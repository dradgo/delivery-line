package org.dradgo.adapters.runner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import org.dradgo.application.project.ProjectConnectorResolver;
import org.dradgo.application.project.ProjectRuntimeConfigResolver;
import org.dradgo.application.runner.ExecutionConstraints;
import org.dradgo.application.runner.RunnerDispatchRequest;
import org.dradgo.application.runner.RunnerProperties;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.project.Project;
import org.dradgo.domain.registry.ConnectorKind;
import org.dradgo.domain.registry.ConnectorRole;
import org.dradgo.domain.registry.DataClassification;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.ProjectStatus;
import org.dradgo.domain.registry.RunnerKind;
import org.dradgo.domain.registry.RunnerStage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Story 3d-2 (AC1, Task 10) — the reviewer dispatch resolves its provider key from the PER-PROJECT
 * reviewer credential (through {@link ProjectConnectorResolver}), injects it under the kind's
 * preferred env-var name, and NEVER logs the secret value. A configured reviewer with no active
 * reviewer credential fails fast with {@code DOCTOR_RUNNER_SECRET_MISSING} (the worker then
 * degrades — AC6). A non-REVIEW dispatch is byte-identical to the story-3.5 host-key path.
 */
class DockerRunnerAdapterReviewerCredentialTest {

  private static final String SECRET = "rk-reviewer-super-secret-token-value";
  private static final Clock CLOCK =
      Clock.fixed(OffsetDateTime.parse("2026-06-22T10:00:00Z").toInstant(), ZoneOffset.UTC);

  private RunnerProperties properties;
  private ProjectRuntimeConfigResolver runtimeResolver;
  private ProjectConnectorResolver connectorResolver;
  private DockerRunnerAdapter adapter;
  private ListAppender<ILoggingEvent> appender;

  @BeforeEach
  void setUp() {
    properties = RunnerProperties.defaults();
    runtimeResolver = mock(ProjectRuntimeConfigResolver.class);
    connectorResolver = mock(ProjectConnectorResolver.class);
    adapter =
        new DockerRunnerAdapter(
            mock(org.dradgo.application.runner.spi.RunnerScratchStore.class),
            mock(org.dradgo.application.runner.spi.RunnerWorkspaceStore.class),
            mock(org.dradgo.adapters.runner.docker.DockerEngineGateway.class),
            properties,
            new org.dradgo.application.runner.RunnerSecretsService(
                new org.springframework.mock.env.MockEnvironment()
                    .withProperty("CLAUDE_CODE_OAUTH_TOKEN", "host-token-should-not-be-used"),
                properties),
            mock(org.dradgo.application.runner.RunnerLogCaptureService.class),
            mock(org.dradgo.application.runner.RunnerExecutionService.class),
            CLOCK);
    adapter.setProjectRuntimeConfigResolver(runtimeResolver);
    adapter.setProjectConnectorResolver(connectorResolver);

    appender = new ListAppender<>();
    appender.start();
    ((Logger) LoggerFactory.getLogger(DockerRunnerAdapter.class)).addAppender(appender);
  }

  @AfterEach
  void detach() {
    ((Logger) LoggerFactory.getLogger(DockerRunnerAdapter.class)).detachAppender(appender);
  }

  @Test
  void reviewerResolvesPerProjectCredentialUnderPreferredNameAndNeverLogsIt() {
    when(runtimeResolver.resolveForRun("run_revcred0001")).thenReturn(project("claude"));
    when(connectorResolver.resolveConnectorSecret(any(), eq(ConnectorRole.REVIEWER.value())))
        .thenReturn(java.util.Optional.of(SECRET));

    Map<String, String> env = adapter.resolveSecretEnv(reviewRequest(), RunnerKind.CLAUDE);

    String preferredName = properties.secretEnvNamesFor(RunnerKind.CLAUDE).get(0);
    assertThat(env).containsEntry(preferredName, SECRET).hasSize(1);
    // AC1 — the secret value must never appear in any log line (only a count is logged elsewhere).
    assertThat(appender.list).noneMatch(event -> event.getFormattedMessage().contains(SECRET));
  }

  @Test
  void reviewerWithoutCredentialFailsFast() {
    when(runtimeResolver.resolveForRun("run_revcred0001")).thenReturn(project("claude"));
    when(connectorResolver.resolveConnectorSecret(any(), eq(ConnectorRole.REVIEWER.value())))
        .thenReturn(java.util.Optional.empty());

    assertThatThrownBy(() -> adapter.resolveSecretEnv(reviewRequest(), RunnerKind.CLAUDE))
        .isInstanceOf(DomainException.class)
        .satisfies(
            e ->
                assertThat(((DomainException) e).errorCode())
                    .isEqualTo(DomainErrorCode.DOCTOR_RUNNER_SECRET_MISSING));
  }

  @Test
  void nonReviewDispatchUsesHostKeyPath() {
    Map<String, String> env =
        adapter.resolveSecretEnv(
            new RunnerDispatchRequest(
                "rex_revcred0001",
                "run_revcred0001",
                RunnerStage.INVESTIGATION,
                RunnerKind.CLAUDE,
                java.nio.file.Path.of("bundle.json"),
                new ExecutionConstraints(Duration.ofSeconds(600), false),
                DataClassification.SHAREABLE_REDACTED),
            RunnerKind.CLAUDE);

    // The host CLAUDE_CODE_OAUTH_TOKEN resolves; the per-project resolvers are never consulted.
    assertThat(env).containsEntry("CLAUDE_CODE_OAUTH_TOKEN", "host-token-should-not-be-used");
    org.mockito.Mockito.verifyNoInteractions(connectorResolver);
  }

  private static RunnerDispatchRequest reviewRequest() {
    return new RunnerDispatchRequest(
        "rex_revcred0001",
        "run_revcred0001",
        RunnerStage.REVIEW,
        RunnerKind.CLAUDE,
        java.nio.file.Path.of("bundle.json"),
        new ExecutionConstraints(Duration.ofSeconds(600), false),
        DataClassification.SHAREABLE_REDACTED);
  }

  private static Project project(String reviewerModelKind) {
    return new Project(
        "prj_revcred00001",
        "Reviewer Cred Test",
        "reviewer-cred-test",
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
}
