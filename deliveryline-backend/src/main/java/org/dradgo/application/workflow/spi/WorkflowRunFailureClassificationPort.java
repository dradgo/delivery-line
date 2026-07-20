package org.dradgo.application.workflow.spi;

import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * Persistence port for the story-4.9 failure-classification triple on {@code workflow_runs} ({@code
 * failure_classification} / {@code failure_classified_at} / {@code failure_classified_by}, Flyway
 * V44).
 *
 * <p>Single-purpose interface mirroring {@link WorkflowRunRejectionLoopPort}: the three columns are
 * deliberately NOT mapped on {@code WorkflowRunEntity} (no {@code @DynamicUpdate} — a stale
 * full-row JPA save from a concurrent transition would null them) nor on {@code
 * WorkflowRunSnapshot} (canonical-constructor fan-out). The adapter implementation writes them with
 * targeted raw SQL and participates in the caller's transaction (no {@code REQUIRES_NEW}), so the
 * column update rolls back with the rest of the classify prep transaction.
 */
public interface WorkflowRunFailureClassificationPort {

  /**
   * Reads the current classification triple for the given run. Returns {@code Optional.empty()}
   * when the run has never been classified (all three columns null — the V44 all-or-nothing CHECK).
   *
   * @param workflowRunPublicId the run's public id (must exist)
   * @throws org.dradgo.domain.DomainException with {@code RUN_NOT_FOUND} if no row matches
   */
  Optional<FailureClassificationRecord> findClassification(String workflowRunPublicId);

  /**
   * Overwrites the classification triple for the given run and returns the PRIOR triple ({@code
   * Optional.empty()} on first classify). The prior value feeds the {@code
   * recovery.failureClassified} event's {@code priorTaxonomyValue} detail (AC9 — history is
   * preserved in the event chain, never on the row). Implementations capture the prior value under
   * {@code select … for update} in the same transaction as the update so a concurrent classify
   * cannot interleave between read and write.
   *
   * @param workflowRunPublicId the run's public id (must exist)
   * @param taxonomyValue the new wire value (already registry-validated by the caller)
   * @param classifiedAt attribution timestamp (never null)
   * @param classifiedBy attribution identity (never null)
   * @throws org.dradgo.domain.DomainException with {@code RUN_NOT_FOUND} if no row matches
   */
  Optional<FailureClassificationRecord> applyClassification(
      String workflowRunPublicId,
      String taxonomyValue,
      OffsetDateTime classifiedAt,
      String classifiedBy);
}
