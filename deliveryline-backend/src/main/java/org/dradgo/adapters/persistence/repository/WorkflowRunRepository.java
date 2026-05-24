package org.dradgo.adapters.persistence.repository;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.dradgo.adapters.persistence.entity.WorkflowRunEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WorkflowRunRepository extends JpaRepository<WorkflowRunEntity, Long> {

  Optional<WorkflowRunEntity> findByPublicId(String publicId);

  // Story 6.9 — newest-first run listing for GET /api/v1/workflows. created_at desc with an id
  // tiebreak gives a deterministic order even when fixtures share a created_at. Pageable caps the
  // row count (callers clamp the limit before building the page request).
  List<WorkflowRunEntity> findAllByOrderByCreatedAtDescIdDesc(Pageable pageable);

  List<WorkflowRunEntity> findByCurrentStateOrderByCreatedAtDescIdDesc(
      String currentState, Pageable pageable);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      "select workflowRun from WorkflowRunEntity workflowRun where workflowRun.publicId = :publicId")
  Optional<WorkflowRunEntity> findByPublicIdForUpdate(@Param("publicId") String publicId);

  boolean existsByPublicId(String publicId);

  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      """
		update WorkflowRunEntity workflowRun
		set workflowRun.currentState = :currentState,
			workflowRun.version = workflowRun.version + 1
		where workflowRun.publicId = :publicId
		  and workflowRun.version = :expectedVersion
		""")
  int updateCurrentState(
      @Param("publicId") String publicId,
      @Param("currentState") String currentState,
      @Param("expectedVersion") Long expectedVersion);

}
