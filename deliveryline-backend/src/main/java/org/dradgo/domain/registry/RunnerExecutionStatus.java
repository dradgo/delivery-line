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
  ORPHANED("orphaned");

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
