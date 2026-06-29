package org.dradgo.application.workflow;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.dradgo.application.workflow.WorkflowTransitionService.TransitionActor;
import org.dradgo.application.workflow.spi.RunDependencyPort;
import org.dradgo.application.workflow.spi.WorkflowRunReadPort;
import org.dradgo.application.workflow.spi.WorkflowRunSnapshot;
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.WorkflowEventDetailKeys;
import org.dradgo.domain.registry.WorkflowState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Drives the {@code Split -> Completed} rollup (story 3f-7, AC2/AC3/AC4/AC7). Invoked from {@link
 * WorkflowTransitionService}'s post-commit hook for committed {@code Completed} transitions, beside
 * the 3f-3 dependency-release hook, so a rollup failure can never roll back the completing run's
 * transition.
 *
 * <p>When a run reaches {@code Completed} it looks up that run's {@code parentRunId}; if the parent
 * is parked in non-terminal {@code Split} <strong>and every direct child of the parent is {@code
 * Completed}</strong>, it transitions the parent {@code Split -> Completed} carrying the {@code
 * viaSplitRollup} detail flag. A split child satisfies its parent only via its own rolled-up {@code
 * Completed} (a split child reaches {@code Completed} only through <em>its</em> rollup), so "all
 * direct children {@code Completed}" is equivalent to "the whole subtree is done" — no transitive
 * grandchild walk is needed.
 *
 * <p><strong>Recursion is the hook chain, not an explicit loop.</strong> The parent's {@code Split
 * -> Completed} transition is itself a {@code Completed} transition, so it registers a fresh rollup
 * afterCommit hook that re-invokes {@code rollupParentOf(parent)} for the grandparent, terminating
 * at the lineage root ({@code parentRunId == null}) or the first non-{@code Split} ancestor. That
 * same parent {@code Completed} transition also fires the unchanged 3f-3 dependency-release hook,
 * so a dependent of a run that was later split is released the moment that subtree finishes (AC3)
 * with no resolver change. This is why the rollup must drive completion through {@link
 * WorkflowTransitionService#transition} rather than a raw state write.
 *
 * <p>Idempotent, best-effort: it takes the run-dependency advisory lock first (serializing
 * near-simultaneous sibling completions — the first rolls the parent up, the second re-reads {@code
 * Completed} and returns) and swallows + logs any {@link RuntimeException} so one stuck ancestor
 * never strands the completing run's callback (the 3f-3 release-resolver discipline). A failed
 * descendant simply leaves the parent in {@code Split} (the all-children-{@code Completed} gate is
 * not met) — no cascade.
 */
@Service
public class RunSplitCompletionRollupService {

  private static final Logger log = LoggerFactory.getLogger(RunSplitCompletionRollupService.class);

  private final WorkflowRunReadPort workflowRunReadPort;
  private final WorkflowTransitionService workflowTransitionService;
  private final RunDependencyPort runDependencyPort;
  // Story 3f-7 (AC2) — the rollup runs in its OWN new transaction. It is invoked from a post-commit
  // hook (the completing run's tx is already done, its JPA resources still bound to the thread
  // until
  // afterCompletion), so REQUIRES_NEW suspends those stale resources and binds a fresh persistence
  // context — a plain REQUIRED would throw InvalidDataAccessApiUsageException (the exact 3f-3 bug).
  // WorkflowTransitionService.transition() uses REQUIRED, so it JOINS this tx: the tx-scoped
  // advisory
  // lock taken as the first statement covers both the children-check and the parent transition
  // atomically, serializing concurrent sibling completions.
  private final TransactionTemplate requiresNewTx;

  public RunSplitCompletionRollupService(
      WorkflowRunReadPort workflowRunReadPort,
      WorkflowTransitionService workflowTransitionService,
      RunDependencyPort runDependencyPort,
      PlatformTransactionManager transactionManager) {
    this.workflowRunReadPort = workflowRunReadPort;
    this.workflowTransitionService = workflowTransitionService;
    this.runDependencyPort = runDependencyPort;
    this.requiresNewTx = new TransactionTemplate(transactionManager);
    this.requiresNewTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  /**
   * Roll the parent of {@code completedRunId} up to {@code Completed} when it is in {@code Split}
   * and all of its direct children are {@code Completed}. Safe to call repeatedly (idempotent):
   * once the parent has rolled up it is no longer {@code Split} and the call is a no-op. Never
   * throws to the caller — any {@link RuntimeException} is logged and swallowed (the completing
   * run's post-commit callback must stay intact).
   */
  public void rollupParentOf(String completedRunId, String correlationId) {
    try {
      requiresNewTx.executeWithoutResult(status -> doRollup(completedRunId, correlationId));
    } catch (RuntimeException error) {
      // Best-effort (AC2): one stuck ancestor must not hide the completed transition. Swallow WARN.
      log.warn(
          "split-rollup swallowed an error (completion intact) completedRunId={} cause={}",
          completedRunId,
          error.getClass().getSimpleName());
    }
  }

  private void doRollup(String completedRunId, String correlationId) {
    // Take the run-dependency advisory lock FIRST so two siblings completing near-simultaneously
    // serialize: the first rolls the parent up, the second re-reads parent == Completed and returns
    // (review 3f-3 D1/D2 — 3f-7 rollup MUST take the RDEP lock). Tx-scoped: released on commit.
    runDependencyPort.lockDependencyGraph();

    Optional<WorkflowRunSnapshot> completed = workflowRunReadPort.findByPublicId(completedRunId);
    if (completed.isEmpty()) {
      return;
    }
    String parentId = completed.get().parentRunId();
    if (parentId == null) {
      // Parity (AC7): a top-level run has no parent — nothing to roll up. One cheap lineage read.
      return;
    }
    Optional<WorkflowRunSnapshot> parentLookup = workflowRunReadPort.findByPublicId(parentId);
    if (parentLookup.isEmpty()) {
      return;
    }
    WorkflowRunSnapshot parent = parentLookup.get();
    if (parent.currentState() != WorkflowState.SPLIT) {
      // Idempotent: an already-rolled-up parent (Completed) or a parent not in Split is a no-op.
      log.debug(
          "split-rollup no-op (parent not Split) parentRunId={} parentState={} completedRunId={}",
          parentId,
          parent.currentState() == null ? "null" : parent.currentState().value(),
          completedRunId);
      return;
    }

    List<WorkflowRunSnapshot> children = workflowRunReadPort.findByParentRunId(parentId);
    int total = children.size();
    long completedCount =
        children.stream().filter(c -> c.currentState() == WorkflowState.COMPLETED).count();
    if (total == 0 || completedCount != total) {
      // Rollup fires ONLY when every direct child reached COMPLETED — by design (review 2026-06-29,
      // Decision 3): a child in ANY non-Completed state (failed/in-flight AND terminal-but-not-
      // Completed, e.g. cancelled/taken-over) stalls the rollup. The parent stays in non-terminal
      // Split, its dependents stay blocked, and there is NO cascade. Resolving the laggard to
      // COMPLETED (retry/takeover) re-fires this hook and resumes the rollup.
      log.info(
          "split-rollup parent NOT ready parentRunId={} completedChildren={} of {} completedRunId={}",
          parentId,
          completedCount,
          total,
          completedRunId);
      return;
    }

    // All direct children Completed -> roll the parent up THROUGH the transition service (NOT a raw
    // state write) so the parent's own Completed transition re-fires the rollup hook (recursion to
    // the grandparent) AND the unchanged 3f-3 dependency-release hook (AC3 cross-split unblock).
    log.info(
        "split-rollup hook firing parentRunId={} children={} completedRunId={}",
        parentId,
        total,
        completedRunId);
    workflowTransitionService.transition(
        parentId,
        WorkflowState.COMPLETED,
        new TransitionActor("system", ActorType.SYSTEM),
        "split_rollup",
        rollupKey(parentId),
        Map.of(WorkflowEventDetailKeys.VIA_SPLIT_ROLLUP, true));
    log.info(
        "split-rollup parent rolled up Split->Completed parentRunId={} children={}",
        parentId,
        total);
  }

  private static String rollupKey(String parentId) {
    // Deterministic so a replayed rollup transition for the same parent dedupes rather than
    // appending a duplicate Split -> Completed event. The non-Split re-check above is the primary
    // idempotency guard; this is the transition-level backstop.
    return "split-rollup:" + parentId;
  }
}
