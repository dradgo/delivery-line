package org.dradgo.adapters.persistence.repository;

import java.util.List;
import org.dradgo.adapters.persistence.entity.ProjectRunnerKindEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Story 3e-4 — Spring Data repository for the V26 {@code project_runner_kinds} mapping table. Reads
 * the per-step rows for a project (ordered by the {@code step} column for determinism); the adapter
 * rebuilds the {@code Project.stepRunnerKinds} map from them.
 *
 * <p>The full-replace on update uses a bulk {@code @Modifying} delete (not a derived delete) so the
 * DELETE executes immediately against the DB BEFORE the replacement inserts. A derived delete only
 * queues entity removals, and Hibernate's flush orders INSERTs before DELETEs — re-setting the same
 * {@code (project_id, step)} would then collide on the composite primary key.
 */
public interface ProjectRunnerKindRepository
    extends JpaRepository<ProjectRunnerKindEntity, ProjectRunnerKindEntity.Key> {

  List<ProjectRunnerKindEntity> findByProjectIdOrderByStepAsc(String projectId);

  // clearAutomatically/flushAutomatically: flush pending work, run the bulk DELETE, then DETACH the
  // persistence context. Without the clear, a managed child entity from a prior load in the same
  // transaction (e.g. an insert→update round-trip under one @Transactional) would survive the
  // bulk delete; re-saving a same-PK (project_id, step) row would then be treated as a merge/UPDATE
  // against an already-deleted row → "Unexpected row count (expected 1 but was 0)". Clearing makes
  // the replacement rows unambiguous fresh INSERTs.
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("delete from ProjectRunnerKindEntity row where row.projectId = :projectId")
  int deleteByProjectId(@Param("projectId") String projectId);
}
