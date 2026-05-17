package org.dradgo.application.workflow.spi;

import org.dradgo.domain.registry.WorkflowState;

public interface WorkflowRunStatePort {

  /**
   * Atomically update the current state of a workflow run, gated on the expected optimistic-lock
   * version.
   *
   * <p>Caller must be in an active transaction; implementations participate via REQUIRED
   * propagation.
   *
   * @param publicId non-null public id of the run to update
   * @param targetState non-null target state
   * @param expectedVersion non-null current version observed by the caller (Long boxed only to
   *     align with JPA accessors)
   * @throws org.springframework.dao.OptimisticLockingFailureException when no row matched the
   *     (publicId, expectedVersion) tuple
   */
  void updateCurrentState(String publicId, WorkflowState targetState, Long expectedVersion);
}
