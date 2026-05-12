package org.dradgo.application.runner;

import java.nio.file.Path;
import java.util.Objects;
import org.dradgo.domain.registry.DataClassification;
import org.dradgo.domain.registry.RunnerStage;

public record RunnerDispatchRequest(
	String runnerExecutionId,
	String workflowRunId,
	RunnerStage stage,
	Path contextBundlePath,
	ExecutionConstraints executionConstraints,
	DataClassification classification
) {

	public RunnerDispatchRequest {
		if (runnerExecutionId == null || runnerExecutionId.isBlank()) {
			throw new IllegalArgumentException("runnerExecutionId must not be blank");
		}
		if (workflowRunId == null || workflowRunId.isBlank()) {
			throw new IllegalArgumentException("workflowRunId must not be blank");
		}
		Objects.requireNonNull(stage, "stage");
		Objects.requireNonNull(contextBundlePath, "contextBundlePath");
		Objects.requireNonNull(executionConstraints, "executionConstraints");
		Objects.requireNonNull(classification, "classification");
	}
}
