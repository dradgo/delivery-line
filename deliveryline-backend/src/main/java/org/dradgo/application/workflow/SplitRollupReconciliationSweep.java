package org.dradgo.application.workflow;

import java.util.List;
import org.dradgo.application.workflow.spi.WorkflowRunReadPort;
import org.dradgo.application.workflow.spi.WorkflowRunSnapshot;
import org.dradgo.domain.registry.WorkflowState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Story 3f-8 (FR — durability for the 3f-7 completion-rollup hook). Self-healing reconciliation for
 * split parents stranded in non-terminal {@code Split}.
 *
 * <p><strong>The gap this closes.</strong> In 3f-7 the {@code Split -> Completed} rollup is driven
 * <em>only</em> by the {@code afterCommit} hook on a child's {@code Completed} transition, and the
 * <strong>last</strong> child to complete is the sole trigger of the parent's rollup. That hook is
 * best-effort ({@code REQUIRES_NEW}, swallow+log): a transient infrastructure error (advisory-lock
 * timeout, deadlock, optimistic-lock conflict, connection blip) on that single execution is logged
 * and swallowed — and because no further child will ever complete, the hook never re-fires. The
 * parent is left permanently in {@code Split} with its cross-split dependents blocked.
 *
 * <p><strong>What this does.</strong> On each scheduled tick (the {@code @Scheduled} trigger lives
 * in {@code infrastructure.config}, {@code @ConditionalOnProperty}-gated so a disabled sweep
 * registers no bean — AC4) it scans for stranded parents ({@code Split} with <em>all</em> direct
 * children {@code Completed}) via {@link WorkflowRunReadPort#findStrandedSplitParents(int)} and,
 * for each, re-invokes the <em>existing</em> idempotent 3f-7 rollup through {@link
 * RunSplitCompletionRollupService#rollupParent(String, String)}. That drives the parent {@code
 * Split -> Completed} through the same gate (RDEP advisory lock, {@code REQUIRES_NEW}, {@code
 * WorkflowTransitionService.transition}) — so the parent's own {@code Completed} transition
 * re-fires the standard hook chain: grandparent recursion <em>and</em> the 3f-3 dependency-release
 * hook (AC3). The sweep therefore only needs to poke the lowest stranded parent; the rest of the
 * subtree recovers via the inherited hooks (or on the next tick).
 *
 * <p><strong>Safety / idempotency (AC2).</strong> The rollup gate takes the RDEP advisory lock
 * first and uses the deterministic {@code split-rollup:<parentId>} transition key, and the state
 * machine rejects a second {@code -> Completed}; so the sweep never double-transitions, never
 * double-emits {@code viaSplitRollup}, and never strands a run, even running repeatedly or
 * concurrently with a live {@code afterCommit} rollup of the same parent.
 *
 * <p>Framework-trigger-free by design: this is plain application logic invoked by the
 * infrastructure scheduler, keeping the application layer free of Spring scheduling annotations.
 */
@Service
public class SplitRollupReconciliationSweep {

  private static final Logger log = LoggerFactory.getLogger(SplitRollupReconciliationSweep.class);

  private final WorkflowRunReadPort workflowRunReadPort;
  private final RunSplitCompletionRollupService rollupService;
  private final RollupSweepProperties properties;

  public SplitRollupReconciliationSweep(
      WorkflowRunReadPort workflowRunReadPort,
      RunSplitCompletionRollupService rollupService,
      RollupSweepProperties properties) {
    this.workflowRunReadPort = workflowRunReadPort;
    this.rollupService = rollupService;
    this.properties = properties;
  }

  /**
   * Run one reconciliation tick: discover stranded {@code Split} parents (bounded by the configured
   * batch limit) and re-invoke the idempotent 3f-7 rollup for each. Never throws — the underlying
   * {@link RunSplitCompletionRollupService#rollupParent} swallows per-parent errors, and a failed
   * parent simply retries on the next tick.
   *
   * @return a summary of how many stranded parents were found and how many actually rolled up
   */
  public SweepResult sweep() {
    int batchLimit = properties.batchLimit();
    List<WorkflowRunSnapshot> stranded = workflowRunReadPort.findStrandedSplitParents(batchLimit);
    int found = stranded.size();
    int recovered = 0;

    for (WorkflowRunSnapshot parent : stranded) {
      String parentId = parent.publicId();
      // Pass a sweep-scoped correlation token (not null) so the reused 3f-7 gate logs carry a
      // traceable id for exactly the rare transient-strand recoveries this story exists to observe.
      rollupService.rollupParent(parentId, "sweep:" + parentId);
      if (rolledUp(parentId)) {
        recovered++;
        long completedChildren =
            workflowRunReadPort.findByParentRunId(parentId).stream()
                .filter(c -> c.currentState() == WorkflowState.COMPLETED)
                .count();
        // AC5 — the sweep-vs-hook marker, emitted only on a CONFIRMED flip so it never overstates a
        // "recovery": a sweep-driven recovery means the PRIMARY completion hook failed transiently,
        // so a recurring sweep recovery is a visible signal worth a WARN. (The child-count read
        // runs
        // only for actual recoveries — not for every discovered parent or for parents that fail to
        // flip — and `recovered` remains best-effort: a concurrent live hook winning the race also
        // counts here, which is harmless since the parent is genuinely Completed.)
        log.warn(
            "split-rollup SWEEP recovering stranded parent (primary completion hook failed; "
                + "sweep-driven rollup) parentRunId={} completedChildren={}",
            parentId,
            completedChildren);
      } else {
        // Did not flip this tick (lost a race, or a transient error inside the swallowed rollup):
        // left in Split, retried next tick. No silent loss.
        log.warn(
            "split-rollup SWEEP parent still Split after rollup attempt (will retry next tick) "
                + "parentRunId={}",
            parentId);
      }
    }

    if (found == batchLimit) {
      // No silent truncation (AC4/AC5): the batch was full, so more stranded parents may remain.
      log.warn(
          "split-rollup SWEEP hit batch limit — more stranded parents may remain, healing next tick"
              + " batchLimit={} found={} recovered={}",
          batchLimit,
          found,
          recovered);
    }
    log.info(
        "split-rollup SWEEP tick complete found={} recovered={} batchLimit={}",
        found,
        recovered,
        batchLimit);
    return new SweepResult(found, recovered, found == batchLimit);
  }

  private boolean rolledUp(String parentId) {
    return workflowRunReadPort
        .findByPublicId(parentId)
        .map(p -> p.currentState() == WorkflowState.COMPLETED)
        .orElse(false);
  }

  /**
   * Per-tick summary. {@code found} = stranded parents discovered this tick; {@code recovered} =
   * those confirmed rolled up to {@code Completed}; {@code batchLimitHit} = the discovery filled
   * the batch (more may remain for the next tick).
   */
  public record SweepResult(int found, int recovered, boolean batchLimitHit) {}
}
