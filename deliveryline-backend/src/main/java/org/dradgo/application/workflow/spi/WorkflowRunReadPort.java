package org.dradgo.application.workflow.spi;

import java.util.List;
import java.util.Optional;
import org.dradgo.domain.registry.WorkflowState;

public interface WorkflowRunReadPort {

  /**
   * Look up a workflow run by its public id.
   *
   * @param publicId non-null public id
   * @return present snapshot if a row exists; empty Optional if no row matches
   */
  Optional<WorkflowRunSnapshot> findByPublicId(String publicId);

  /**
   * List workflow runs newest-first (by {@code created_at} descending, id tiebreak), optionally
   * filtered by current state, capped at {@code limit} rows (story 6.9 — backs {@code GET
   * /api/v1/workflows}). Ordering is performed in the database so the returned order is
   * authoritative.
   *
   * <p>Story 3d-8 (FR67, AC5/AC6): when {@code includeArchived} is {@code false} (the default queue
   * path) soft-hidden runs ({@code archived_at IS NOT NULL}) are excluded; {@code true} returns
   * them alongside live runs so the operator can review hidden work. The single-run by-id read
   * ({@link #findByPublicId}) is never archive-filtered (audit-queryable path).
   *
   * @param stateFilter optional current-state filter; {@code null} returns runs in all states
   * @param includeArchived when {@code false}, excludes runs with a non-null {@code archived_at}
   * @param limit maximum rows to return (callers clamp to a sane ceiling before calling)
   * @return run snapshots in newest-first order (possibly empty, never {@code null})
   */
  default List<WorkflowRunSnapshot> listRuns(
      WorkflowState stateFilter, boolean includeArchived, int limit) {
    return listRuns(stateFilter, includeArchived, limit, null);
  }

  List<WorkflowRunSnapshot> listRuns(
      WorkflowState stateFilter, boolean includeArchived, int limit, String projectId);
}
