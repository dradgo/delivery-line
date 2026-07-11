package org.dradgo.application.project;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.id.PublicIdPrefixes;
import org.dradgo.domain.project.Project;
import org.dradgo.domain.registry.ConnectorKind;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.ProjectRunnerStep;
import org.dradgo.domain.registry.ProjectStatus;
import org.dradgo.domain.registry.PushMode;
import org.dradgo.domain.registry.RunnerKind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Story 3c-8 (AC1/AC6) — CRUD/disable orchestration over the {@link ProjectStore}. The
 * {@code @Service} suffix satisfies {@code APPLICATION_SERVICES_MUST_BE_NAMED_AS_SERVICES}; it
 * depends only on {@code application.project} + {@code domain.*} (never {@code adapters..}/{@code
 * infrastructure..}).
 *
 * <p>The reserved {@code default} project (the runtime fallback resolved by {@code
 * ProjectRuntimeConfigResolver}) is editable but <strong>cannot be disabled</strong> (R7b):
 * disabling it would break every default-project run's resolution.
 */
@Service
public class ProjectManagementService {

  private static final Logger log = LoggerFactory.getLogger(ProjectManagementService.class);

  // Project-level allowed-action wire values (AC6). A List<String> matches the workflow
  // AllowedActionsResponse `actions: String[]` shape; projects gate on status only (no RBAC, R6).
  static final String ACTION_EDIT = "edit";
  static final String ACTION_DISABLE = "disable";
  static final String ACTION_ENABLE = "enable";
  static final String ACTION_SET_CREDENTIAL = "set_credential";
  static final String ACTION_TEST_CONNECTION = "test_connection";

  private final ProjectStore projectStore;

  public ProjectManagementService(ProjectStore projectStore) {
    this.projectStore = Objects.requireNonNull(projectStore, "projectStore");
  }

  /** AC1 — every project, creation-ordered (includes disabled projects). */
  @Transactional(readOnly = true)
  public List<Project> listProjects() {
    List<Project> projects = projectStore.findAll();
    log.info("project list resolved count={}", projects.size());
    return projects;
  }

  /** AC1 — read one project by public id; {@code PROJECT_NOT_FOUND} on a miss. */
  @Transactional(readOnly = true)
  public Project getProject(String publicId) {
    return projectStore
        .findByPublicId(publicId)
        .orElseThrow(() -> ProjectErrors.projectNotFound(publicId));
  }

