package org.dradgo.adapters.persistence.repository;

import java.util.List;
import java.util.Optional;
import org.dradgo.adapters.persistence.entity.ArtifactOperationEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ArtifactOperationRepository extends JpaRepository<ArtifactOperationEntity, Long> {

  Optional<ArtifactOperationEntity> findByPublicId(String publicId);

  Optional<ArtifactOperationEntity>
      findFirstByWorkflowRunPublicIdAndArtifactTypeAndIdempotencyKeyAndOperationType(
          String workflowRunPublicId,
          String artifactType,
          String idempotencyKey,
          String operationType);

  Optional<ArtifactOperationEntity> findFirstByIdempotencyKeyAndOperationTypeAndArtifactPublicId(
      String idempotencyKey, String operationType, String artifactPublicId);

  @Query(
      value =
          "SELECT ao.* FROM artifact_operations ao "
              + "JOIN artifacts a ON a.id = ao.artifact_id "
              + "WHERE ao.status = :status "
              + "AND ao.created_at < (now() - make_interval(secs => :seconds)) "
              + "AND a.archived_at IS NULL "
              + "ORDER BY ao.created_at ASC "
              + "LIMIT 500",
      nativeQuery = true)
  List<ArtifactOperationEntity> findByStatusAndCreatedAtOlderThanSeconds(
      @Param("status") String status, @Param("seconds") long seconds);

  Optional<ArtifactOperationEntity> findFirstByArtifactPublicIdOrderByCreatedAtDesc(
      String artifactPublicId);

  List<ArtifactOperationEntity> findByArtifactPublicIdAndStatusOrderByCreatedAtDesc(
      String artifactPublicId, String status);

  @Query(
      """
		select count(op) > 0
		from ArtifactOperationEntity op
		where op.workflowRun.publicId = :workflowRunPublicId
		  and op.status in ('failed', 'failed_orphan')
		""")
  boolean existsFailedOrFailedOrphanByWorkflowRunPublicId(
      @Param("workflowRunPublicId") String workflowRunPublicId);
}
