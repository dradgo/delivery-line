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

  // Story 3c-6 (AC2) — the run's project_id without widening WorkflowRunSnapshot (which fans out to
  // every `new WorkflowRunSnapshot(...)`). Returns empty for a missing run AND for a run whose
  // project_id is null — both collapse to "no binding" so the resolver falls back to the default
  // project (Spring Data wraps a null scalar / NoResultException into Optional.empty()).
  @Query(
      "select workflowRun.projectId from WorkflowRunEntity workflowRun"
          + " where workflowRun.publicId = :publicId")
  Optional<String> findProjectIdByPublicId(@Param("publicId") String publicId);

  // Story 3c-6 (AC2) — backfill every run with a null project_id to the seeded default project. A
  // single @Modifying UPDATE, naturally idempotent (`where project_id is null`); returns the count.
  // flush/clear so a later read in the same tx (the seeder's log line / tests) sees the new values.
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      "update WorkflowRunEntity workflowRun set workflowRun.projectId = :projectId"
          + " where workflowRun.projectId is null")
  int backfillNullProjectIds(@Param("projectId") String projectId);

  // Story 3d-8 (FR67, AC1/AC4) — set the soft-hide marker WITHOUT touching current_state or version.
  // A bulk @Modifying update deliberately bypasses the @Version bump so a rare operator hide cannot
  // race the optimistic-lock guard on an in-flight state transition. The `archived_at is null` guard
  // makes the flip itself race-safe (mirrors MARK_ESCALATION_SQL): two concurrent hides with
  // distinct idempotency keys can no longer both pass a stale read-then-write and double-append the
  // governed event — the loser updates 0 rows. flush/clear so a same-tx re-read (the event append /
  // tests) sees the new marker.
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      "update WorkflowRunEntity workflowRun set workflowRun.archivedAt = :archivedAt"
          + " where workflowRun.publicId = :publicId and workflowRun.archivedAt is null")
  int archiveIfNotArchived(
      @Param("publicId") String publicId, @Param("archivedAt") java.time.OffsetDateTime archivedAt);

  // Symmetric race-safe clear: only an already-archived run is cleared, so a concurrent double
  // un-hide cannot double-append workflow.unarchived (the loser updates 0 rows).
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      "update WorkflowRunEntity workflowRun set workflowRun.archivedAt = null"
          + " where workflowRun.publicId = :publicId and workflowRun.archivedAt is not null")
  int clearArchivedIfArchived(@Param("publicId") String publicId);

  // Story 3d-8 (FR67, AC5/AC6) — newest-first listing filtered to NON-archived runs (the default
  // queue path; archived_at IS NULL). The include-archived path reuses the existing unfiltered
  // findAllByOrderByCreatedAtDescIdDesc, so a single boolean at the adapter chooses between them.
  List<WorkflowRunEntity> findByArchivedAtIsNullOrderByCreatedAtDescIdDesc(Pageable pageable);

  List<WorkflowRunEntity> findByCurrentStateAndArchivedAtIsNullOrderByCreatedAtDescIdDesc(
      String currentState, Pageable pageable);
}
