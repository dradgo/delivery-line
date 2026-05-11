package org.dradgo.application.artifact.spi;

import java.util.Optional;
import org.dradgo.application.artifact.ArtifactChecksum;
import org.dradgo.application.artifact.ArtifactDraftRequest;
import org.dradgo.application.artifact.ArtifactRecordSnapshot;
import org.dradgo.application.artifact.ArtifactVersionRequest;
import org.dradgo.domain.registry.FailureCategory;

public interface ArtifactRecordPort {

	Optional<ArtifactRecordSnapshot> findByPublicId(String artifactId);

	Optional<ArtifactRecordSnapshot> findLatestByWorkflowRunIdAndArtifactType(String workflowRunId, String artifactType);

	Optional<ArtifactRecordSnapshot> findLatestByParentArtifactId(String lineageMemberArtifactId);

	/**
	 * Creates the first artifact record (version 1, status PENDING) for a new lineage within the
	 * given workflow run and artifact type.
	 *
	 * <p>Acquires a pessimistic write lock on the workflow run row before inserting so that
	 * concurrent drafts for the same {@code (workflow_run_id, artifact_type)} are serialized and
	 * the monotonic version counter remains correct. The lock is released when the enclosing
	 * transaction commits or rolls back.
	 *
	 * @throws org.dradgo.domain.DomainException with {@code WORKFLOW_RUN_TERMINAL} if the run is
	 *         already in a terminal state at the time the lock is acquired.
	 * @throws org.dradgo.domain.DomainException with {@code ARTIFACT_OPERATION_CONFLICT} if a
	 *         concurrent insert collides on the version uniqueness constraint.
	 */
	ArtifactRecordSnapshot createDraft(ArtifactDraftRequest request);

	/**
	 * Acquires a PostgreSQL advisory transaction lock scoped to {@code (workflowRunId, artifactType)}.
	 *
	 * <p>Uses {@code pg_advisory_xact_lock} so the lock is automatically released when the current
	 * transaction ends. The lock key is derived deterministically from the two arguments so that
	 * any caller holding a transaction that targets the same lineage blocks until the prior writer
	 * commits or rolls back, preventing interleaved PENDING artifact rows in the same family.
	 *
	 * <p>Must be called inside an active transaction. Intended to be invoked from
	 * {@link org.dradgo.application.artifact.ArtifactOperationService} immediately after the
	 * workflow-run liveness check and before any artifact row is read or written.
	 */
	void lockLineageForUpdate(String workflowRunId, String artifactType);

	/**
	 * Continues the current lineage leaf for the artifact family that contains the supplied member id.
	 *
	 * <p>The caller may pass the root, the current leaf, or any superseded lineage member; the
	 * adapter resolves the actual leaf from {@code (workflow_run_id, artifact_type)} and chains
	 * the new version off that leaf. A stale (non-leaf) argument is silently followed to the leaf
	 * — this is contract, not a bug. If the caller cares about the precise predecessor, query
	 * {@link #findLatestByParentArtifactId(String)} first and reuse that id.
	 */
	ArtifactRecordSnapshot createNextVersion(ArtifactVersionRequest request);

	ArtifactRecordSnapshot markAvailable(String artifactId, ArtifactChecksum checksum, String storageRef);

	ArtifactRecordSnapshot markFailed(String artifactId, FailureCategory failureCategory, String reason);

	ArtifactRecordSnapshot markLateOrStale(String artifactId);
}
