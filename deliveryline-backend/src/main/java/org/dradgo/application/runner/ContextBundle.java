package org.dradgo.application.runner;

import java.util.Objects;
import org.dradgo.domain.registry.DataClassification;
import org.dradgo.domain.registry.RunnerStage;

public record ContextBundle(
	String workflowRunId,
	RunnerStage stage,
	String runnerExecutionId,
	int contextBundleVersion,
	DataClassification effectiveClassification,
	byte[] redactedPayload
) {

	public ContextBundle {
		Objects.requireNonNull(workflowRunId, "workflowRunId");
		Objects.requireNonNull(stage, "stage");
		Objects.requireNonNull(runnerExecutionId, "runnerExecutionId");
		Objects.requireNonNull(effectiveClassification, "effectiveClassification");
		Objects.requireNonNull(redactedPayload, "redactedPayload");
		if (contextBundleVersion <= 0) {
			throw new IllegalArgumentException("contextBundleVersion must be positive");
		}
		if (redactedPayload.length == 0) {
			throw new IllegalArgumentException("redactedPayload must not be empty");
		}
		redactedPayload = redactedPayload.clone();
	}

	@Override
	public byte[] redactedPayload() {
		return redactedPayload.clone();
	}
}