  /**
   * AC1 — create a project: generate a {@code prj_} public id, parse the connector kinds (a bad
   * value surfaces as a typed 400 via {@code ConnectorKind.fromValue}), stamp {@code ACTIVE} +
   * {@code createdAt}, and insert. A slug collision surfaces as {@code PROJECT_SLUG_CONFLICT} at
   * the persistence adapter.
   */
  @Transactional
  public Project createProject(CreateProjectCommand command) {
    Objects.requireNonNull(command, "command");
    // The reserved `default` slug belongs to the seeded runtime-fallback project; a user must not
    // claim it through the create surface (the uq_projects_slug index also backstops this once the
    // seed exists, but reject it explicitly so the create is well-defined even if the seed is
    // absent).
    if (DefaultProjectSeeder.DEFAULT_PROJECT_SLUG.equals(command.slug())) {
      Map<String, Object> details = new LinkedHashMap<>();
      details.put("slug", command.slug());
      details.put("reason", "reserved_default_slug");
      throw new DomainException(
          DomainErrorCode.PROJECT_SLUG_CONFLICT,
          "The slug '" + DefaultProjectSeeder.DEFAULT_PROJECT_SLUG + "' is reserved",
          details);
    }
    ConnectorKind ticketSourceKind =
        ConnectorKind.fromValue(command.ticketSourceKind(), "ticketSourceKind");
    ConnectorKind repoHostKind = ConnectorKind.fromValue(command.repoHostKind(), "repoHostKind");
    RunnerKind runnerKind = parseRunnerKind(command.runnerKind());
    Map<ProjectRunnerStep, RunnerKind> stepRunnerKinds =
        parseStepRunnerKinds(command.stepRunnerKinds());
    Project project =
        new Project(
            PublicIdPrefixes.PROJECT.next(),
            command.name(),
            command.slug(),
            ProjectStatus.ACTIVE,
            normalizeRepositoryUrl(command.repositoryUrl()),
            ticketSourceKind,
            repoHostKind,
            command.openspecEnabled(),
            // Story 3d-1 (AC4) — a created project has no reviewer until 3d-2 wires the edit path.
            null,
            false,
            // Nullable per-project override; null delegates to stage/global runner defaults.
            runnerKind,
            OffsetDateTime.now(ZoneOffset.UTC),
            null,
            // Story 3e-4 (AC6) — the per-step runner mapping persisted to project_runner_kinds.
            stepRunnerKinds,
            // Story 3h-1 (AC2) — per-project build config from the create command (blank command
            // coerced to null so the Project non-blank-if-set invariant holds; no BUILD then).
            normalizeBuildCommand(command.buildCommand()),
            command.buildStageEnabled(),
            // Story 3h-2 (AC2) — per-project lint config from the command (blank entries dropped;
            // empty ⇒ no lint).
            normalizeLintCommands(command.lintCommands()),
            command.lintStageEnabled(),
            // Story 3h-4 (AC1) — per-project delivery config from the create command (null pushMode
            // ⇒ AUTO; autoCreatePullRequest is a plain boolean).
            parsePushMode(command.pushMode()),
            command.autoCreatePullRequest(),
            // Task 4 (DinD Testcontainers sidecar) — per-project opt-in from the create command.
            command.testcontainersEnabled());
    Project created = projectStore.insert(project);
    log.info(
        "project created projectId={} slug={} ticketSourceKind={} repoHostKind={} status={}",
        created.publicId(),
        created.slug(),
        created.ticketSourceKind().value(),
        created.repoHostKind().value(),
        created.status().value());
    return created;
  }

  /**
   * AC1 — edit a project's mutable config (name / repository url / kinds / OpenSpec flag). The
   * slug, public id, status, {@code createdAt}, and {@code archivedAt} are preserved (status
   * changes go through {@link #disableProject}). {@code PROJECT_NOT_FOUND} on a miss.
   */
  @Transactional
  public Project updateProject(String publicId, UpdateProjectCommand command) {
    Objects.requireNonNull(command, "command");
    Project existing = getProject(publicId);
    ConnectorKind ticketSourceKind =
        ConnectorKind.fromValue(command.ticketSourceKind(), "ticketSourceKind");
    ConnectorKind repoHostKind = ConnectorKind.fromValue(command.repoHostKind(), "repoHostKind");
    RunnerKind runnerKind = parseRunnerKind(command.runnerKind());
    String reviewerModelKind = parseReviewerModelKind(command.reviewerModelKind());
    Map<ProjectRunnerStep, RunnerKind> stepRunnerKinds =
        parseStepRunnerKinds(command.stepRunnerKinds());
    Project mutated =
        new Project(
            existing.publicId(),
            command.name(),
            existing.slug(),
            existing.status(),
            normalizeRepositoryUrl(command.repositoryUrl()),
            ticketSourceKind,
            repoHostKind,
            command.openspecEnabled(),
            // Story 3d-2 — the advisory-reviewer binding is now editable here: null/blank clears
            // it,
            // a non-blank value is validated to a non-MANUAL RunnerKind by parseReviewerModelKind.
            reviewerModelKind,
            existing.reviewerGatingEnabled(),
            // The update surface replaces or clears the nullable per-project runner override.
            runnerKind,
            existing.createdAt(),
            existing.archivedAt(),
            // Story 3e-4 (AC6) — full-replace: the submitted per-step map is authoritative.
            stepRunnerKinds,
            // Story 3h-1 (AC2) — build config is editable; full-replace from the update command
            // (blank command clears to null / no build).
            normalizeBuildCommand(command.buildCommand()),
            command.buildStageEnabled(),
            // Story 3h-2 (AC2) — per-project lint config from the command (blank entries dropped;
            // empty ⇒ no lint).
            normalizeLintCommands(command.lintCommands()),
            command.lintStageEnabled(),
            // Story 3h-4 (AC1) — delivery config is editable; full-replace from the update command
            // (null pushMode ⇒ AUTO).
            parsePushMode(command.pushMode()),
            command.autoCreatePullRequest(),
            // Task 4 (DinD Testcontainers sidecar) — editable; full-replace from the update
            // command.
            command.testcontainersEnabled());
    Project updated = projectStore.update(mutated);
    log.info(
        "project updated projectId={} slug={} ticketSourceKind={} repoHostKind={} status={}",
        updated.publicId(),
        updated.slug(),
        updated.ticketSourceKind().value(),
        updated.repoHostKind().value(),
        updated.status().value());
    return updated;
  }

