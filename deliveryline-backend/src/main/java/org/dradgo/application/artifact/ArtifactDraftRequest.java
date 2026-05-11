package org.dradgo.application.artifact;

import org.dradgo.domain.registry.ArtifactType;
import org.dradgo.domain.registry.DataClassification;

public record ArtifactDraftRequest(
	String workflowRunId,
	ArtifactType artifactType,
	String payloadRef,
	DataClassification classification,
	ActorContext actor,
	String operationType,
	String operationPublicId,
	String idempotencyKey,
	String runnerExecutionId
) {

	public ArtifactDraftRequest {
		if (workflowRunId == null || workflowRunId.isBlank()) {
			throw new IllegalArgumentException("workflowRunId must not be blank");
		}
		if (artifactType == null) {
			throw new IllegalArgumentException("artifactType must not be null");
		}
		if (classification == null) {
			throw new IllegalArgumentException("classification must not be null");
		}
		if (actor == null) {
			throw new IllegalArgumentException("actor must not be null");
		}
		if (payloadRef != null && payloadRef.isBlank()) {
			throw new IllegalArgumentException("payloadRef must not be blank when provided");
		}
	}
}
