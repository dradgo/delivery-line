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
  // Story 3h-0 — the child-driven rollup (rollupParentOf, the 3f-7 hook path) is retrofitted onto
  // the shared replay-safe afterCommit helper's Layer B (runInNewTransaction), which owns the
  // REQUIRES_NEW template + best-effort swallow. The tx-scoped RDEP advisory lock is still taken as
  // the FIRST statement inside doRollupForParent (the helper stays domain-agnostic).
  private final AfterCommitSideEffectRunner afterCommitSideEffectRunner;
  // Story 3f-8 — the sweep-driven parent entry (rollupParent) keeps its OWN REQUIRES_NEW template
  // so
  // its distinct "(sweep)" swallow signal is preserved (that retrofit is optional/forward per 3h-0;
  // recorded in the Dev Agent Record). Same REQUIRES_NEW rationale as the child-driven path:
  // invoked
  // from the reconciliation sweep, it must suspend any stale resources and bind a fresh context.
  private final TransactionTemplate requiresNewTx;

  public RunSplitCompletionRollupService(
      WorkflowRunReadPort workflowRunReadPort,
      WorkflowTransitionService workflowTransitionService,
      RunDependencyPort runDependencyPort,
      AfterCommitSideEffectRunner afterCommitSideEffectRunner,
      PlatformTransactionManager transactionManager) {
    this.workflowRunReadPort = workflowRunReadPort;
    this.workflowTransitionService = workflowTransitionService;
    this.runDependencyPort = runDependencyPort;
    this.afterCommitSideEffectRunner = afterCommitSideEffectRunner;
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
    // Story 3h-0 — Layer B of the shared helper owns the REQUIRES_NEW template + best-effort
    // swallow-and-WARN. doRollup keeps resolving the parent and taking the RDEP advisory lock
    // inside
    // doRollupForParent exactly as before; the helper never references the lock.
    afterCommitSideEffectRunner.runInNewTransaction(
        "split-rollup", completedRunId, () -> doRollup(completedRunId, correlationId));
  }

  /**
   * Story 3f-8 (AC1/AC2/AC3) — <strong>parent-targeted</strong> sibling of {@link
   * #rollupParentOf(String, String)} for the reconciliation sweep, which already holds the parent
   * id. Runs the <em>identical</em> {@link #doRollupForParent} gate keyed on {@code parentId}
   * directly (no fake-child synthesis), in the same {@code REQUIRES_NEW} transaction with the same
   * {@code lockDependencyGraph()}-first discipline and the same swallow+log. Safe to call
   * repeatedly and alongside the live child-driven hook for the same parent: the advisory lock +
   * the deterministic {@code split-rollup:<parentId>} transition key + the state machine rejecting
   * a second {@code -> Completed} make a double-transition / double-emit impossible. A successful
   * rollup re-fires the standard 3f-7 hook chain (grandparent recursion + 3f-3 dependency release),
   * so the sweep need only poke the lowest stranded parent.
   */
  public void rollupParent(String parentId, String correlationId) {
    try {
      requiresNewTx.executeWithoutResult(status -> doRollupForParent(parentId, correlationId));
    } catch (RuntimeException error) {
      // Best-effort (AC2): mirror the child-driven swallow — one stuck parent must not abort the
      // sweep's remaining parents (the sweep loop continues; the next tick retries this one).
      log.warn(
          "split-rollup swallowed an error (sweep) parentRunId={} cause={}",
          parentId,
          error.getClass().getSimpleName());
    }
  }

  private void doRollup(String completedRunId, String correlationId) {
    Optional<WorkflowRunSnapshot> completed = workflowRunReadPort.findByPublicId(completedRunId);
    if (completed.isEmpty()) {
      return;
    }
    String parentId = completed.get().parentRunId();
    if (parentId == null) {
      // Parity (AC7): a top-level run has no parent — nothing to roll up. One cheap lineage read.
      // (The child's parentRunId is immutable, so this resolution read needs no lock; the shared
      // gate below takes the advisory lock as its first statement before the parent state-read.)
      return;
    }
    doRollupForParent(parentId, correlationId);
  }

  // Story 3f-8 — the ONE shared rollup gate, keyed on the PARENT id. Both the child-driven
  // (rollupParentOf -> doRollup resolves parentId) and the sweep-driven (rollupParent) entries
  // delegate here so there is no forked gate logic (AC1). Same REQUIRES_NEW tx (the caller's
  // TransactionTemplate), same lock-first ordering, same transition.
  private void doRollupForParent(String parentId, String correlationId) {
    // Take the run-dependency advisory lock FIRST so two near-simultaneous rollup attempts for the
    // same parent (sibling completions and/or a sweep racing the live hook) serialize: the first
    // rolls the parent up, the second re-reads parent == Completed and returns (review 3f-3 D1/D2 —
    // the 3f-7 rollup MUST take the RDEP lock). Tx-scoped: released on commit.
    runDependencyPort.lockDependencyGraph();

    Optional<WorkflowRunSnapshot> parentLookup = workflowRunReadPort.findByPublicId(parentId);
    if (parentLookup.isEmpty()) {
      return;
    }
    WorkflowRunSnapshot parent = parentLookup.get();
    if (parent.currentState() != WorkflowState.SPLIT) {
      // Idempotent: an already-rolled-up parent (Completed) or a parent not in Split is a no-op.
      log.debug(
          "split-rollup no-op (parent not Split) parentRunId={} parentState={} correlationId={}",
          parentId,
          parent.currentState() == null ? "null" : parent.currentState().value(),
          correlationId);
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
          "split-rollup parent NOT ready parentRunId={} completedChildren={} of {} correlationId={}",
          parentId,
          completedCount,
          total,
          correlationId);
      return;
    }

    // All direct children Completed -> roll the parent up THROUGH the transition service (NOT a raw
    // state write) so the parent's own Completed transition re-fires the rollup hook (recursion to
    // the grandparent) AND the unchanged 3f-3 dependency-release hook (AC3 cross-split unblock).
    log.info(
        "split-rollup gate firing parentRunId={} children={} correlationId={}",
        parentId,
        total,
        correlationId);
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
