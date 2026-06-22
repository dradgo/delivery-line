package org.dradgo.application.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.Optional;
import org.dradgo.application.runner.RunnerProperties;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.project.Project;
import org.dradgo.domain.registry.ConnectorKind;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.ProjectStatus;
import org.dradgo.domain.registry.RunnerKind;
import org.dradgo.domain.registry.RunnerStage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Story 3c-6 (AC3/AC6/AC9) — fast unit coverage for {@link ProjectRuntimeConfigResolver} (the
 * {@link ProjectStore} fully mocked): default-fallback resolution for a run with no {@code
 * project_id}, run-project resolution when bound, missing-project fallback, and the {@code
 * PROJECT_NOT_FOUND} invariant when even the default cannot be resolved.
 */
class ProjectRuntimeConfigResolverTest {

  private static final String RUN_ID = "run_resolver0001";

  private ProjectStore projectStore;
  private RunnerProperties runnerProperties;
  private ProjectRuntimeConfigResolver resolver;

  @BeforeEach
  void setUp() {
    projectStore = mock(ProjectStore.class);
    runnerProperties = mock(RunnerProperties.class);
    resolver = new ProjectRuntimeConfigResolver(projectStore, runnerProperties);
  }

  @Test
  void runWithoutProjectIdResolvesDefaultConfig() {
    when(projectStore.findProjectIdForRun(RUN_ID)).thenReturn(Optional.empty());
    when(projectStore.findBySlug(DefaultProjectSeeder.DEFAULT_PROJECT_SLUG))
        .thenReturn(Optional.of(defaultProject("octo/hello", true)));

    assertThat(resolver.resolveForRun(RUN_ID).publicId())
        .isEqualTo(DefaultProjectSeeder.DEFAULT_PROJECT_PUBLIC_ID);
    assertThat(resolver.resolveRepositoryRef(RUN_ID)).contains("octo/hello");
    assertThat(resolver.resolveOpenSpecEnabled(RUN_ID)).isTrue();
  }

  @Test
  void runBoundToDefaultResolvesTheSameConfig() {
    when(projectStore.findProjectIdForRun(RUN_ID))
        .thenReturn(Optional.of(DefaultProjectSeeder.DEFAULT_PROJECT_PUBLIC_ID));
    when(projectStore.findByPublicId(DefaultProjectSeeder.DEFAULT_PROJECT_PUBLIC_ID))
        .thenReturn(Optional.of(defaultProject("octo/hello", false)));

    assertThat(resolver.resolveForRun(RUN_ID).publicId())
        .isEqualTo(DefaultProjectSeeder.DEFAULT_PROJECT_PUBLIC_ID);
    assertThat(resolver.resolveRepositoryRef(RUN_ID)).contains("octo/hello");
    assertThat(resolver.resolveOpenSpecEnabled(RUN_ID)).isFalse();
  }

  @Test
  void runWithUnresolvableProjectIdFallsBackToDefault() {
    when(projectStore.findProjectIdForRun(RUN_ID)).thenReturn(Optional.of("prj_missing01"));
    when(projectStore.findByPublicId("prj_missing01")).thenReturn(Optional.empty());
    when(projectStore.findBySlug(DefaultProjectSeeder.DEFAULT_PROJECT_SLUG))
        .thenReturn(Optional.of(defaultProject("octo/hello", false)));

    assertThat(resolver.resolveForRun(RUN_ID).publicId())
        .isEqualTo(DefaultProjectSeeder.DEFAULT_PROJECT_PUBLIC_ID);
  }

  @Test
  void emptyRepoBindingResolvesEmptyRef() {
    when(projectStore.findProjectIdForRun(RUN_ID)).thenReturn(Optional.empty());
    when(projectStore.findBySlug(DefaultProjectSeeder.DEFAULT_PROJECT_SLUG))
        .thenReturn(Optional.of(defaultProject(null, false)));

    assertThat(resolver.resolveRepositoryRef(RUN_ID)).isEmpty();
  }

