package org.dradgo.adapters.persistence.repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.dradgo.adapters.persistence.entity.WorkflowEventEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WorkflowEventRepository extends JpaRepository<WorkflowEventEntity, Long> {

  Optional<WorkflowEventEntity> findByPublicId(String publicId);

  /**
   * Story 2.12 — resolve a workflow_events row to its internal {@code id} (FK target for {@code
   * clarifications.incorporation_event_id}). Used by {@code ClarificationWritePersistenceAdapter
   * .markIncorporated} after {@code ClarificationLifecycleService} appends the event row.
   */
  @Query("select event.id from WorkflowEventEntity event where event.publicId = :publicId")
  Optional<Long> findIdByPublicId(@Param("publicId") String publicId);

  @Query(
      """
		select event from WorkflowEventEntity event
		where event.workflowRun.publicId = :publicId
		  and event.archivedAt is null
		order by event.createdAt desc, event.id desc
		""")
  List<WorkflowEventEntity> findLatestByWorkflowRunPublicId(
      @Param("publicId") String publicId, Pageable pageable);

  @Query(
      """
		select event from WorkflowEventEntity event
		where event.workflowRun.publicId = :publicId
		  and event.archivedAt is null
		order by event.createdAt asc, event.id asc
		""")
  List<WorkflowEventEntity> findByWorkflowRunPublicIdOrderByCreatedAtAscIdAsc(
      @Param("publicId") String publicId, Pageable pageable);

  @Query(
      """
		select event from WorkflowEventEntity event
		where event.workflowRun.publicId = :publicId
		  and event.archivedAt is null
		  and event.createdAt >= :sinceInclusive
		order by event.createdAt asc, event.id asc
		""")
  List<WorkflowEventEntity>
      findByWorkflowRunPublicIdAndCreatedAtGreaterThanEqualOrderByCreatedAtAscIdAsc(
          @Param("publicId") String publicId,
          @Param("sinceInclusive") OffsetDateTime sinceInclusive,
          Pageable pageable);

  default Optional<WorkflowEventEntity> findFirstLatestByWorkflowRunPublicId(String publicId) {
    List<WorkflowEventEntity> top =
        findLatestByWorkflowRunPublicId(
            publicId, org.springframework.data.domain.PageRequest.of(0, 1));
    return top.isEmpty() ? Optional.empty() : Optional.of(top.get(0));
  }

  @Query(
      """
		select event from WorkflowEventEntity event
		where event.workflowRun.publicId = :publicId
		  and event.archivedAt is null
		  and event.resultingState = 'Failed'
		  and event.priorState is not null
		order by event.createdAt desc, event.id desc
		""")
  List<WorkflowEventEntity> findLatestFailureEvent(
      @Param("publicId") String publicId, Pageable pageable);

  default Optional<WorkflowEventEntity> findFirstLatestFailureEvent(String publicId) {
    List<WorkflowEventEntity> top =
        findLatestFailureEvent(publicId, org.springframework.data.domain.PageRequest.of(0, 1));
    return top.isEmpty() ? Optional.empty() : Optional.of(top.get(0));
  }

  /**
   * Returns the most-recent non-blank {@code details->>'correlationId'} for the run, walking the
   * full event history newest-first at the DB layer. Filters on {@code archived_at IS NULL} to
   * ignore soft-deleted rows. PostgreSQL-native (uses {@code jsonb->>}) — the project's only
   * production target. Replaces the prior in-memory cap of 100 events that could miss the latest
   * stored correlation id on long-lived runs. (review F528)
   */
  @Query(
      value =
          """
		SELECT we.details->>'correlationId'
		  FROM workflow_events we
		  JOIN workflow_runs wr ON wr.id = we.workflow_run_id
		 WHERE wr.public_id = :publicId
		   AND we.archived_at IS NULL
		   AND we.details->>'correlationId' IS NOT NULL
		   AND we.details->>'correlationId' <> ''
		 ORDER BY we.created_at DESC, we.id DESC
		 LIMIT 1
		""",
      nativeQuery = true)
  Optional<String> findLatestCorrelationIdInDetails(@Param("publicId") String publicId);
}
