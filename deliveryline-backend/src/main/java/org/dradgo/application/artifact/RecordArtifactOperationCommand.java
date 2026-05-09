package org.dradgo.application.artifact;

import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.ArtifactOperationType;
import org.dradgo.domain.registry.ArtifactType;

public record RecordArtifactOperationCommand(
	String workflowRunId,
	ArtifactType artifactType,
	ArtifactOperationType operationType,
	String idempotencyKey,
	String payloadRef,
	byte[] payloadContent,
	String actorIdentity,
	ActorType actorType,
	String correlationId,
	String runnerExecutionId
) {
}