  @Test
  void noDefaultProjectRaisesProjectNotFound() {
    when(projectStore.findProjectIdForRun(RUN_ID)).thenReturn(Optional.empty());
    when(projectStore.findBySlug(DefaultProjectSeeder.DEFAULT_PROJECT_SLUG))
        .thenReturn(Optional.empty());

    assertThatThrownBy(() -> resolver.resolveForRun(RUN_ID))
        .isInstanceOf(DomainException.class)
        .satisfies(
            e ->
                assertThat(((DomainException) e).errorCode())
                    .isEqualTo(DomainErrorCode.PROJECT_NOT_FOUND));
  }

  // ---------------------------------------------------------------------------
  // Story 3d-3 (AC1) — resolveRunnerKind: project override wins, else global per-stage kind.
  // ---------------------------------------------------------------------------

  @Test
  void resolveRunnerKindReturnsProjectOverrideWhenSet() {
    when(projectStore.findProjectIdForRun(RUN_ID)).thenReturn(Optional.empty());
    when(projectStore.findBySlug(DefaultProjectSeeder.DEFAULT_PROJECT_SLUG))
        .thenReturn(Optional.of(projectWithRunnerKind(RunnerKind.MANUAL)));

    assertThat(resolver.resolveRunnerKind(RUN_ID, RunnerStage.INVESTIGATION))
        .isEqualTo(RunnerKind.MANUAL);
    assertThat(resolver.resolveRunnerKind(RUN_ID, RunnerStage.EXECUTION))
        .isEqualTo(RunnerKind.MANUAL);
    // The override is applied across stages — the global per-stage kind is never consulted.
    org.mockito.Mockito.verifyNoInteractions(runnerProperties);
  }

  @Test
  void resolveRunnerKindFallsBackToGlobalPerStageKindWhenNoOverride() {
    when(projectStore.findProjectIdForRun(RUN_ID)).thenReturn(Optional.empty());
    when(projectStore.findBySlug(DefaultProjectSeeder.DEFAULT_PROJECT_SLUG))
        .thenReturn(Optional.of(projectWithRunnerKind(null)));
    when(runnerProperties.kindForStage(RunnerStage.INVESTIGATION)).thenReturn(RunnerKind.CODEX);
    when(runnerProperties.kindForStage(RunnerStage.EXECUTION)).thenReturn(RunnerKind.CLAUDE);

    assertThat(resolver.resolveRunnerKind(RUN_ID, RunnerStage.INVESTIGATION))
        .isEqualTo(RunnerKind.CODEX);
    assertThat(resolver.resolveRunnerKind(RUN_ID, RunnerStage.EXECUTION))
        .isEqualTo(RunnerKind.CLAUDE);
  }

  @Test
  void resolveRunnerKindUsesExecutionSubStageFallbackWhenProvided() {
    when(projectStore.findProjectIdForRun(RUN_ID)).thenReturn(Optional.empty());
    when(projectStore.findBySlug(DefaultProjectSeeder.DEFAULT_PROJECT_SLUG))
        .thenReturn(Optional.of(projectWithRunnerKind(null)));
    when(runnerProperties.kindForExecutionSubStage(
            org.dradgo.application.runner.ExecutionSubStage.PR_OUTPUT))
        .thenReturn(RunnerKind.CLAUDE);

    assertThat(
            resolver.resolveRunnerKind(
                RUN_ID,
                RunnerStage.EXECUTION,
                org.dradgo.application.runner.ExecutionSubStage.PR_OUTPUT))
        .isEqualTo(RunnerKind.CLAUDE);
  }

  // ---------------------------------------------------------------------------
  // Story 3d-2 (AC1/AC5, Task 2) — resolveReviewerKind: null/blank = parity no-op (empty);
  // a valid non-manual kind resolves; an unparseable kind or MANUAL is a graceful misconfiguration
  // tagged REVIEWER_MODEL_NOT_CONFIGURED (caught by the dispatch trigger, never a 5xx into the
  // run).
  // ---------------------------------------------------------------------------

