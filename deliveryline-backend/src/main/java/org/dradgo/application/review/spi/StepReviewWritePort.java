package org.dradgo.application.review.spi;

import org.dradgo.application.review.StepReviewSnapshot;
import org.dradgo.domain.registry.ReviewOutcome;

/**
 * Story 3d-2 (AC2/AC4) — write SPI over the {@code step_reviews} table (V19 schema, 3d-1). The
 * net-new core-table write seam this story introduces, mirroring {@code
 * org.dradgo.application.approval.spi.ApprovalWritePort}: a reviewer runner invocation harvests its
 * verdict directly into {@code step_reviews} (NOT the {@code artifacts} table — DD-2), so a verdict
 * is advisory metadata <em>about</em> an existing artifact, not itself a reviewable artifact.
 *
 * <p>{@code insert} is {@code Propagation.REQUIRED}: the REVIEW harvest invokes it inside its own
 * programmatic transaction (the verdict insert + the reviewer-execution finalize commit
 * atomically), which REQUIRED joins; with no ambient transaction it opens its own. The verdict
 * insert is the only write the REVIEW harvest performs besides finalizing its {@code
 * runner_executions} row. There is no idempotency-key column (a reviewer execution produces at most
 * one verdict); the partial-unique index {@code uq_step_reviews_runner_execution} (V21) is the
 * defense-in-depth backstop forbidding a duplicate verdict for the same reviewer execution.
 */
public interface StepReviewWritePort {

  /**
   * Insert a single advisory verdict row, returning the persisted projection.
   *
   * @throws org.dradgo.domain.DomainException {@code RUN_NOT_FOUND} / {@code
   *     RUNNER_EXECUTION_NOT_FOUND} / {@code ARTIFACT_RECORD_NOT_FOUND} when a referenced FK row is
   *     absent, or {@code INTERNAL_ERROR} on a public-id collision / otherwise-unmapped integrity
   *     violation. The persistence exception is mapped without leaking detail.
   * @throws org.dradgo.application.review.spi.DuplicateStepReviewException when a verdict for the
   *     same reviewer execution already exists (V21 partial-unique index) — a benign, idempotent
   *     duplicate the harvest treats as a no-op rather than a fault.
   */
  StepReviewSnapshot insert(NewStepReview newStepReview);

  /**
   * Caller-built insert payload. {@code publicId} is supplied externally (caller-generated via
   * {@link org.dradgo.domain.id.PublicIdPrefixes#REVIEW}) so log lines remain deterministic before
   * the row exists.
   *
   * @param publicId {@code rev_…}
   * @param workflowRunPublicId {@code run_…}
   * @param runnerExecutionPublicId the reviewer execution that produced the verdict ({@code rex_…})
   * @param reviewedArtifactPublicId the reviewed output artifact ({@code art_…})
   * @param reviewedArtifactVersion exact version pinned by the composite FK into {@code artifacts}
   * @param outcome the advisory verdict ({@code pass}/{@code concern}/{@code fail})
   * @param rationale nullable, ALREADY POST-REDACTION reviewer rationale (AC7 — the caller redacts
   *     before handing it here; the adapter never re-redacts and never logs the text)
   * @param reviewerModelIdentity the model that produced the review (kind + image tag); nullable
   * @param producerModelIdentity the model that produced the reviewed artifact; nullable
   */
  record NewStepReview(
      String publicId,
      String workflowRunPublicId,
      String runnerExecutionPublicId,
      String reviewedArtifactPublicId,
      int reviewedArtifactVersion,
      ReviewOutcome outcome,
      String rationale,
      String reviewerModelIdentity,
      String producerModelIdentity) {}
}
