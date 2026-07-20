package org.dradgo.application.workflow.spi;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;

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
 * @param runnerKinds story 4.2 (AC3) — the runner-kind wire strings ({@code codex}/{@code
 *     claude}/{@code manual}) to filter on, sourced from {@code projects.runner_kind} (the run's
 *     project-level override); empty disables the filter. Feeds both the row query and the
 *     aggregate so the histograms describe the filtered fleet.
 * @param failureCategories story 4.2 (AC3) — the failure-category wire strings to filter on
 *     (matched against the run's derived latest-Failed {@code failure_category}); empty disables
 *     the filter. Feeds both the row query and the aggregate.
 * @param cursorLastTransitionAt story 4.2 (AC5) — the decoded keyset cursor's {@code
 *     last_transition_at}, or {@code null} when the cursor row sat in the {@code nulls last} tail
 *     (or no cursor). Only the row query consults the cursor; the aggregate is cursor-independent.
 * @param cursorRunId story 4.2 (AC5) — the decoded keyset cursor's {@code run_id} tiebreaker;
 *     {@code null} when no cursor is active. Presence of a non-blank value ⟺ paging is active.
 */
public record OperatorRunQuery(
    boolean selectFailed,
    boolean selectStalled,
    boolean selectOrphaned,
    boolean selectTakenover,
    boolean selectOverridden,
    Duration sinceWindow,
    Duration stallWindow,
    boolean includeArchived,
    List<String> runnerKinds,
    List<String> failureCategories,
    OffsetDateTime cursorLastTransitionAt,
    String cursorRunId) {

  public OperatorRunQuery {
    runnerKinds = runnerKinds == null ? List.of() : List.copyOf(runnerKinds);
    failureCategories = failureCategories == null ? List.of() : List.copyOf(failureCategories);
  }

  /**
   * Back-compatible 8-arg constructor (story 4.1 shape) — no runner-kind / failure-category filter,
   * no cursor. Keeps the ~existing unit callers untouched while story 4.2 threads the new fields at
   * the tail.
   */
  public OperatorRunQuery(
      boolean selectFailed,
      boolean selectStalled,
      boolean selectOrphaned,
      boolean selectTakenover,
      boolean selectOverridden,
      Duration sinceWindow,
      Duration stallWindow,
      boolean includeArchived) {
    this(
        selectFailed,
        selectStalled,
        selectOrphaned,
        selectTakenover,
        selectOverridden,
        sinceWindow,
        stallWindow,
        includeArchived,
        List.of(),
        List.of(),
        null,
        null);
  }

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

  /** Story 4.2 (AC3) — true when a runner-kind filter is active. */
  public boolean hasRunnerKindFilter() {
    return !runnerKinds.isEmpty();
  }

  /** Story 4.2 (AC3) — true when a failure-category filter is active. */
  public boolean hasFailureCategoryFilter() {
    return !failureCategories.isEmpty();
  }

  /** Story 4.2 (AC5) — true when a keyset cursor is active (paging into a later page). */
  public boolean hasCursor() {
    return cursorRunId != null && !cursorRunId.isBlank();
  }
}
