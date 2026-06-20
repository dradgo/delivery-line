package org.dradgo.application.workflow.spi;

import org.dradgo.domain.registry.WorkflowState;

public interface WorkflowRunCreatePort {

  /**
   * Create a new workflow run row with the given initial state.
   *
   * <p>Caller must be in an active transaction; implementations participate via REQUIRED
   * propagation and rely on the surrounding service to coordinate atomicity with related
   * event-write calls.
   *
   * @param publicId non-null, non-blank public id (must be unique)
   * @param initialState non-null initial state
   * @param projectId the resolved project public id to bind the run to (story 3c-6 — the production
   *     caller resolves the default project first; the run row is never null at insert)
   * @return non-null snapshot of the freshly created row, including assigned version
   */
  WorkflowRunSnapshot create(String publicId, WorkflowState initialState, String projectId);
}