  @Test
  void resolveReviewerKindIsEmptyWhenNoBinding() {
    when(projectStore.findProjectIdForRun(RUN_ID)).thenReturn(Optional.empty());
    when(projectStore.findBySlug(DefaultProjectSeeder.DEFAULT_PROJECT_SLUG))
        .thenReturn(Optional.of(projectWithReviewerKind(null)));

    assertThat(resolver.resolveReviewerKind(RUN_ID)).isEmpty();
  }

  @Test
  void resolveReviewerKindResolvesValidNonManualKind() {
    when(projectStore.findProjectIdForRun(RUN_ID)).thenReturn(Optional.empty());
    when(projectStore.findBySlug(DefaultProjectSeeder.DEFAULT_PROJECT_SLUG))
        .thenReturn(Optional.of(projectWithReviewerKind("claude")));

    assertThat(resolver.resolveReviewerKind(RUN_ID)).contains(RunnerKind.CLAUDE);
  }

  @Test
  void resolveReviewerKindDegradesOnUnparseableKind() {
    when(projectStore.findProjectIdForRun(RUN_ID)).thenReturn(Optional.empty());
    when(projectStore.findBySlug(DefaultProjectSeeder.DEFAULT_PROJECT_SLUG))
        .thenReturn(Optional.of(projectWithReviewerKind("gpt-9000")));

    assertThatThrownBy(() -> resolver.resolveReviewerKind(RUN_ID))
        .isInstanceOf(DomainException.class)
        .satisfies(
            e ->
                assertThat(((DomainException) e).errorCode())
                    .isEqualTo(DomainErrorCode.REVIEWER_MODEL_NOT_CONFIGURED));
  }

  @Test
  void resolveReviewerKindRejectsManualReviewer() {
    when(projectStore.findProjectIdForRun(RUN_ID)).thenReturn(Optional.empty());
    when(projectStore.findBySlug(DefaultProjectSeeder.DEFAULT_PROJECT_SLUG))
        .thenReturn(Optional.of(projectWithReviewerKind("manual")));

    assertThatThrownBy(() -> resolver.resolveReviewerKind(RUN_ID))
        .isInstanceOf(DomainException.class)
        .satisfies(
            e ->
                assertThat(((DomainException) e).errorCode())
                    .isEqualTo(DomainErrorCode.REVIEWER_MODEL_NOT_CONFIGURED));
  }

  private static Project projectWithReviewerKind(String reviewerModelKind) {
    return new Project(
        DefaultProjectSeeder.DEFAULT_PROJECT_PUBLIC_ID,
        DefaultProjectSeeder.DEFAULT_PROJECT_NAME,
        DefaultProjectSeeder.DEFAULT_PROJECT_SLUG,
        ProjectStatus.ACTIVE,
        "octo/hello",
        ConnectorKind.LINEAR,
        ConnectorKind.GITHUB,
        false,
        reviewerModelKind,
        false,
        null,
        OffsetDateTime.parse("2026-06-20T00:00:00Z"),
        null);
  }

  private static Project projectWithRunnerKind(RunnerKind runnerKind) {
    return new Project(
        DefaultProjectSeeder.DEFAULT_PROJECT_PUBLIC_ID,
        DefaultProjectSeeder.DEFAULT_PROJECT_NAME,
        DefaultProjectSeeder.DEFAULT_PROJECT_SLUG,
        ProjectStatus.ACTIVE,
        "octo/hello",
        ConnectorKind.LINEAR,
        ConnectorKind.GITHUB,
        false,
        null,
        false,
        runnerKind,
        OffsetDateTime.parse("2026-06-20T00:00:00Z"),
        null);
  }

  private static Project defaultProject(String repositoryUrl, boolean openspecEnabled) {
    return new Project(
        DefaultProjectSeeder.DEFAULT_PROJECT_PUBLIC_ID,
        DefaultProjectSeeder.DEFAULT_PROJECT_NAME,
        DefaultProjectSeeder.DEFAULT_PROJECT_SLUG,
        ProjectStatus.ACTIVE,
        repositoryUrl,
        ConnectorKind.LINEAR,
        ConnectorKind.GITHUB,
        openspecEnabled,
        null,
        false,
        null,
        OffsetDateTime.parse("2026-06-20T00:00:00Z"),
        null);
  }
}
