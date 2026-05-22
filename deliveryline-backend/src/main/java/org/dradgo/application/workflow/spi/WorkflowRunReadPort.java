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
   * @param stateFilter optional current-state filter; {@code null} returns runs in all states
   * @param limit maximum rows to return (callers clamp to a sane ceiling before calling)
   * @return run snapshots in newest-first order (possibly empty, never {@code null})
   */
  List<WorkflowRunSnapshot> listRuns(WorkflowState stateFilter, int limit);
}
