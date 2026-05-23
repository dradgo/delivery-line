package org.dradgo.adapters.persistence.repository;

import java.util.List;
import java.util.Optional;
import org.dradgo.adapters.persistence.entity.ApprovalEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for the {@code approvals} table (story 2.8). All read queries enforce
 * {@code archived_at IS NULL} so tombstoned rows never reach the application layer (trap T7).
 *
 * <p>Story 2.8 is read-only at the application boundary; writers ship with stories 2.9 / 2.10.
 * Direct repository inserts are used in tests to seed approval rows without going through a
 * not-yet-written {@code ApprovalService}.
 */
public interface ApprovalRepository extends JpaRepository<ApprovalEntity, Long> {

  /**
   * Latest approved row for the run + artifact-type filter. Prefers the highest approved artifact
   * version; ties fall back to {@code decided_at} and row id for deterministic ordering. Returns at
   * most one row.
   */
  @Query(
      """
      select a from ApprovalEntity a
        join fetch a.workflowRun
        join fetch a.artifact
      where a.workflowRun.publicId = :workflowRunPublicId
        and a.artifact.artifactType = :artifactType
        and a.decision = 'approved'
        and a.archivedAt is null
        and a.artifact.archivedAt is null
      order by a.artifactVersion desc, a.decidedAt desc, a.id desc
      """)
  List<ApprovalEntity> findLatestApprovedForArtifactLineage(
      @Param("workflowRunPublicId") String workflowRunPublicId,
      @Param("artifactType") String artifactType);

  /**
   * All non-archived decisions for the run + artifact-type filter, chronological ascending with an
   * {@code id} tie-breaker for deterministic ordering. Used by {@code getSpecHistory} (FR11). The
   * {@code join fetch} eliminates a lazy-load N+1 in {@code ApprovalEntityMapper.toSnapshot}, which
   * dereferences {@code workflowRun.publicId} / {@code artifact.publicId} per row.
   */
  @Query(
      """
      select a from ApprovalEntity a
        join fetch a.workflowRun
        join fetch a.artifact
      where a.workflowRun.publicId = :workflowRunPublicId
        and a.artifact.artifactType = :artifactType
        and a.archivedAt is null
        and a.artifact.archivedAt is null
      order by a.decidedAt asc, a.id asc
      """)
  List<ApprovalEntity> listByWorkflowRunAndArtifactType(
      @Param("workflowRunPublicId") String workflowRunPublicId,
      @Param("artifactType") String artifactType);

  /**
   * All non-archived rejected decisions for the run + artifact-type filter, chronological
   * ascending. Used by spec-investigation context-bundle composition (story 2.8 AC3
   * priorFeedbackReferences source).
   */
  @Query(
      """
      select a from ApprovalEntity a
        join fetch a.workflowRun
        join fetch a.artifact
      where a.workflowRun.publicId = :workflowRunPublicId
        and a.artifact.artifactType = :artifactType
        and a.decision = 'rejected'
        and a.archivedAt is null
        and a.artifact.archivedAt is null
      order by a.decidedAt asc, a.id asc
      """)
  List<ApprovalEntity> listRejectionsByWorkflowRunAndArtifactType(
      @Param("workflowRunPublicId") String workflowRunPublicId,
      @Param("artifactType") String artifactType);

  Optional<ApprovalEntity> findByPublicId(String publicId);
}
