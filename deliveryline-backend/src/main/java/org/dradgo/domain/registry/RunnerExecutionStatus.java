package org.dradgo.domain.registry;

import java.util.Map;

public enum RunnerExecutionStatus implements RegistryValue {
  PENDING("pending"),
  RUNNING("running"),
  // Story 3.17a — pre-dispatch state for a row sitting in the RunnerExecutionQueue. dequeue()
  // (FOR UPDATE SKIP LOCKED) leases it to a worker and flips it to RUNNING. Built dormant: no
  // production code enqueues yet (story 3.17b activates the queue).
  QUEUED("queued"),
  COMPLETED("completed"),
  FAILED("failed"),
  TIMED_OUT("timed_out"),
  ORPHANED("orphaned"),
  // Story 3.22 (AC5) — developer-takeover terminal status. A row in this status is the
  // authoritative
  // "stop dispatch" signal: invisible to the worker pool's dequeueNext (leases status='queued')
  // AND the broker's ACTIVE_STATUSES ({pending, running}), so neither dispatch path can pick it up.
  CANCELLED_FOR_TAKEOVER("cancelled_for_takeover"),
  // Story 3d-3 (AC5, ADR 0024 D5/FR53) — NON-TERMINAL parked status for a step dispatched under the
  // `manual` runner kind. The row records the manual step (stage + bundle version) so it is
  // first-class in inspection/observability/lineage, but carries NO container/lease/worker columns
  // and NO completed_at. It must NOT join ck_runner_executions_completed_correlation (exactly as
  // `queued` is excluded). The kind (`manual`) is carried by the manual.executionRequested event
  // detail, not a column (R3). On 3d-4 submission the row finalizes to `completed`.
  AWAITING_MANUAL("awaiting_manual");

  private static final Map<String, RunnerExecutionStatus> LOOKUP = RegistryParsers.index(values());

  private final String value;

  RunnerExecutionStatus(String value) {
    this.value = value;
  }

  @Override
  public String value() {
    return value;
  }

  static RunnerExecutionStatus fromValue(String rawValue) {
    return fromValue(rawValue, null);
  }

  public static RunnerExecutionStatus fromValue(String rawValue, String field) {
    return RegistryParsers.parse("RunnerExecutionStatus", rawValue, field, LOOKUP);
  }
}
