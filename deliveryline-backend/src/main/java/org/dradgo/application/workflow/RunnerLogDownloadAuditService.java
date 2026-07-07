package org.dradgo.application.workflow;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.dradgo.application.observability.MdcKeys;
import org.dradgo.application.workflow.spi.WorkflowEventRecord;
import org.dradgo.application.workflow.spi.WorkflowEventWritePort;
import org.dradgo.application.workflow.spi.WorkflowRunReadPort;
import org.dradgo.application.workflow.spi.WorkflowRunSnapshot;
import org.dradgo.domain.id.PublicIdPrefixes;
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.WorkflowEventDetailKeys;
import org.dradgo.domain.registry.WorkflowEventType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Story 4.4 (AC5) — appends the {@code audit.logDownloaded} governed read-access event when an
 * operator downloads a run's redacted runner log. Kept in {@code application.workflow} (not the
 * thin {@code RunnerExecutionController}) so the write stays out of the adapter and off the
 * read-only {@link WorkflowInspectionService}.
 *
 * <p>The event is NOT a workflow-state change (mirrors {@link
 * WorkflowArchiveService#appendArchiveEvent}): {@code priorState == resultingState ==
 * currentState}, {@code interventionMarker = true}, {@code failureCategory = null}, and the only
 * detail key is the already-allow-listed {@code runnerExecutionId}.
 *
 * <p><strong>Best-effort (OQ-2 provisional).</strong> The append runs in its own transaction; the
 * caller treats a failure as non-fatal (a failed audit append does NOT fail the download).
 */
@Service
public class RunnerLogDownloadAuditService {

  private static final Logger log = LoggerFactory.getLogger(RunnerLogDownloadAuditService.class);

  private final WorkflowRunReadPort workflowRunReadPort;
  private final WorkflowEventWritePort workflowEventWritePort;
  private final Clock clock;

  @org.springframework.beans.factory.annotation.Autowired
  public RunnerLogDownloadAuditService(
      WorkflowRunReadPort workflowRunReadPort, WorkflowEventWritePort workflowEventWritePort) {
    this(workflowRunReadPort, workflowEventWritePort, Clock.systemUTC());
  }

  RunnerLogDownloadAuditService(
      WorkflowRunReadPort workflowRunReadPort,
      WorkflowEventWritePort workflowEventWritePort,
      Clock clock) {
    this.workflowRunReadPort = Objects.requireNonNull(workflowRunReadPort, "workflowRunReadPort");
    this.workflowEventWritePort =
        Objects.requireNonNull(workflowEventWritePort, "workflowEventWritePort");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * Append {@code audit.logDownloaded} for a runner-log download. Resolves the run to stamp {@code
   * priorState == resultingState == currentState}. No-ops (with a WARN) when the run cannot be
   * resolved so a race with archival never surfaces as a 500 on the download path.
   *
   * @param workflowRunId the resolved run that owns the runner execution
   * @param runnerExecutionId the downloaded execution's id (the sole detail key)
   * @param actorIdentity the resolved operator identity
   * @param actorType the operator actor type (typically {@link ActorType#HUMAN})
   */
  @Transactional
  public void recordLogDownloaded(
      String workflowRunId, String runnerExecutionId, String actorIdentity, ActorType actorType) {
    PublicIdPrefixes.require(workflowRunId, PublicIdPrefixes.WORKFLOW_RUN);
    PublicIdPrefixes.require(runnerExecutionId, PublicIdPrefixes.RUNNER_EXECUTION);
    WorkflowRunSnapshot run = workflowRunReadPort.findByPublicId(workflowRunId).orElse(null);
    if (run == null) {
      log.warn(
          "audit.logDownloaded append skipped workflowRunId={} runnerExecutionId={} reason=run_not_found",
          MdcKeys.sanitizeForLog(workflowRunId),
          MdcKeys.sanitizeForLog(runnerExecutionId));
      return;
    }
    Map<String, Object> details = new LinkedHashMap<>();
    details.put(WorkflowEventDetailKeys.RUNNER_EXECUTION_ID, runnerExecutionId);
    workflowEventWritePort.append(
        new WorkflowEventRecord(
            PublicIdPrefixes.WORKFLOW_EVENT.next(),
            run.publicId(),
            WorkflowEventType.AUDIT_LOG_DOWNLOADED,
            run.currentState(),
            run.currentState(),
            actorIdentity,
            actorType == null ? ActorType.HUMAN : actorType,
            null,
            null,
            true,
            OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC),
            details));
    log.info(
        "audit.logDownloaded appended workflowRunId={} runnerExecutionId={} actorIdentity={}",
        MdcKeys.sanitizeForLog(workflowRunId),
        MdcKeys.sanitizeForLog(runnerExecutionId),
        MdcKeys.sanitizeForLog(actorIdentity));
  }
}