  /**
   * AC1/R7b — soft-disable a project ({@code status := DISABLED}); leaves {@code archivedAt} null
   * (status-only, Open Decision #4 default). The reserved {@code default} project cannot be
   * disabled (it is the runtime fallback) — attempting it is rejected at the boundary.
   */
  @Transactional
  public Project disableProject(String publicId) {
    Project existing = getProject(publicId);
    // Key the immutability guard on the reserved public id (identity), not the user-editable slug.
    if (DefaultProjectSeeder.DEFAULT_PROJECT_PUBLIC_ID.equals(existing.publicId())) {
      log.warn(
          "project disable rejected: the default project is the runtime fallback projectId={}",
          existing.publicId());
      Map<String, Object> details = new LinkedHashMap<>();
      details.put("projectId", existing.publicId());
      details.put("slug", existing.slug());
      details.put("reason", "default_project_immutable");
      // Open Decision #3 default: no new DomainErrorCode — reject at the boundary with the generic
      // request-rejection code (a well-behaved client never sends `disable` for the default project
      // because its allowedActions omits it).
      throw new DomainException(
          DomainErrorCode.INVALID_COMMAND_PAYLOAD,
          "The default project cannot be disabled — it is the runtime fallback",
          details);
    }
    Project mutated =
        new Project(
            existing.publicId(),
            existing.name(),
            existing.slug(),
            ProjectStatus.DISABLED,
            existing.repositoryUrl(),
            existing.ticketSourceKind(),
            existing.repoHostKind(),
            existing.openspecEnabled(),
            existing.reviewerModelKind(),
            existing.reviewerGatingEnabled(),
            existing.runnerKind(),
            existing.createdAt(),
            existing.archivedAt(),
            // Story 3e-4 — preserve the per-step map across a status-only change (update
            // full-replaces
            // it from the submitted aggregate, so a status flip must carry the existing mapping).
            existing.stepRunnerKinds(),
            // Story 3h-1 — preserve build config across a status-only change (the back-compat
            // 14-arg ctor would default it to (null,false) and silently wipe it).
            existing.buildCommand(),
            existing.buildStageEnabled(),
            // Story 3h-2 — preserve lint config across a status-only change (the back-compat 16-arg
            // ctor would default it to (empty,false) and silently wipe it).
            existing.lintCommands(),
            existing.lintStageEnabled(),
            // Story 3h-4 — preserve delivery config across a status-only change (the back-compat
            // 18-arg ctor would default it to (AUTO,true) and silently wipe a non-auto mode).
            existing.pushMode(),
            existing.autoCreatePullRequest(),
            // Task 4 — preserve the testcontainers flag across a status-only change (the
            // back-compat
            // 20-arg ctor would default it to false and silently wipe it).
            existing.testcontainersEnabled());
    Project disabled = projectStore.update(mutated);
    log.info(
        "project disabled projectId={} slug={} status={}",
        disabled.publicId(),
        disabled.slug(),
        disabled.status().value());
    return disabled;
  }

