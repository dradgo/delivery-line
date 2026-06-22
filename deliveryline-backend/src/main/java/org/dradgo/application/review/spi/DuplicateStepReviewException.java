package org.dradgo.application.review.spi;

/**
 * Story 3d-2 (code-review hardening) — a benign, idempotent signal that a {@code step_reviews}
 * verdict for the same reviewer execution already exists (the V21 partial-unique index {@code
 * uq_step_reviews_runner_execution} fired on insert).
 *
 * <p>This is NOT a contract violation: a duplicate/late harvest of the same reviewer execution is
 * explicitly anticipated (a recovery scratch-replay or a concurrent harvest re-delivering the same
 * result). The earlier mapping to {@code INTERNAL_ERROR} both surfaced a misleading 500 and made
 * the REVIEW harvest falsely degrade the run as {@code RUNNER_CONTRACT_VIOLATION}. The write
 * adapter now throws this dedicated unchecked signal so the harvest can treat the second delivery
 * as an idempotent no-op (the verdict the first delivery persisted stands) and still finalize the
 * reviewer execution — never falsely degrading.
 *
 * <p>Thrown across the adapter→application boundary (the adapter's transaction has already unwound
 * by the time it is caught), so the catch site is free of the rolled-back persistence context.
 */
public class DuplicateStepReviewException extends RuntimeException {

  private final String runnerExecutionPublicId;

  public DuplicateStepReviewException(String runnerExecutionPublicId) {
    super("Duplicate reviewer verdict for runner execution " + runnerExecutionPublicId);
    this.runnerExecutionPublicId = runnerExecutionPublicId;
  }

  public String runnerExecutionPublicId() {
    return runnerExecutionPublicId;
  }
}
