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

  // Story 3e-2 (review P1) — type-AGNOSTIC lookup of the operation already recorded for an
  // idempotency key within a run+artifactType (any operation_type). Lets the broker REUSE the
  // operation_type a prior harvest chose instead of recomputing it from mutable lineage state on a
  // re-harvest (a recompute flips CREATE->UPDATE and, because the replay key includes
  // operation_type, misses the stored row and mints a spurious extra version).
  Optional<ArtifactOperationEntity>
      findFirstByWorkflowRunPublicIdAndArtifactTypeAndIdempotencyKeyOrderByCreatedAtAsc(
          String workflowRunPublicId, String artifactType, String idempotencyKey);

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

  /**
   * Story 4.15 (review D2) — bounded, KEYSET-PAGED variant for the artifact-drift-detection orphan
   * scan, symmetric to {@code ArtifactRepository.findAvailableCreatedBefore}. The base finder above
   * (shared with the auto-repair reconciliation) hard-caps at 500 with no cursor; because the drift
   * sweep is detection-only (it never resolves an orphan), orphans beyond the oldest cap would
   * never be recorded without rotation. {@code (afterCreatedAt, afterPublicId)} is the exclusive
   * keyset cursor ({@code NULL} = start from the oldest) and {@code batchLimit} is the configured
   * sweep cap.
   */
  @Query(
      value =
          "SELECT ao.* FROM artifact_operations ao "
              + "JOIN artifacts a ON a.id = ao.artifact_id "
              + "WHERE ao.status = :status "
              + "AND ao.created_at < (now() - make_interval(secs => :seconds)) "
              + "AND a.archived_at IS NULL "
              + "AND (cast(:afterCreatedAt as timestamptz) is null "
              + "     OR (ao.created_at, ao.public_id) > (:afterCreatedAt, :afterPublicId)) "
              + "ORDER BY ao.created_at ASC, ao.public_id ASC "
              + "LIMIT :batchLimit",
      nativeQuery = true)
  List<ArtifactOperationEntity> findPendingOlderThanKeyset(
      @Param("status") String status,
      @Param("seconds") long seconds,
      @Param("batchLimit") int batchLimit,
      @Param("afterCreatedAt") java.time.OffsetDateTime afterCreatedAt,
      @Param("afterPublicId") String afterPublicId);

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
