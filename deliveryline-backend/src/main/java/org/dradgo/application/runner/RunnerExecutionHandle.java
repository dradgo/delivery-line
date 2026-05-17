package org.dradgo.application.runner;

import java.time.OffsetDateTime;
import java.util.Objects;
import org.dradgo.domain.registry.RunnerExecutionStatus;
import org.dradgo.domain.registry.RunnerStage;

public record RunnerExecutionHandle(
    String runnerExecutionId,
    String workflowRunId,
    RunnerStage stage,
    RunnerExecutionStatus status,
    OffsetDateTime timeoutAt) {

  public RunnerExecutionHandle {
    if (runnerExecutionId == null || runnerExecutionId.isBlank()) {
      throw new IllegalArgumentException("runnerExecutionId must not be blank");
    }
    if (workflowRunId == null || workflowRunId.isBlank()) {
      throw new IllegalArgumentException("workflowRunId must not be blank");
    }
    Objects.requireNonNull(stage, "stage");
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(timeoutAt, "timeoutAt");
  }
}
