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

	private static final int IDEMPOTENCY_KEY_MAX = 256;
	private static final int CORRELATION_ID_MAX = 256;
	private static final int PAYLOAD_REF_MAX = 256;
	private static final int RUNNER_EXECUTION_ID_MAX = 256;

	public RecordArtifactOperationCommand {
		if (workflowRunId == null || workflowRunId.isBlank()) {
			throw new IllegalArgumentException("workflowRunId must not be blank");
		}
		if (artifactType == null) {
			throw new IllegalArgumentException("artifactType must not be null");
		}
		if (operationType == null) {
			throw new IllegalArgumentException("operationType must not be null");
		}
		if (idempotencyKey == null || idempotencyKey.isBlank()) {
			throw new IllegalArgumentException("idempotencyKey must not be blank");
		}
		if (idempotencyKey.length() > IDEMPOTENCY_KEY_MAX) {
			throw new IllegalArgumentException(
				"idempotencyKey must not exceed " + IDEMPOTENCY_KEY_MAX + " characters, got " + idempotencyKey.length());
		}
		if (payloadRef != null && payloadRef.isBlank()) {
			throw new IllegalArgumentException("payloadRef must not be blank when provided");
		}
		if (payloadRef != null && payloadRef.length() > PAYLOAD_REF_MAX) {
			throw new IllegalArgumentException(
				"payloadRef must not exceed " + PAYLOAD_REF_MAX + " characters, got " + payloadRef.length());
		}
		if (payloadContent != null && payloadContent.length == 0) {
			throw new IllegalArgumentException("payloadContent must not be empty when provided");
		}
		if (actorIdentity == null || actorIdentity.isBlank()) {
			throw new IllegalArgumentException("actorIdentity must not be blank");
		}
		if (actorType == null) {
			throw new IllegalArgumentException("actorType must not be null");
		}
		if (correlationId != null && correlationId.length() > CORRELATION_ID_MAX) {
			throw new IllegalArgumentException(
				"correlationId must not exceed " + CORRELATION_ID_MAX + " characters, got " + correlationId.length());
		}
		if (runnerExecutionId != null && runnerExecutionId.length() > RUNNER_EXECUTION_ID_MAX) {
			throw new IllegalArgumentException(
				"runnerExecutionId must not exceed " + RUNNER_EXECUTION_ID_MAX + " characters, got " + runnerExecutionId.length());
		}
		payloadContent = payloadContent == null ? null : payloadContent.clone();
	}
}
