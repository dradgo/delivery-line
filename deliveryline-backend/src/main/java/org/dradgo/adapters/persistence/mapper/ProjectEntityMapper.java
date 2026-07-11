package org.dradgo.adapters.persistence.mapper;

import java.util.Map;
import org.dradgo.adapters.persistence.entity.ProjectEntity;
import org.dradgo.domain.project.Project;
import org.dradgo.domain.registry.ProjectRunnerStep;
import org.dradgo.domain.registry.RunnerKind;
import org.springframework.stereotype.Component;

/**
 * Story 3c-6 — entity &harr; domain translation for {@code projects}. Explicit (no MapStruct) to
 * match the {@code IntegrationLinkEntityMapper}/{@code WorkflowRunEntityMapper} pattern.
 *
 * <p>{@code toNewEntity} leaves {@code id} DB-generated but stamps {@code created_at} from the
 * domain {@code createdAt} (Hibernate does not refresh an entity after {@code saveAndFlush}, so a
 * DB-defaulted {@code insertable=false} column would read back null and trip the {@code Project}
 * record's non-null {@code createdAt} guard). The raw enum text is written through the entity's
 * typed setters (which call {@code RegistryValue.value()}), keeping the single registry-string
 * source.
 */
@Component
public class ProjectEntityMapper {

  /**
   * Back-compat read (no per-step rows loaded) — used by the mapper unit test + any caller that has
   * no child rows. Delegates to the {@code (entity, stepRunnerKinds)} form with an empty map.
   */
  public Project toDomain(ProjectEntity entity) {
    return toDomain(entity, Map.of());
  }

  /**
   * Story 3e-4 (AC3) — entity + the per-step runner map loaded from {@code project_runner_kinds} →
   * domain. The adapter loads the child rows and passes them here; an empty map round-trips as zero
   * rows (3d-3 parity).
   */
  public Project toDomain(
      ProjectEntity entity, Map<ProjectRunnerStep, RunnerKind> stepRunnerKinds) {
    return new Project(
        entity.getPublicId(),
        entity.getName(),
        entity.getSlug(),
        entity.getStatus(),
        entity.getRepositoryUrl(),
        entity.getTicketSourceKind(),
        entity.getRepoHostKind(),
        entity.isOpenspecEnabled(),
        // Story 3d-1 review patch: DD-1 leaves reviewer_model_kind without a DB CHECK, so a blank
        // (empty/whitespace) row value is persistable but would trip the Project record's
        // non-blank-if-set invariant on read — making the project (and any findAll over it)
        // un-readable. Coerce blank to null here: NULL is the canonical "no reviewer" value, so the
        // read path is symmetric with that semantic and robust to a future bad write / direct SQL.
        blankToNull(entity.getReviewerModelKind()),
        entity.isReviewerGatingEnabled(),
        // Story 3d-3 (AC1) — nullable per-project runner-kind override (parsed through
        // RunnerKind.fromValue in the entity getter; null round-trips as null).
        entity.getRunnerKind(),
        entity.getCreatedAt(),
        entity.getArchivedAt(),
        stepRunnerKinds,
        // Story 3h-1 (AC2) — nullable per-project build command. Coerce blank to null on read
        // (mirrors reviewerModelKind): build_command has no DB CHECK, so a blank row value would
        // trip the Project record's non-blank-if-set invariant. NULL is the canonical "no build".
        blankToNull(entity.getBuildCommand()),
        entity.isBuildStageEnabled(),
        // Story 3h-2 (AC2) — split the newline-delimited lint_commands text back into the domain
        // list (null/blank text → empty list; blank lines dropped). Project's compact ctor copies
        // it.
        splitLintCommands(entity.getLintCommands()),
        entity.isLintStageEnabled(),
        // Story 3h-4 (AC1) — per-project delivery config (push_mode parsed through
        // PushMode.fromValue
        // in the entity getter; auto_create_pull_request a plain boolean).
        entity.getPushMode(),
        entity.isAutoCreatePullRequest(),
        // Task 4 (DinD Testcontainers sidecar) — plain boolean, no parsing needed.
        entity.isTestcontainersEnabled());
  }

