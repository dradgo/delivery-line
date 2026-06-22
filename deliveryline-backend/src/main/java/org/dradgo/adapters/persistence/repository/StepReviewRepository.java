package org.dradgo.adapters.persistence.repository;

import java.util.List;
import java.util.Optional;
import org.dradgo.adapters.persistence.entity.StepReviewEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Story 3d-2 (AC2/AC3) — Spring Data repository for the {@code step_reviews} table (V19). All read
 * queries enforce {@code archived_at IS NULL} so tombstoned verdicts never reach the application
 * layer (retention is Epic 5).
 *
 * <p>The latest-verdict read backs the {@code GET …/reviewer-verdict} endpoint. It is a {@code join
 * fetch} because {@code StepReviewEntityMapper.toSnapshot} dereferences the LAZY {@code
 * workflowRun}/{@code runnerExecution}/{@code reviewedArtifact} proxies, and the verdict read can
 * run on a non-{@code @Transactional} / no-OSIV path — without the fetch it would {@code
 * LazyInitializationException}.
 */
public interface StepReviewRepository extends JpaRepository<StepReviewEntity, Long> {

  Optional<StepReviewEntity> findByPublicId(String publicId);

  /**
   * Latest non-archived verdict for the run (most recent {@code created_at}; row id tie-break).
   * Returns a list so the caller can take the first via a {@link Pageable} of size 1.
   */
  @Query(
      """
      select s from StepReviewEntity s
        join fetch s.workflowRun
        join fetch s.runnerExecution
        join fetch s.reviewedArtifact
      where s.workflowRun.publicId = :workflowRunPublicId
        and s.archivedAt is null
      order by s.createdAt desc, s.id desc
      """)
  List<StepReviewEntity> findLatestForRun(
      @Param("workflowRunPublicId") String workflowRunPublicId, Pageable pageable);
}
