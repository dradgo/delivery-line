package org.dradgo.application.review.spi;

import java.util.Optional;
import org.dradgo.application.review.StepReviewSnapshot;

/**
 * Story 3d-2 (AC3, Task 7) — read SPI over {@code step_reviews}. Backs the {@code GET
 * …/reviewer-verdict} read leg; the application layer reads verdicts through this port (never the
 * adapter repository directly — ArchUnit application→adapters boundary), mirroring {@code
 * ApprovalReadPort}.
 */
public interface StepReviewReadPort {

  /** The latest non-archived advisory verdict for the run, or empty when none exists. */
  Optional<StepReviewSnapshot> findLatestForRun(String workflowRunPublicId);
}
