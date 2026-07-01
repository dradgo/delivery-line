package org.dradgo.adapters.persistence.repository;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.dradgo.adapters.persistence.entity.ClarificationEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for the V8 {@code clarifications} table (story 2.11). All read queries
 * enforce {@code archived_at IS NULL} so tombstoned rows never reach the application layer.
 *
 * <p>The status-grouped ordering ({@code open → answered → accepted → incorporated → superseded →
 * rejected_invalid}, with {@code created_at ASC + id ASC} within each group) is pinned in JPQL so
 * the UI (story 2.18) can render rows in a single render pass without re-sorting. Story 2.11 trap
 * T11.
 */
public interface ClarificationRepository extends JpaRepository<ClarificationEntity, Long> {

  Optional<ClarificationEntity> findByPublicIdAndArchivedAtIsNull(String publicId);

  Optional<ClarificationEntity> findByPublicId(String publicId);

  /**
   * Story 3e-1 review (D1) — pre-flight idempotency-key existence probe. Deliberately NOT
   * archived-filtered: {@code uq_clarifications_idempotency_key} (V8) is a TOTAL {@code UNIQUE
   * (idempotency_key)}, so an archived row with the same key still collides on insert. The probe
   * must mirror the constraint scope exactly to keep {@code ClarificationIngestService} from
   * flushing a conflicting INSERT into the broker's shared transaction.
   */
  boolean existsByIdempotencyKey(String idempotencyKey);

  /**
   * P30/D2 — pessimistic row-lock variant of {@link #findByPublicIdAndArchivedAtIsNull}. Emits
   * {@code SELECT ... FOR UPDATE} so a concurrent {@code ClarificationLifecycleService.mark*} on
   * the same row in another transaction blocks until the holder commits or rolls back. The caller
   * MUST be inside an outer transaction (the lifecycle service is invoked under the spec-rebuild
   * outer {@code @Transactional}). Eliminates the duplicate-event race between sweep and a manual
   * lifecycle transition.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      """
      select c from ClarificationEntity c
        join c.workflowRun wr
      where c.publicId = :publicId
        and wr.publicId = :workflowRunPublicId
        and c.archivedAt is null
      """)
  Optional<ClarificationEntity> findByWorkflowRunPublicIdAndPublicIdAndArchivedAtIsNullForUpdate(
      @Param("workflowRunPublicId") String workflowRunPublicId, @Param("publicId") String publicId);

  /**
   * All non-archived clarifications for the supplied workflow run, status-grouped + chronological
   * within each group. {@code join fetch} on {@code workflowRun} + {@code artifact} kills the
   * lazy-load N+1 in {@link
   * org.dradgo.adapters.persistence.mapper.ClarificationEntityMapper#toProjection} which
   * dereferences both public ids per row.
   */
  @Query(
      """
      select c from ClarificationEntity c
        join fetch c.workflowRun wr
        join fetch c.artifact a
      where wr.publicId = :workflowRunPublicId
        and c.archivedAt is null
      order by case c.status
          when 'open' then 0
          when 'answered' then 1
          when 'accepted' then 2
          when 'incorporated' then 3
          when 'superseded' then 3
          when 'rejected_invalid' then 3
        end,
        c.createdAt asc,
        c.id asc
      """)
  List<ClarificationEntity> findByWorkflowRunPublicIdOrdered(
      @Param("workflowRunPublicId") String workflowRunPublicId);

  /** Same ordering contract scoped to a single artifact's clarification list. */
  @Query(
      """
      select c from ClarificationEntity c
        join fetch c.workflowRun wr
        join fetch c.artifact a
      where a.publicId = :artifactPublicId
        and c.archivedAt is null
      order by case c.status
          when 'open' then 0
          when 'answered' then 1
          when 'accepted' then 2
          when 'incorporated' then 3
          when 'superseded' then 3
          when 'rejected_invalid' then 3
        end,
        c.createdAt asc,
        c.id asc
      """)
  List<ClarificationEntity> findByArtifactPublicIdOrdered(
      @Param("artifactPublicId") String artifactPublicId);

  /**
   * Story 2.12 AC9 — count of non-terminal clarifications for the supplied workflow run. Native
   * query so Postgres can use the V9 partial index {@code
   * idx_clarifications_pending_by_workflow_run} directly (V31 re-narrowed that index to the same
   * predicate). The excluded set is EXACTLY {@link
   * org.dradgo.application.clarification.Clarification#isTerminal()} — {@code incorporated}, {@code
   * rejected_invalid}, AND {@code superseded}. A superseded clarification is no longer the active
   * question (replaced by a newer version, or resolved not-addressed during a regenerate sweep), so
   * it MUST NOT keep gating {@code approve_spec} — otherwise a run whose spec was regenerated is
   * wedged: valid spec, but leftover superseded rows block approval forever.
   */
  @Query(
      value =
          "select count(*) from clarifications c "
              + "where c.workflow_run_id = (select id from workflow_runs where public_id = :runPublicId) "
              + "  and c.archived_at is null "
              + "  and c.status not in ('incorporated', 'rejected_invalid', 'superseded')",
      nativeQuery = true)
  long countPendingByWorkflowRunPublicId(@Param("runPublicId") String runPublicId);
}
