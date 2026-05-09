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

	ArtifactRecordSnapshot createDraft(ArtifactDraftRequest request);

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
