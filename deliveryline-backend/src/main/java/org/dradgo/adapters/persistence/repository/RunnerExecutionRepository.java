package org.dradgo.adapters.persistence.repository;

import jakarta.persistence.LockModeType;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.dradgo.adapters.persistence.entity.RunnerExecutionEntity;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RunnerExecutionRepository extends JpaRepository<RunnerExecutionEntity, Long> {

  Optional<RunnerExecutionEntity> findByPublicId(String publicId);

  /**
   * Story 3.18 (AC4) — stamp {@code batch_submission_id} onto every runner execution of a workflow
   * run, tracing a queued execution back to the batch that submitted it (serves story 3.19's
   * per-batch queue filtering). Native UPDATE so it needs no {@code RunnerExecutionEntity} field
   * for the forward-only column. Best-effort: returns 0 when the run dispatched no runner
   * execution.
   */
  @Modifying
  @Query(
      value =
          """
          update runner_executions
             set batch_submission_id = :batchSubmissionId
           where workflow_run_id =
                 (select id from workflow_runs where public_id = :workflowRunPublicId)
          """,
      nativeQuery = true)
  int stampBatchSubmissionId(
      @Param("workflowRunPublicId") String workflowRunPublicId,
      @Param("batchSubmissionId") String batchSubmissionId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      """
		select runnerExecution
		from RunnerExecutionEntity runnerExecution
		where runnerExecution.publicId = :publicId
		""")
  Optional<RunnerExecutionEntity> findByPublicIdForUpdate(@Param("publicId") String publicId);

  Optional<RunnerExecutionEntity>
      findFirstByWorkflowRunPublicIdAndStageOrderByContextBundleVersionDesc(
          String workflowRunPublicId, String stage);

  List<RunnerExecutionEntity> findByWorkflowRunPublicIdAndStatusIn(
      String workflowRunPublicId, List<String> statuses);

  @Query(
      """
		select runnerExecution
		from RunnerExecutionEntity runnerExecution
		where runnerExecution.status in :statuses
		  and runnerExecution.timeoutAt < :cutoff
		order by runnerExecution.timeoutAt asc
		""")
  List<RunnerExecutionEntity> findStaleByStatusInAndTimeoutAtBefore(
      @Param("statuses") List<String> statuses,
      @Param("cutoff") OffsetDateTime cutoff,
      Limit limit);

  List<RunnerExecutionEntity> findByStatusInOrderByCreatedAtAsc(List<String> statuses, Limit limit);

  @Query(
      """
		select runnerExecution
		from RunnerExecutionEntity runnerExecution
		where runnerExecution.status in :statuses
		  and runnerExecution.lastActivityAt < :cutoff
		order by runnerExecution.lastActivityAt asc
		""")
  List<RunnerExecutionEntity> findStaleByStatusInAndLastActivityAtBefore(
      @Param("statuses") List<String> statuses,
      @Param("cutoff") OffsetDateTime cutoff,
      Limit limit);

  // Story 3.2a AC2: stage-scoped variant so the LIMIT applies per-stage (no cross-stage
  // starvation). The stage is the persisted string value of RunnerStage (closed enum set).
  @Query(
      """
		select runnerExecution
		from RunnerExecutionEntity runnerExecution
		where runnerExecution.status in :statuses
		  and runnerExecution.stage = :stage
		  and runnerExecution.lastActivityAt < :cutoff
		order by runnerExecution.lastActivityAt asc
		""")
  List<RunnerExecutionEntity> findStaleByStatusInAndStageAndLastActivityAtBefore(
      @Param("statuses") List<String> statuses,
      @Param("stage") String stage,
      @Param("cutoff") OffsetDateTime cutoff,
      Limit limit);

  // Story 3.17b (AC5 / D6) — worker-crash lease reclamation. Finds LEASED-but-stalled rows: a
  // worker dequeued them (worker_id set, dispatched_at stamped) but the lease has aged past the
  // orphan threshold AND no heartbeat advanced last_activity_at since. Both predicates are required
  // so a healthy long-running execution (which advances last_activity_at via the heartbeat path) is
  // never reclaimed — only a row whose worker thread died mid-dispatch. queued rows (worker_id
  // null)
  // are deliberately excluded: they are correctly waiting and stay enqueueable.
  @Query(
      """
		select runnerExecution
		from RunnerExecutionEntity runnerExecution
		where runnerExecution.status = :status
		  and runnerExecution.stage = :stage
		  and runnerExecution.workerId is not null
		  and runnerExecution.dispatchedAt < :cutoff
		  and runnerExecution.lastActivityAt < :cutoff
		order by runnerExecution.dispatchedAt asc
		""")
  List<RunnerExecutionEntity> findLeasedStaleByStageAndDispatchedAtBefore(
      @Param("status") String status,
      @Param("stage") String stage,
      @Param("cutoff") OffsetDateTime cutoff,
      Limit limit);

  // Story 3.2 AC5: workspace cleanup uses status IN (completed, failed, timed_out, orphaned) AS
  // the primary defense against deleting workspaces whose row is still live (Trap T16). The
  // adapter constructs the status list from the closed-set RunnerExecutionStatus enum, NOT from
  // a free-form caller, so SQL injection is not a concern.
  @Query(
      """
		select runnerExecution
		from RunnerExecutionEntity runnerExecution
		where runnerExecution.status in :statuses
		  and runnerExecution.completedAt is not null
		  and runnerExecution.completedAt < :cutoff
		  and runnerExecution.archivedAt is null
		order by runnerExecution.completedAt asc
		""")
  List<RunnerExecutionEntity> findCompletedBeforeAndNotArchived(
      @Param("statuses") List<String> statuses,
      @Param("cutoff") OffsetDateTime cutoff,
      Limit limit);
}