  public ProjectEntity toNewEntity(Project project) {
    ProjectEntity entity = new ProjectEntity();
    entity.setPublicId(project.publicId());
    entity.setName(project.name());
    entity.setSlug(project.slug());
    entity.setStatus(project.status());
    entity.setRepositoryUrl(project.repositoryUrl());
    entity.setTicketSourceKind(project.ticketSourceKind());
    entity.setRepoHostKind(project.repoHostKind());
    entity.setOpenspecEnabled(project.openspecEnabled());
    entity.setReviewerModelKind(project.reviewerModelKind());
    entity.setReviewerGatingEnabled(project.reviewerGatingEnabled());
    entity.setRunnerKind(project.runnerKind());
    // Story 3h-1 (AC2) — per-project build config round-trips on insert.
    entity.setBuildCommand(project.buildCommand());
    entity.setBuildStageEnabled(project.buildStageEnabled());
    // Story 3h-2 (AC2) — per-project lint config round-trips on insert (list ↔ newline-delimited).
    entity.setLintCommands(joinLintCommands(project.lintCommands()));
    entity.setLintStageEnabled(project.lintStageEnabled());
    // Story 3h-4 (AC1) — per-project delivery config round-trips on insert.
    entity.setPushMode(project.pushMode());
    entity.setAutoCreatePullRequest(project.autoCreatePullRequest());
    // Task 4 (DinD Testcontainers sidecar) — per-project flag round-trips on insert.
    entity.setTestcontainersEnabled(project.testcontainersEnabled());
    entity.setCreatedAt(project.createdAt());
    entity.setArchivedAt(project.archivedAt());
    return entity;
  }

  /**
   * Story 3c-8 (AC1) — merge the editable columns of {@code project} onto an already-loaded {@code
   * entity} for the update path. Deliberately does NOT touch {@code id} or {@code created_at}: the
   * surrogate key and the immutable creation timestamp are authoritative on the persisted row (the
   * domain {@code createdAt} is the value just read from it, so re-stamping it is a no-op — but we
   * leave it untouched to honor the 3c-6 {@code created_at} no-refresh trap explicitly). The {@code
   * publicId} is the lookup key and is never reassigned here.
   */
  public ProjectEntity applyEditableColumns(ProjectEntity entity, Project project) {
    entity.setName(project.name());
    entity.setSlug(project.slug());
    entity.setStatus(project.status());
    entity.setRepositoryUrl(project.repositoryUrl());
    entity.setTicketSourceKind(project.ticketSourceKind());
    entity.setRepoHostKind(project.repoHostKind());
    entity.setOpenspecEnabled(project.openspecEnabled());
    // Reviewer binding is editable project config (no REST surface yet in 3d-1; 3d-2 owns editing).
    // Threading it through the update path keeps a read-modify-write round-trip lossless.
    entity.setReviewerModelKind(project.reviewerModelKind());
    entity.setReviewerGatingEnabled(project.reviewerGatingEnabled());
    // Runner-kind override is editable project config (no REST surface yet in 3d-3; a future story
    // owns editing). Threading it through update keeps a read-modify-write round-trip lossless.
    entity.setRunnerKind(project.runnerKind());
    // Story 3h-1 (AC2) — build config is editable project config; thread it through update so a
    // read-modify-write round-trip stays lossless (mirrors reviewer binding / runner-kind).
    entity.setBuildCommand(project.buildCommand());
    entity.setBuildStageEnabled(project.buildStageEnabled());
    // Story 3h-2 (AC2) — lint config is editable project config; thread it through update so a
    // read-modify-write round-trip stays lossless (mirrors build config).
    entity.setLintCommands(joinLintCommands(project.lintCommands()));
    entity.setLintStageEnabled(project.lintStageEnabled());
    // Story 3h-4 (AC1) — delivery config is editable project config; thread it through update so a
    // read-modify-write round-trip stays lossless (mirrors build/lint config).
    entity.setPushMode(project.pushMode());
    entity.setAutoCreatePullRequest(project.autoCreatePullRequest());
    // Task 4 (DinD Testcontainers sidecar) — editable project config; thread it through update so a
    // read-modify-write round-trip stays lossless (mirrors build/lint/delivery config).
    entity.setTestcontainersEnabled(project.testcontainersEnabled());
    entity.setArchivedAt(project.archivedAt());
    return entity;
  }

  private static String blankToNull(String value) {
    return (value == null || value.isBlank()) ? null : value;
  }

  /**
   * Story 3h-2 — the domain lint command list → the newline-delimited {@code lint_commands} text
   * column. An empty/null list serializes to NULL (the canonical "no lint commands" value); blank
   * entries are dropped so a stray newline never yields a blank command.
   */
  private static String joinLintCommands(java.util.List<String> lintCommands) {
    if (lintCommands == null || lintCommands.isEmpty()) {
      return null;
    }
    String joined =
        lintCommands.stream()
            .filter(command -> command != null && !command.isBlank())
            .map(String::trim)
            .collect(java.util.stream.Collectors.joining("\n"));
    return joined.isBlank() ? null : joined;
  }

  /**
   * Story 3h-2 — the newline-delimited {@code lint_commands} text column → the domain lint command
   * list. A null/blank column reads back an empty list; blank lines are dropped.
   */
  private static java.util.List<String> splitLintCommands(String lintCommands) {
    if (lintCommands == null || lintCommands.isBlank()) {
      return java.util.List.of();
    }
    return lintCommands.lines().map(String::trim).filter(line -> !line.isBlank()).toList();
  }
}
