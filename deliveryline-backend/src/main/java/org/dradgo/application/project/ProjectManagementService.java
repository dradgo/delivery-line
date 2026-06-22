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
import org.dradgo.domain.registry.ProjectStatus;
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
            null);
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
            // Reviewer binding is not yet editable through this surface (3d-2 owns it); preserve.
            existing.reviewerModelKind(),
            existing.reviewerGatingEnabled(),
            // The update surface replaces or clears the nullable per-project runner override.
            runnerKind,
            existing.createdAt(),
            existing.archivedAt());
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
            existing.archivedAt());
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
            existing.archivedAt());
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
}
