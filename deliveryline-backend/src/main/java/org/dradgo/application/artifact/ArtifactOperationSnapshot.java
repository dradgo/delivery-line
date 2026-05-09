package org.dradgo.application.artifact;

import java.time.OffsetDateTime;
import org.dradgo.domain.registry.ArtifactOperationStatus;
import org.dradgo.domain.registry.FailureCategory;

public record ArtifactOperationSnapshot(
	String publicId,
	String workflowRunId,
	String artifactId,
	String operationType,
	ArtifactOperationStatus status,
	String idempotencyKey,
	FailureCategory failureCategory,
	String reason,
	OffsetDateTime createdAt
) {
}
