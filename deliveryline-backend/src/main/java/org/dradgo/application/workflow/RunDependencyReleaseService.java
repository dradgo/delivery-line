package org.dradgo.application.workflow;

import java.util.List;
import org.dradgo.application.workflow.WorkflowTransitionService.TransitionActor;
import org.dradgo.application.workflow.spi.RunDependencyPort;
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.WorkflowState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Releases dependency-blocked runs once a prerequisite completes (story 3f-3, AC6/AC7). Invoked
 * from {@link WorkflowTransitionService}'s post-commit hook for committed {@code Completed}
 * transitions, so a release failure can never roll back the completing run's transition.
 *
 * <p>For each direct dependent of the completed run: skip unless it is parked in {@code
 * WaitingForDependencies}; if every one of its prerequisites is now {@code Completed}, transition
 * it to {@code Investigating} and dispatch spec generation. Each dependent is handled independently
 * and best-effort: a {@link RuntimeException} on one dependent is logged and swallowed so the
 * remaining dependents (and the completed prerequisite) are unaffected. A
 * failed/taken-over/reconciled/split/ still-waiting prerequisite simply leaves its dependents
 * blocked (no cascade).
 */
@Service
public class RunDependencyReleaseService {

  private static final Logger log = LoggerFactory.getLogger(RunDependencyReleaseService.class);

  private final RunDependencyPort runDependencyPort;
  private final WorkflowTransitionService workflowTransitionService;
  private final WorkflowOrchestrationService workflowOrchestrationService;
  // Story 3f-3 (AC6) — each dependent is released in its OWN new transaction. This runs from a
  // post-commit hook (the completing run's tx is already done, and its JPA resources are still
  // bound
  // to the thread until afterCompletion), so REQUIRES_NEW suspends those stale resources and binds
  // a
  // fresh persistence context — a plain REQUIRED would throw InvalidDataAccessApiUsageException.
  // Per-dependent (not per-batch) so one dependent's rollback never poisons another's transaction.
  private final TransactionTemplate requiresNewTx;

  public RunDependencyReleaseService(
      RunDependencyPort runDependencyPort,
      WorkflowTransitionService workflowTransitionService,
      WorkflowOrchestrationService workflowOrchestrationService,
      PlatformTransactionManager transactionManager) {
    this.runDependencyPort = runDependencyPort;
    this.workflowTransitionService = workflowTransitionService;
    this.workflowOrchestrationService = workflowOrchestrationService;
    this.requiresNewTx = new TransactionTemplate(transactionManager);
    this.requiresNewTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  /**
   * Release any dependents of {@code completedRunId} whose prerequisites are now all completed.
   * Safe to call repeatedly (idempotent): an already-released dependent is no longer in {@code
   * WaitingForDependencies} and is skipped.
   */
  public void releaseDependentsOf(String completedRunId, String correlationId) {
    // Scan the dependents under the dependency-graph advisory lock (review 3f-3 D2) so the read is
    // serialized against in-flight declarations: an edge committed by declareDependencies is either
    // already visible here, or that declaration runs *after* this scan — in which case it observes
    // the prerequisite already Completed and never parks the dependent. Either way no dependent is
    // stranded. Wrapped in its own short transaction because the xact-scoped lock needs one.
    List<BlockedDependencyView> scanned =
        requiresNewTx.execute(
            status -> {
              runDependencyPort.lockDependencyGraph();
              return runDependencyPort.findDependents(completedRunId);
            });
    List<BlockedDependencyView> dependents = scanned == null ? List.of() : scanned;
    log.info(
        "dependency release resolver entry completedRunId={} dependentCount={} correlationId={}",
        completedRunId,
        dependents.size(),
        correlationId);
    int released = 0;
    for (BlockedDependencyView dependent : dependents) {
      String dependentRunId = dependent.runId();
      if (dependent.state() != WorkflowState.WAITING_FOR_DEPENDENCIES) {
        log.debug(
            "dependency release skip (dependent not waiting) dependentRunId={} state={}",
            dependentRunId,
            dependent.state().value());
        continue;
      }
      try {
        boolean releasedOne =
            Boolean.TRUE.equals(
                requiresNewTx.execute(
                    status -> {
                      // Same advisory lock as declaration: a prerequisite added concurrently to
                      // this dependent cannot slip between the all-completed re-check and the
                      // transition (review 3f-3 D1/D2). The declaration either runs first (this
                      // re-check then sees the new unmet prerequisite and skips) or is blocked
                      // until
                      // this release commits (and is then rejected since the dependent is no longer
                      // pre-execution).
                      runDependencyPort.lockDependencyGraph();
                      // Re-check inside the fresh transaction: a concurrent completion may have
                      // already released this dependent, and the prerequisite set is read live.
                      if (!runDependencyPort.allPrerequisitesCompleted(dependentRunId)) {
                        log.debug(
                            "dependency release skip (prerequisites still unmet) dependentRunId={}",
                            dependentRunId);
                        return false;
                      }
                      workflowTransitionService.transition(
                          dependentRunId,
                          WorkflowState.INVESTIGATING,
                          new TransitionActor("system", ActorType.SYSTEM),
                          "dependencies_satisfied",
                          releaseKey(dependentRunId));
                      workflowOrchestrationService.dispatchSpecGeneration(
                          dependentRunId, correlationId);
                      return true;
                    }));
        if (releasedOne) {
          released++;
          log.info(
              "dependent released and dispatched dependentRunId={} viaCompletedRunId={}",
              dependentRunId,
              completedRunId);
        }
      } catch (RuntimeException error) {
        // Best-effort (AC6): one dependent's failure must not hide the completed transition nor
        // block the other dependents. Swallow with a WARN.
        log.warn(
            "dependency release swallowed an error (completion intact) dependentRunId={} viaCompletedRunId={} cause={}",
            dependentRunId,
            completedRunId,
            error.getClass().getSimpleName());
      }
    }
    log.info(
        "dependency release resolver exit completedRunId={} releasedCount={}",
        completedRunId,
        released);
  }

  private static String releaseKey(String dependentRunId) {
    // Deterministic so repeated completion hooks (e.g. two prerequisites completing) do not append
    // duplicate WaitingForDependencies -> Investigating transitions for the same dependent.
    return "release-deps:" + dependentRunId;
  }
}
