package org.dradgo.adapters.persistence.repository;

import java.util.List;
import java.util.Optional;
import org.dradgo.adapters.persistence.entity.ArtifactEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ArtifactRepository extends JpaRepository<ArtifactEntity, Long> {

	Optional<ArtifactEntity> findByPublicId(String publicId);

	Optional<ArtifactEntity> findFirstByWorkflowRunPublicIdAndArtifactTypeAndArchivedAtIsNullOrderByVersionDesc(
		String workflowRunPublicId,
		String artifactType
	);

	List<ArtifactEntity> findByWorkflowRunPublicIdAndArtifactTypeOrderByVersionDesc(
		String workflowRunPublicId,
		String artifactType
	);
}
