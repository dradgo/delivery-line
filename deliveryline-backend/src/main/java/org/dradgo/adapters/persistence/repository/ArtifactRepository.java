package org.dradgo.adapters.persistence.repository;

import java.util.Optional;
import org.dradgo.adapters.persistence.entity.ArtifactEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ArtifactRepository extends JpaRepository<ArtifactEntity, Long> {

  Optional<ArtifactEntity> findByPublicId(String publicId);

  Optional<ArtifactEntity>
      findFirstByWorkflowRunPublicIdAndArtifactTypeAndArchivedAtIsNullOrderByVersionDesc(
          String workflowRunPublicId, String artifactType);

  /**
   * All non-archived artifact rows for the run + artifact-type filter, in ascending version order.
   * Story 2.8: spec history (FR11) + spec-investigation prior-versions walk.
   */
  java.util.List<ArtifactEntity>
      findByWorkflowRunPublicIdAndArtifactTypeAndArchivedAtIsNullOrderByVersionAsc(
          String workflowRunPublicId, String artifactType);

  /**
   * Story 2.8 AC7: resolve the runner-execution public id associated with the artifact's creation
   * event (read from the linked workflow_events row's JSONB {@code details->>'runnerExecutionId'}).
   * Single-statement native query — no entity hydration.
   */
  @Query(
      value =
          """
          SELECT we.details ->> 'runnerExecutionId'
            FROM artifacts a
            JOIN workflow_events we ON we.id = a.linked_event_id
           WHERE a.public_id = :artifactId
           LIMIT 1
          """,
      nativeQuery = true)
  String findRunnerExecutionIdForArtifact(@Param("artifactId") String artifactId);

  /**
   * Story 1.12c (AC1+AC2): bounded single-statement leaf resolver.
   *
   * <p>Replaces the legacy unbounded sibling load + JVM-side parent-chain walk in {@code
   * ArtifactRecordPersistenceAdapter#findLatestActiveLineageMemberEntity}. A native PostgreSQL
   * recursive CTE walks each non-archived sibling's parent chain entirely DB-side and returns only
   * the highest-version sibling whose chain reaches the requested lineage member. One SQL statement
   * regardless of sibling count S or chain depth D.
   *
   * <p>Cycle defense: the recursion is bounded by {@code depth < 10000}. The schema's {@code
   * ck_artifacts_no_self_parent} CHECK constraint plus insert-only {@code parent_artifact_id} keep
   * real cycles unreachable; the depth cap is defense-in-depth against a hypothetical malformed
   * graph.
   *
   * <p>Depth-cap contract (story 1.12c review D1, accepted): if the recursion bound is hit before
   * any chain reaches {@code :lineageMemberPublicId}, the CTE returns no row. The adapter's caller
   * observes {@code Optional.empty()} and follows its existing fallback (typically {@code
   * createNextVersion} grafts onto the latest-active artifact). No log is emitted. Intentional:
   * schema invariants make legitimate chains beyond 10000 unreachable, so the cap fires only on a
   * malformed graph that should be invisible to normal operation.
   *
   * <p>Per-chain early termination: once a chain hits the target {@code lineageMemberPublicId} it
   * stops climbing further ancestors, keeping the CTE row count bounded by the matched chains'
   * depth-to-target rather than each sibling's full root-walk.
   *
   * <p>PostgreSQL-only. The project has no other database target (architecture.md:233) and {@code
   * WorkflowEventRepository#findLatestCorrelationIdInDetails} already establishes the precedent for
   * PG-native recursive/SQL features.
   */
  @Query(
      value =
          """
		WITH RECURSIVE chain(leaf_id, leaf_version, cursor_public_id, parent_artifact_id, depth) AS (
		    SELECT a.id, a.version, a.public_id, a.parent_artifact_id, 0
		      FROM artifacts a
		      JOIN workflow_runs wr ON wr.id = a.workflow_run_id
		     WHERE wr.public_id = :workflowRunPublicId
		       AND a.artifact_type = :artifactType
		       AND a.archived_at IS NULL
		    UNION ALL
		    SELECT c.leaf_id, c.leaf_version, parent.public_id, parent.parent_artifact_id, c.depth + 1
		      FROM chain c
		      JOIN artifacts parent ON parent.id = c.parent_artifact_id
		     WHERE c.depth < 10000
		       AND c.cursor_public_id <> :lineageMemberPublicId
		)
		SELECT a.*
		  FROM artifacts a
		 WHERE a.id = (
		    SELECT c.leaf_id
		      FROM chain c
		     WHERE c.cursor_public_id = :lineageMemberPublicId
		     ORDER BY c.leaf_version DESC
		     LIMIT 1
		 )
		""",
      nativeQuery = true)
  Optional<ArtifactEntity> findActiveLineageLeaf(
      @Param("workflowRunPublicId") String workflowRunPublicId,
      @Param("artifactType") String artifactType,
      @Param("lineageMemberPublicId") String lineageMemberPublicId);

  /**
   * Story 4.15 (AC1): bounded, KEYSET-PAGED oldest-first scan of {@code available} artifacts older
   * than {@code now() - minAgeMinutes}, for the artifact-drift-detection sweep. DB-side staleness
   * (both sides on the database clock — no JVM-derived cutoff) mirrors {@code
   * ArtifactOperationRepository.findPendingOlderThan}. Backed by the V45 partial index {@code
   * idx_artifacts_status_created_at WHERE status='available'}; {@code SELECT a.*} hydrates the full
   * entity so the caller can map it via {@code ArtifactEntityMapper}.
   *
   * <p><strong>Keyset cursor (story 4.15 review D1).</strong> The sweep is detection-only — it
   * never flips {@code status}, so a clean {@code available} artifact stays {@code available}
   * forever. Without a cursor an {@code ORDER BY created_at LIMIT n} scan re-reads the same oldest
   * {@code n} every tick and NEVER reaches drift on newer artifacts once more than {@code n} are
   * eligible. {@code (afterCreatedAt, afterPublicId)} is the exclusive keyset cursor the caller
   * advances each tick ({@code NULL} = start from the oldest); the {@code (created_at, public_id)}
   * tuple ordering matches so successive ticks walk the whole eligible set. {@code public_id}
   * (unique) is the stable tiebreak.
   */
  @Query(
      value =
          """
          SELECT a.*
            FROM artifacts a
           WHERE a.status = 'available'
             AND a.created_at < now() - (:minAgeMinutes * interval '1 minute')
             AND (cast(:afterCreatedAt as timestamptz) is null
                  OR (a.created_at, a.public_id) > (:afterCreatedAt, :afterPublicId))
           ORDER BY a.created_at ASC, a.public_id ASC
           LIMIT :batchLimit
          """,
      nativeQuery = true)
  java.util.List<ArtifactEntity> findAvailableCreatedBefore(
      @Param("minAgeMinutes") long minAgeMinutes,
      @Param("batchLimit") int batchLimit,
      @Param("afterCreatedAt") java.time.OffsetDateTime afterCreatedAt,
      @Param("afterPublicId") String afterPublicId);
}
