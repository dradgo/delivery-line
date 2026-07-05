package org.dradgo.application.workflow.spi;

import java.util.List;

/**
 * Story 4.1 (AC2/AC6) — the read seam backing {@code deliveryline operator status}. A NEW dedicated
 * port (not a bend of {@link WorkflowRunReadPort#listRuns}, which filters a SINGLE {@code
 * WorkflowState} ordered by {@code created_at}): the operator fleet row cannot be served from
 * {@code workflow_runs} alone — it is a JOIN/derivation across {@code workflow_events} (latest /
 * latest-Failed / earliest event) and typed {@code integration_links} (story 4.1 Reconciliation 3).
 *
 * <p>Mirrors the native-aggregate style of {@code RunnerExecutionRecordPort.loadQueueCounts}: two
 * read methods over the same predicate — {@link #loadOperatorRunAggregate} computes the histograms
 * + total + oldest-entry over the FULL matching set, and {@link #listOperatorRuns} returns the
 * {@code lastTransitionAt DESC} page capped at {@code limit}.
 *
 * <p>Default no-op implementations so the lean unit-test constructors of {@code
 * WorkflowInspectionService} (which never call {@code getOperatorRunSummary}) need not implement
 * this port; production Spring wires the {@code adapters.persistence} implementation.
 */
public interface OperatorRunReadPort {

  /**
   * Fleet aggregate over the full matching set (independent of any row limit).
   *
   * @param query resolved predicate parameters
   * @return the histograms + total + oldest-entry; {@link OperatorRunAggregate#empty()} when
   *     nothing matches
   */
  default OperatorRunAggregate loadOperatorRunAggregate(OperatorRunQuery query) {
    return OperatorRunAggregate.empty();
  }

  /**
   * The matching runs ordered by {@code lastTransitionAt} descending (nulls last, run-id tiebreak),
   * capped at {@code limit}.
   *
   * @param query resolved predicate parameters
   * @param limit maximum rows to return (the service clamps to {@code [1, 500]} before calling)
   * @return fleet rows (possibly empty, never {@code null})
   */
  default List<OperatorRunRowSnapshot> listOperatorRuns(OperatorRunQuery query, int limit) {
    return List.of();
  }
}
