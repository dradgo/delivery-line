package org.dradgo.adapters.persistence.repository;

import java.util.Optional;
import org.dradgo.adapters.persistence.entity.RecoveryActionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RecoveryActionRepository extends JpaRepository<RecoveryActionEntity, Long> {

  Optional<RecoveryActionEntity> findByPublicId(String publicId);

  Optional<RecoveryActionEntity> findByIdempotencyKey(String idempotencyKey);

  /**
   * Story 3.22 (AC8) — the most recent recovery action of a given {@code action_type} for a run,
   * resolved through the lazy {@code workflowRun} association's public id. Ordered by {@code
   * created_at} then {@code id} descending so ties (same-instant inserts) still yield the newest
   * row deterministically. Backs {@code RecoveryActionRecordPort.findLatestTakeoverForRun}.
   */
  Optional<RecoveryActionEntity>
      findFirstByWorkflowRunPublicIdAndActionTypeOrderByCreatedAtDescIdDesc(
          String workflowRunPublicId, String actionType);
}
