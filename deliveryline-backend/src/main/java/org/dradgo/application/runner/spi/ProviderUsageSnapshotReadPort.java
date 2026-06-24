package org.dradgo.application.runner.spi;

import java.util.Optional;
import org.dradgo.application.runner.ProviderUsageSnapshotView;

/**
 * Story 3d-7 (FR69, AC5) — read SPI over the V24 {@code provider_usage_snapshots} table. Used by
 * the REST endpoint + CLI to surface the LATEST non-archived snapshot for a run. Implemented under
 * {@code adapters.persistence}.
 */
public interface ProviderUsageSnapshotReadPort {

  /** Load the latest non-archived provider usage snapshot for a run, if any has been captured. */
  Optional<ProviderUsageSnapshotView> findLatestByWorkflowRunId(String workflowRunId);

  /**
   * Story 3d-7 (AC6) — count active (non-archived) snapshots by signal state, backing the optional
   * observability gauge. Cheap aggregate read.
   */
  SignalStateCounts countActiveBySignalState();

  /** available vs not_exposed snapshot counts (non-archived). */
  record SignalStateCounts(long available, long notExposed) {
    public static final SignalStateCounts EMPTY = new SignalStateCounts(0L, 0L);
  }
}