  /**
   * AC6 — re-enable a disabled project ({@code status := ACTIVE}). The reserved {@code default}
   * project is always {@code ACTIVE}, so enabling it is a harmless no-op (no guard needed); a
   * {@code DISABLED} non-default project becomes reachable again. {@code PROJECT_NOT_FOUND} on a
   * miss.
   */
  @Transactional
  public Project enableProject(String publicId) {
    Project existing = getProject(publicId);
    Project mutated =
        new Project(
            existing.publicId(),
            existing.name(),
            existing.slug(),
            ProjectStatus.ACTIVE,
            existing.repositoryUrl(),
            existing.ticketSourceKind(),
            existing.repoHostKind(),
            existing.openspecEnabled(),
            existing.reviewerModelKind(),
            existing.reviewerGatingEnabled(),
            existing.runnerKind(),
            existing.createdAt(),
            existing.archivedAt(),
            // Story 3e-4 — preserve the per-step map across the status-only re-enable
            // (full-replace).
            existing.stepRunnerKinds(),
            // Story 3h-1 — preserve build config across the status-only re-enable.
            existing.buildCommand(),
            existing.buildStageEnabled(),
            // Story 3h-2 — preserve lint config across a status-only change (the back-compat 16-arg
            // ctor would default it to (empty,false) and silently wipe it).
            existing.lintCommands(),
            existing.lintStageEnabled(),
            // Story 3h-4 — preserve delivery config across the status-only re-enable.
            existing.pushMode(),
            existing.autoCreatePullRequest(),
            // Task 4 — preserve the testcontainers flag across the status-only re-enable.
            existing.testcontainersEnabled());
    Project enabled = projectStore.update(mutated);
    log.info(
        "project enabled projectId={} slug={} status={}",
        enabled.publicId(),
        enabled.slug(),
        enabled.status().value());
    return enabled;
  }

  /**
   * AC6 — backend-derived, status-only allowed actions for a project (no RBAC, R6). A small pure
   * helper so the controller, response mapper, and tests share one derivation.
   *
   * <ul>
   *   <li>{@code ACTIVE} non-default → {@code edit, disable, set_credential, test_connection}
   *   <li>{@code ACTIVE} default → {@code edit, set_credential, test_connection} (never disable)
   *   <li>{@code DISABLED} → {@code edit, enable, set_credential, test_connection}
   * </ul>
   */
  public static List<String> allowedActionsFor(Project project) {
    Objects.requireNonNull(project, "project");
    boolean isDefault = DefaultProjectSeeder.DEFAULT_PROJECT_PUBLIC_ID.equals(project.publicId());
    if (project.status() == ProjectStatus.DISABLED) {
      return List.of(ACTION_EDIT, ACTION_ENABLE, ACTION_SET_CREDENTIAL, ACTION_TEST_CONNECTION);
    }
    if (isDefault) {
      return List.of(ACTION_EDIT, ACTION_SET_CREDENTIAL, ACTION_TEST_CONNECTION);
    }
    return List.of(ACTION_EDIT, ACTION_DISABLE, ACTION_SET_CREDENTIAL, ACTION_TEST_CONNECTION);
  }

