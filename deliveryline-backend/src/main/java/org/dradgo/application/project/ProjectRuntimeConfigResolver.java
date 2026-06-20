package org.dradgo.application.project;

import java.util.Objects;
import java.util.Optional;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.integration.repohost.RepositoryRef;
import org.dradgo.domain.project.Project;
import org.dradgo.domain.registry.DomainErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Story 3c-6 — resolves the effective run-time configuration (repository binding, connector kinds,
 * OpenSpec flag) for a run from its {@link Project}, falling back to the reserved {@code default}
 * project when the run carries no {@code project_id} or its binding cannot be resolved. This is the
 * read side that inverts config resolution: the {@code default} project (seeded from the global
 * config by {@link DefaultProjectSeeder}) — not the global {@code @ConfigurationProperties} — is
 * now the source of truth.
 *
 * <p>3c-6 repoints the ONE already-isolated consumer ({@code
 * RepositoryWorkspaceService.resolveExpectedRepositoryRef}); the remaining hot-path consumers
 * (runner bundle, {@code DockerRunnerAdapter} dispatch incl. the OpenSpec env, the queue, Linear
 * completion sync) are repointed in 3c-7, which threads {@code project_id} through intake/dispatch
 * so non-default projects take effect end-to-end.
 *
 * <p>Boundary: {@code application.project} depending only on {@code domain.*} + the {@link
 * ProjectStore} application port — never {@code adapters..}/{@code infrastructure..} (the {@code
 * application-cannot-import-adapters} rule). {@code @Component} (not {@code @Service}) keeps the
 * {@code *Resolver} name under the {@code APPLICATION_SERVICES_MUST_BE_NAMED_AS_SERVICES} rule,
 * exactly like {@link ProjectConnectorResolver}.
 */
@Component
public class ProjectRuntimeConfigResolver {

  private static final Logger log = LoggerFactory.getLogger(ProjectRuntimeConfigResolver.class);

  private final ProjectStore projectStore;

  public ProjectRuntimeConfigResolver(ProjectStore projectStore) {
    this.projectStore = Objects.requireNonNull(projectStore, "projectStore");
  }

  /**
   * The effective {@link Project} for a run: the project bound by the run's {@code project_id} when
   * present and resolvable, else the {@code default} project. Throws {@link
   * DomainErrorCode#PROJECT_NOT_FOUND} only when even the default project cannot be resolved (a
   * pre-seed / misconfigured-DB invariant breach).
   */
  public Project resolveForRun(String workflowRunId) {
    Optional<String> boundProjectId = projectStore.findProjectIdForRun(workflowRunId);
    if (boundProjectId.isPresent()) {
      Optional<Project> bound = projectStore.findByPublicId(boundProjectId.get());
      if (bound.isPresent()) {
        log.debug(
            "resolveForRun workflowRunId={} projectId={} source=run_project",
            workflowRunId,
            bound.get().publicId());
        return bound.get();
      }
      log.warn(
          "resolveForRun workflowRunId={} references missing projectId={}; falling back to default",
          workflowRunId,
          boundProjectId.get());
    }
    Project defaultProject =
        projectStore
            .findBySlug(DefaultProjectSeeder.DEFAULT_PROJECT_SLUG)
            .orElseThrow(
                () ->
                    new DomainException(
                        DomainErrorCode.PROJECT_NOT_FOUND,
                        "no default project resolved for run " + workflowRunId));
    log.debug(
        "resolveForRun workflowRunId={} projectId={} source=default_fallback",
        workflowRunId,
        defaultProject.publicId());
    return defaultProject;
  }

  /**
   * The effective repository ref for a run — the resolved project's {@code repositoryUrl}
   * normalized to {@code owner/repo} (the shared {@link RepositoryRef#normalizeRepositoryUrl},
   * identical to the global path), or empty when the project carries no repo binding. The repoint
   * target of {@code RepositoryWorkspaceService.resolveExpectedRepositoryRef}.
   */
  public Optional<String> resolveRepositoryRef(String workflowRunId) {
    Project project = resolveForRun(workflowRunId);
    return Optional.ofNullable(RepositoryRef.normalizeRepositoryUrl(project.repositoryUrl()));
  }

  /**
   * The effective OpenSpec opt-in for a run — the resolved project's {@code openspecEnabled}
   * (seeded from {@code deliveryline.runner.openspec.enabled}). 3c-6 exposes it; the {@code
   * DockerRunnerAdapter} dispatch consumer is repointed in 3c-7.
   */
  public boolean resolveOpenSpecEnabled(String workflowRunId) {
    return resolveForRun(workflowRunId).openspecEnabled();
  }
}
