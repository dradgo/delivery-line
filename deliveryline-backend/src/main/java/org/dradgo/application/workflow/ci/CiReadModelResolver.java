package org.dradgo.application.workflow.ci;

import java.util.Objects;
import java.util.Optional;
import org.dradgo.application.integration.repohost.RepositoryHostAdapter;
import org.dradgo.application.project.ProjectConnectorResolver;
import org.dradgo.application.project.ProjectRuntimeConfigResolver;
import org.dradgo.application.workflow.spi.CiRunView;
import org.dradgo.application.workflow.spi.CiStatusPort;
import org.dradgo.domain.integration.repohost.RepositoryHostCapabilities;
import org.dradgo.domain.project.Project;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Story 3h-5 (AC3) — resolves the CI read-model fields for a run's detail view: the live {@code
 * ci_status} / {@code ci_head_sha} / {@code ci_fix_loop_count} (raw read via {@link CiStatusPort},
 * never the entity — Decision 6) plus {@code ciChecksEnforced}, the FIRST production reader of
 * {@code RepositoryHostCapabilities.supportsRequiredStatusChecks()}.
 *
 * <p>The capability probe is defensive: an unknown/throwing/absent host resolves {@code
 * ciChecksEnforced=false} (never propagates a failure into the read path). A never-pushed run has
 * null {@code ciStatus}/{@code ciHeadSha} and {@code ciFixLoopCount=0}.
 *
 * <p>Registered as a {@code @Component} (not {@code @Service}) — the {@code
 * APPLICATION_SERVICES_MUST_BE_NAMED_AS_SERVICES} ArchUnit rule requires {@code @Service} beans to
 * end in {@code Service}/{@code Orchestrator}; a {@code *Resolver} uses {@code @Component} (the
 * {@code ProjectConnectorResolver} precedent). The bean registers identically.
 */
@Component
public class CiReadModelResolver {

  private static final Logger log = LoggerFactory.getLogger(CiReadModelResolver.class);

  private final CiStatusPort ciStatusPort;
  private final ProjectRuntimeConfigResolver projectRuntimeConfigResolver;
  private final ProjectConnectorResolver projectConnectorResolver;

  public CiReadModelResolver(
      CiStatusPort ciStatusPort,
      ProjectRuntimeConfigResolver projectRuntimeConfigResolver,
      ProjectConnectorResolver projectConnectorResolver) {
    this.ciStatusPort = Objects.requireNonNull(ciStatusPort, "ciStatusPort");
    this.projectRuntimeConfigResolver =
        Objects.requireNonNull(projectRuntimeConfigResolver, "projectRuntimeConfigResolver");
    this.projectConnectorResolver =
        Objects.requireNonNull(projectConnectorResolver, "projectConnectorResolver");
  }

  /** Resolve the four CI detail read-model fields for {@code workflowRunPublicId}. */
  public CiRunReadModel resolve(String workflowRunPublicId) {
    Optional<CiRunView> view = ciStatusPort.readCiView(workflowRunPublicId);
    String ciStatus = view.map(CiRunView::ciStatus).orElse(null);
    String ciHeadSha = view.map(CiRunView::ciHeadSha).orElse(null);
    int ciFixLoopCount = view.map(CiRunView::ciFixLoopCount).orElse(0);
    boolean ciChecksEnforced = resolveChecksEnforced(workflowRunPublicId);
    return new CiRunReadModel(ciStatus, ciHeadSha, ciFixLoopCount, ciChecksEnforced);
  }

  private boolean resolveChecksEnforced(String workflowRunPublicId) {
    try {
      Project project = projectRuntimeConfigResolver.resolveForRun(workflowRunPublicId);
      RepositoryHostAdapter adapter = projectConnectorResolver.resolveRepositoryHost(project);
      if (adapter == null) {
        return false;
      }
      RepositoryHostCapabilities capabilities = adapter.getCapabilities();
      return capabilities != null && capabilities.supportsRequiredStatusChecks();
    } catch (RuntimeException probeFailure) {
      log.debug(
          "ci checks-enforced probe defaulted to false workflowRunId={} cause={}",
          workflowRunPublicId,
          probeFailure.getClass().getSimpleName());
      return false;
    }
  }

  /** The four CI detail read-model fields (story 3h-5 AC3). */
  public record CiRunReadModel(
      String ciStatus, String ciHeadSha, int ciFixLoopCount, boolean ciChecksEnforced) {

    /** Neutral default when no CI resolver is wired (lean contexts): null/0/false. */
    public static CiRunReadModel empty() {
      return new CiRunReadModel(null, null, 0, false);
    }
  }
}
