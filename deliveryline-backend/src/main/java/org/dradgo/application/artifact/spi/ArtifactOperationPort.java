package org.dradgo.application.artifact.spi;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.dradgo.application.artifact.ArtifactOperationSnapshot;
import org.dradgo.domain.registry.ArtifactType;
import org.dradgo.domain.registry.FailureCategory;

public interface ArtifactOperationPort {

	Optional<ArtifactOperationSnapshot> findReplay(
		String workflowRunId,
		ArtifactType artifactType,
		String idempotencyKey,
		String operationType
	);

	Optional<ArtifactOperationSnapshot> findPendingByArtifactId(String artifactId);

	ArtifactOperationSnapshot createPending(
		String operationPublicId,
		String workflowRunId,
		ArtifactType artifactType,
		String artifactId,
		String operationType,
		String idempotencyKey
	);

	ArtifactOperationSnapshot markComplete(String operationPublicId);

	ArtifactOperationSnapshot markFailed(String operationPublicId, FailureCategory failureCategory, String reason);

	List<ArtifactOperationSnapshot> findPendingCreatedBefore(OffsetDateTime threshold);

	/**
	 * Finds pending operations whose {@code created_at} is older than {@code now - threshold},
	 * with both sides of the comparison evaluated in database time.
	 *
	 * <p>Prefer this over {@link #findPendingCreatedBefore(OffsetDateTime)} for reconciliation
	 * paths: the JVM clock and the database clock can drift apart, and a JVM-derived threshold
	 * combined with DB-side {@code created_at} masks or hallucinates orphans during skew. This
	 * method keeps both sides on the same clock so the staleness window is honest.
	 */
	List<ArtifactOperationSnapshot> findPendingOlderThan(Duration threshold);

	ArtifactOperationSnapshot markFailedOrphan(String operationPublicId, String reason);
}
