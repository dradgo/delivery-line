package org.dradgo.application.workflow.spi;

import java.time.Instant;

/**
 * Write SPI for the run-level soft-hide marker (story 3d-8, FR67, ADR 0027).
 *
 * <p>Archiving is <strong>orthogonal to the workflow lifecycle</strong>: it sets/clears {@code
 * workflow_runs.archived_at} and never touches {@code current_state}, so this port is deliberately
 * separate from {@link WorkflowRunStatePort} (whose {@code updateCurrentState} is ArchUnit-pinned
 * to {@code WorkflowTransitionService}). No row is ever deleted — un-hide is a reversible clear of
 * the same marker (ADR 0027 D5). The marker column already exists (V1); nothing else sets or clears
 * it.
 */
public interface WorkflowRunArchivePort {

  /**
   * Stamp the soft-hide marker on a run. Caller must be in an active transaction; implementations
   * participate via REQUIRED propagation so the marker write is atomic with the governed {@code
   * workflow.archived} event append that accompanies it.
   *
   * @param workflowRunPublicId non-null public id of the run to hide
   * @param archivedAt non-null instant to record as the hide time
   * @throws org.dradgo.domain.DomainException with {@code RUN_NOT_FOUND} when no row matches
   */
  void markArchived(String workflowRunPublicId, Instant archivedAt);

  /**
   * Clear the soft-hide marker on a run (reversible un-hide). Caller must be in an active
   * transaction; see {@link #markArchived} for atomicity.
   *
   * @param workflowRunPublicId non-null public id of the run to un-hide
   * @throws org.dradgo.domain.DomainException with {@code RUN_NOT_FOUND} when no row matches
   */
  void clearArchived(String workflowRunPublicId);
}
