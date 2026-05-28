package org.dradgo.application.runner;

import java.nio.file.Path;
import java.util.Objects;
import org.dradgo.domain.registry.DataClassification;
import org.dradgo.domain.registry.RunnerKind;
import org.dradgo.domain.registry.RunnerStage;

/**
 * Application-owned dispatch envelope handed to {@link
 * org.dradgo.application.runner.spi.RunnerAdapter#dispatch(RunnerDispatchRequest)}.
 *
 * <p>Story 3.1: {@code runnerKind} is added as a new required field. The broker resolves the kind
 * from {@code RunnerProperties.docker().defaultKind()} and threads it through; the adapter never
 * reads kind information from the (untrusted) context bundle (trap T3).
 */
public record RunnerDispatchRequest(
    String runnerExecutionId,
    String workflowRunId,
    RunnerStage stage,
    RunnerKind runnerKind,
    Path contextBundlePath,
    ExecutionConstraints executionConstraints,
    DataClassification classification) {

  public RunnerDispatchRequest {
    if (runnerExecutionId == null || runnerExecutionId.isBlank()) {
      throw new IllegalArgumentException("runnerExecutionId must not be blank");
    }
    if (workflowRunId == null || workflowRunId.isBlank()) {
      throw new IllegalArgumentException("workflowRunId must not be blank");
    }
    Objects.requireNonNull(stage, "stage");
    Objects.requireNonNull(runnerKind, "runnerKind");
    Objects.requireNonNull(contextBundlePath, "contextBundlePath");
    Objects.requireNonNull(executionConstraints, "executionConstraints");
    Objects.requireNonNull(classification, "classification");
  }
}
