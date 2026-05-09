package org.dradgo.adapters.persistence.repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.dradgo.adapters.persistence.entity.ArtifactOperationEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ArtifactOperationRepository extends JpaRepository<ArtifactOperationEntity, Long> {

	Optional<ArtifactOperationEntity> findByPublicId(String publicId);

	Optional<ArtifactOperationEntity> findFirstByWorkflowRunPublicIdAndArtifactTypeAndIdempotencyKeyAndOperationType(
		String workflowRunPublicId,
		String artifactType,
		String idempotencyKey,
		String operationType
	);

	Optional<ArtifactOperationEntity> findFirstByIdempotencyKeyAndOperationTypeAndArtifactPublicId(
		String idempotencyKey,
		String operationType,
		String artifactPublicId
	);

	List<ArtifactOperationEntity> findByStatusAndCreatedAtBefore(String status, OffsetDateTime threshold);

	@Query(value = "SELECT * FROM artifact_operations "
		+ "WHERE status = :status AND created_at < (now() - make_interval(secs => :seconds)) "
		+ "ORDER BY created_at ASC",
		nativeQuery = true)
	List<ArtifactOperationEntity> findByStatusAndCreatedAtOlderThanSeconds(
		@Param("status") String status,
		@Param("seconds") long seconds);

	Optional<ArtifactOperationEntity> findFirstByArtifactPublicIdOrderByCreatedAtDesc(String artifactPublicId);

	List<ArtifactOperationEntity> findByArtifactPublicIdAndStatusOrderByCreatedAtDesc(String artifactPublicId, String status);
}
