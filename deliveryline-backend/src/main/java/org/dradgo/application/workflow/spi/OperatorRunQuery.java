package org.dradgo.application.workflow.spi;

import java.time.Duration;

/**
 * Story 4.1 (AC2/AC6) — the resolved predicate parameters backing {@link OperatorRunReadPort}.
 * Built by {@code WorkflowInspectionService.getOperatorRunSummary} from the raw CLI filter: the
 * five {@code select*} booleans encode which operator-state predicates are active (the UNION of the
 * selected tokens — story 4.1 Reconciliation 5), {@code stallWindow} is the "no recent activity"
 * threshold for the {@code stalled} predicate, and {@code sinceWindow} (nullable) is the optional
 * recent-activity time window.
 *
 * <p><b>Windows, not cutoffs.</b> The story's shape names {@code sinceCutoff}/{@code stallCutoff}
 * as absolute {@code OffsetDateTime}s, but this port carries {@link Duration} windows and the
 * persistence adapter computes the cutoff server-side ({@code now() - make_interval(...)}) exactly
 * like {@code RunnerExecutionRecordPort.loadQueueCounts(Duration, ...)}. This keeps every time
 * comparison clock-drift-free at the database (story 4.1 Reconciliation 4) and needs no JVM {@code
 * Clock} dependency on the read service.
 *
 * @param selectFailed match runs whose {@code current_state = 'Failed'}
 * @param selectStalled match active runs (Investigating/Executing/WaitingForManualExecution) with
 *     no transition inside {@code stallWindow}
 * @param selectOrphaned match {@code Failed} runs whose latest Failed transition carried {@code
 *     failure_category = 'orphan'}
 * @param selectTakenover match runs whose {@code current_state = 'TakenOver'}
 * @param selectOverridden match runs whose latest event set {@code intervention_marker = true} and
 *     whose current state is non-terminal (provisional binding OQ-1)
 * @param sinceWindow optional recent-activity window (runs whose last transition is within it);
 *     {@code null} disables the time filter
 * @param stallWindow the {@code stalled} inactivity threshold (never {@code null})
 * @param includeArchived when {@code false}, soft-hidden runs ({@code archived_at IS NOT NULL}) are
 *     excluded; the operator fleet view always passes {@code false}
 */
public record OperatorRunQuery(
    boolean selectFailed,
    boolean selectStalled,
    boolean selectOrphaned,
    boolean selectTakenover,
    boolean selectOverridden,
    Duration sinceWindow,
    Duration stallWindow,
    boolean includeArchived) {

  /** True when no operator-state predicate is active (the port then matches nothing). */
  public boolean matchesNothing() {
    return !selectFailed
        && !selectStalled
        && !selectOrphaned
        && !selectTakenover
        && !selectOverridden;
  }

  /**
   * True when the {@code failed} or {@code orphaned} predicate is active (drives
   * byFailureCategory).
   */
  public boolean includesFailureCategoryHistogram() {
    return selectFailed || selectOrphaned;
  }
}
