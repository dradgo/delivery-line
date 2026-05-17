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
}
