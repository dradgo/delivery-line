package org.dradgo.application.runner;

import java.util.Optional;
import org.dradgo.application.observability.MdcKeys;
import org.dradgo.application.runner.spi.ProviderUsageSnapshotReadPort;
import org.dradgo.domain.id.PublicIdPrefixes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Story 3d-7 (FR69, AC5) — read service surfacing the latest per-credential provider usage/limit
 * snapshot for a run. Backs the REST endpoint + CLI. Read-only and tolerant of absence: a run that
 * never captured a snapshot (legacy / default runner, or no run yet) returns {@link
 * Optional#empty()} so the surface degrades gracefully.
 */
@Service
public class ProviderUsageStatusService {

  private static final Logger log = LoggerFactory.getLogger(ProviderUsageStatusService.class);

  private final ProviderUsageSnapshotReadPort readPort;

  public ProviderUsageStatusService(ProviderUsageSnapshotReadPort readPort) {
    this.readPort = readPort;
  }

  /**
   * Latest non-archived provider usage snapshot for the run, if any. Validates the run id prefix so
   * a malformed id surfaces the standard {@code INVALID_ID_PREFIX} Problem Details.
   */
  public Optional<ProviderUsageSnapshotView> getLatestForRun(String workflowRunId) {
    PublicIdPrefixes.require(workflowRunId, PublicIdPrefixes.WORKFLOW_RUN);
    Optional<ProviderUsageSnapshotView> snapshot =
        readPort.findLatestByWorkflowRunId(workflowRunId);
    log.info(
        "provider-usage status read workflowRunId={} present={} signalState={}",
        MdcKeys.sanitizeForLog(workflowRunId),
        snapshot.isPresent(),
        snapshot.map(ProviderUsageSnapshotView::signalState).orElse("<none>"));
    return snapshot;
  }
}