  private static String normalizeRepositoryUrl(String repositoryUrl) {
    if (repositoryUrl == null) {
      return null;
    }
    String trimmed = repositoryUrl.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  private static RunnerKind parseRunnerKind(String runnerKind) {
    return runnerKind == null ? null : RunnerKind.fromValue(runnerKind, "runnerKind");
  }

  /**
   * Story 3h-4 (AC1) — parse/validate the per-project push mode for persistence. null/blank ⇒ the
   * {@link PushMode#AUTO} default (push inline, pre-3h parity — mirrors {@code parseRunnerKind}'s
   * null handling but with a non-null default because {@code push_mode} is a NOT NULL column). A
   * non-blank value must resolve to a real {@link PushMode} ({@code auto}/{@code manual}/{@code
   * approve}); an unknown value surfaces a typed {@code UNKNOWN_REGISTRY_VALUE} 400 via {@code
   * PushMode.fromValue}.
   */
  private static PushMode parsePushMode(String pushMode) {
    return (pushMode == null || pushMode.isBlank())
        ? PushMode.AUTO
        : PushMode.fromValue(pushMode, "pushMode");
  }

  /**
   * Story 3h-1 (AC2) — normalize the per-project build command for persistence: null/blank ⇒ null
   * (the canonical "no build command" value the domain stores as NULL — BUILD skipped). No enum
   * validation: the command is opaque and validated only at execution time.
   */
  private static String normalizeBuildCommand(String buildCommand) {
    if (buildCommand == null) {
      return null;
    }
    String trimmed = buildCommand.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  /**
   * Story 3h-2 (AC2) — normalize the per-project lint commands for persistence: drop null/blank
   * entries and trim the survivors (empty ⇒ the canonical "no lint commands" value — LINT skipped).
   * No enum validation: the commands are opaque and validated only at execution time.
   */
  private static java.util.List<String> normalizeLintCommands(java.util.List<String> lintCommands) {
    if (lintCommands == null) {
      return java.util.List.of();
    }
    return lintCommands.stream()
        .filter(command -> command != null && !command.isBlank())
        .map(String::trim)
        .toList();
  }

  /**
   * Story 3d-2 — parse/validate the advisory-reviewer binding for persistence. null/blank clears it
   * (the canonical "no reviewer" value the domain stores as NULL). A non-blank value must resolve
   * to a real reviewer model: an unknown kind surfaces a typed {@code UNKNOWN_REGISTRY_VALUE} 400
   * (via {@code RunnerKind.fromValue}), and {@code manual} is rejected with {@code
   * INVALID_COMMAND_PAYLOAD} because a manual reviewer is nonsensical (it would park, not produce a
   * verdict — mirrors {@code ProjectRuntimeConfigResolver.resolveReviewerKind}). The stored value
   * is the canonical kind value.
   */
  private static String parseReviewerModelKind(String reviewerModelKind) {
    if (reviewerModelKind == null || reviewerModelKind.isBlank()) {
      return null;
    }
    RunnerKind kind = RunnerKind.fromValue(reviewerModelKind, "reviewerModelKind");
    if (kind == RunnerKind.MANUAL) {
      Map<String, Object> details = new LinkedHashMap<>();
      details.put("reviewerModelKind", reviewerModelKind);
      details.put("reason", "manual is not a valid reviewer model");
      throw new DomainException(
          DomainErrorCode.INVALID_COMMAND_PAYLOAD,
          "reviewerModelKind 'manual' is not a valid reviewer",
          details);
    }
    return kind.value();
  }

  /**
   * Story 3e-4 (AC6/AC8) — parse the raw wire per-step map (step → kind) into the typed domain map.
   * An unknown step or runner kind surfaces as a typed {@code UNKNOWN_REGISTRY_VALUE} 400 (via
   * {@code ProjectRunnerStep}/{@code RunnerKind} fromValue), never a 500. A null/empty map → empty
   * (no per-step mapping). The parsed map preserves the request's insertion order for the inbound
   * persist + log path; note a project later READ back from persistence is re-ordered by {@code
   * step} value (see {@code ProjectPersistenceAdapter.loadStepRunnerKinds}), so the round-tripped
   * wire/log order is step-sorted, not insertion order.
   */
  private static Map<ProjectRunnerStep, RunnerKind> parseStepRunnerKinds(Map<String, String> raw) {
    if (raw == null || raw.isEmpty()) {
      return Map.of();
    }
    Map<ProjectRunnerStep, RunnerKind> parsed = new LinkedHashMap<>();
    for (Map.Entry<String, String> entry : raw.entrySet()) {
      ProjectRunnerStep step = ProjectRunnerStep.fromValue(entry.getKey(), "stepRunnerKinds.step");
      RunnerKind kind = RunnerKind.fromValue(entry.getValue(), "stepRunnerKinds.runnerKind");
      parsed.put(step, kind);
    }
    return parsed;
  }
}
