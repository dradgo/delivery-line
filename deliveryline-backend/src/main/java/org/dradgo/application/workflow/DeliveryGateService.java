package org.dradgo.application.workflow;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.dradgo.application.observability.MdcKeys;
import org.dradgo.application.project.ProjectRuntimeConfigResolver;
import org.dradgo.application.runner.RunnerExecutionService;
import org.dradgo.application.runner.spi.RunnerWorkspaceStore;
import org.dradgo.application.workflow.WorkflowTransitionService.TransitionActor;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.PushMode;
import org.dradgo.domain.registry.WorkflowState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Story 3h-4 (AC2/AC4, FR78) — the unified pre-review DELIVERY gate. It is the structural sibling
 * of {@code LintStageService}'s gate half, but with NO command execution: it runs no linter/build,
 * it only resolves the run's {@link PushMode} and decides <em>push-vs-park</em> for the pr-output
 * delivery tail.
 *
 * <ul>
 *   <li><b>{@code AUTO}</b> (or no workspace/repoRef to deliver) → pass-through: {@link
 *       #tryGateBehindDelivery} returns {@code false} and the caller runs the inline delivery
 *       ({@code captureAndPush} fires exactly where it did pre-3h — byte-identical, AC6). The gate
 *       never parks an {@code auto} run.
 *   <li><b>{@code MANUAL}/{@code APPROVE}</b> → finalize the producing PR_OUTPUT execution (so it
 *       is never timeout-reaped during the operator-approval wait) and transition {@code EXECUTING
 *       -> WaitingForDelivery}, taking ownership of the tail (returns {@code true}). The push
 *       (approve) or the out-of-band delivery record (manual) happens later at {@code
 *       approve_delivery} via {@code DeliveryApprovalService}.
 * </ul>
 *
 * <p>The gate keeps git ownership on the backend ({@code GitCommandPort} /{@code
 * RepositoryWorkspaceService}); only the <em>trigger point</em> is now gate-controlled. It runs
 * synchronously within whatever transaction the caller's tail is invoked in (the poller tx for an
 * inline delivery, or the lint gate's {@code REQUIRES_NEW} advance tx), so it needs no afterCommit
 * helper of its own — the state mutation stays in the caller's tx (keeping {@code
 * only_workflow_transition_service_may_mutate_workflow_state} intact).
 */
@Service
public class DeliveryGateService {

  private static final Logger log = LoggerFactory.getLogger(DeliveryGateService.class);

  private final ProjectRuntimeConfigResolver projectRuntimeConfigResolver;
  private final RunnerWorkspaceStore workspaceStore;
  private final RunnerExecutionService executionService;
  private final WorkflowTransitionService transitionService;

  public DeliveryGateService(
      ProjectRuntimeConfigResolver projectRuntimeConfigResolver,
      RunnerWorkspaceStore workspaceStore,
      RunnerExecutionService executionService,
      WorkflowTransitionService transitionService) {
    this.projectRuntimeConfigResolver =
        Objects.requireNonNull(projectRuntimeConfigResolver, "projectRuntimeConfigResolver");
    this.workspaceStore = Objects.requireNonNull(workspaceStore, "workspaceStore");
    this.executionService = Objects.requireNonNull(executionService, "executionService");
    this.transitionService = Objects.requireNonNull(transitionService, "transitionService");
  }

  /**
   * AC2/AC4 — if the delivery gate parks this PR_OUTPUT success (non-{@code auto} push mode with a
   * deliverable workspace), finalize the producing execution + transition {@code EXECUTING ->
   * WaitingForDelivery} and return {@code true}. Returns {@code false} (pass-through ⇒ the caller
   * runs {@code deliverInline}) when the push mode is {@code AUTO} or the run has no materialized
   * workspace/repoRef to deliver.
   *
   * @param deliverInline the inline delivery step (unused by the gate itself — the caller runs it
   *     when this returns {@code false}); accepted for symmetry with the build/lint gate seams and
   *     future extension. The gate never invokes it (it only decides push-vs-park).
   */
  public boolean tryGateBehindDelivery(
      String workflowRunId,
      String prOutputRunnerExecutionId,
      String correlationId,
      Runnable deliverInline) {
    String priorRun = MdcKeys.beginScope(MdcKeys.WORKFLOW_RUN_ID, workflowRunId);
    String priorRex = MdcKeys.beginScope(MdcKeys.RUNNER_EXECUTION_ID, prOutputRunnerExecutionId);
    try {
      PushMode pushMode = projectRuntimeConfigResolver.resolvePushMode(workflowRunId);
      if (pushMode == PushMode.AUTO) {
        log.info(
            "delivery gate pushMode={} workflowRunId={} runnerExecutionId={} correlationId={} "
                + "-> inline (pass-through)",
            pushMode.value(),
            workflowRunId,
            prOutputRunnerExecutionId,
            correlationId);
        return false;
      }
      Optional<Path> repoDir = workspaceStore.resolveRepositoryDir(prOutputRunnerExecutionId);
      if (repoDir.isEmpty()) {
        // Nothing to deliver (no repo-backed workspace) — pass through so deliverInline's
        // captureAndPush no-ops (no_repo_workspace), never parking a run that can't be pushed.
        log.info(
            "delivery gate skipped workflowRunId={} runnerExecutionId={} pushMode={} "
                + "reason=no_repo_workspace -> inline (pass-through)",
            workflowRunId,
            prOutputRunnerExecutionId,
            pushMode.value());
        return false;
      }

      // Finalize the producing PR_OUTPUT execution FIRST — it succeeded (only the push is gated)
      // and
      // is still `running` (its completion lives in the delivery tail that is now deferred). A
      // dangling running row would be timeout-reaped as RUNNER_TIMEOUT during the (possibly long)
      // operator wait and would make the later approve_delivery resume a no-op. Idempotent (mirrors
      // the lint gate's park half).
      finalizeProducingExecution(prOutputRunnerExecutionId);
      transitionService.transition(
          workflowRunId,
          WorkflowState.WAITING_FOR_DELIVERY,
          new TransitionActor("system", ActorType.SYSTEM),
          "delivery gate: non-auto push mode requires operator approval",
          "delivery-gate:" + workflowRunId,
          Map.of("runnerExecutionId", prOutputRunnerExecutionId, "pushMode", pushMode.value()));
      log.info(
          "delivery gate pushMode={} workflowRunId={} runnerExecutionId={} correlationId={} "
              + "-> parked at WaitingForDelivery",
          pushMode.value(),
          workflowRunId,
          prOutputRunnerExecutionId,
          correlationId);
      return true;
    } finally {
      MdcKeys.endScope(MdcKeys.RUNNER_EXECUTION_ID, priorRex);
      MdcKeys.endScope(MdcKeys.WORKFLOW_RUN_ID, priorRun);
    }
  }

  /**
   * Finalize the producing PR_OUTPUT execution as {@code completed}. Idempotent — an
   * already-terminal row (replay / concurrent finalize) is a no-op. Mirrors {@code
   * LintStageService.finalizeProducingExecution}.
   */
  private void finalizeProducingExecution(String prOutputRunnerExecutionId) {
    try {
      executionService.recordCompleted(prOutputRunnerExecutionId);
    } catch (DomainException alreadyTerminal) {
      if (alreadyTerminal.errorCode() != DomainErrorCode.ILLEGAL_TRANSITION) {
        throw alreadyTerminal;
      }
      log.info(
          "delivery gate producing execution already terminal runnerExecutionId={}",
          prOutputRunnerExecutionId);
    }
  }
}
