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
		if (actor == null) {
			throw new IllegalArgumentException("actor must not be null");
		}
	}
}
