package org.dradgo.application.workflow.spi;

import java.util.Optional;
import org.dradgo.application.workflow.BatchSubmissionResult;

/**
 * Story 3.18 — read SPI over the {@code batch_submissions} table (V15 schema), used to reconstruct
 * the prior {@link BatchSubmissionResult} on an idempotent replay (AC9). The {@code result_json}
 * column carries the full per-ticket list (including rejected tickets, which have no {@code
 * runner_executions} row) so the replay returns a byte-identical body.
 */
public interface BatchSubmissionReadPort {

  /** Load a batch submission (and its per-ticket outcomes) by its {@code bat_} public id. */
  Optional<BatchSubmissionResult> findByPublicId(String publicId);
}
