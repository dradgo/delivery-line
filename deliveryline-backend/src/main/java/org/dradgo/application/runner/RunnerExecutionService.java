package org.dradgo.application.runner;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;
import org.dradgo.application.runner.spi.RunnerExecutionRecordPort;
import org.dradgo.application.runner.spi.RunnerExecutionSnapshot;
import org.dradgo.domain.registry.FailureCategory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Thin application service over {@link RunnerExecutionRecordPort}. The state-machine guard lives in
 * the persistence adapter (mirroring 1.12 C4 / R4); this service is the named seam call sites use
 * rather than touching the port directly.
 */
@Service
public class RunnerExecutionService {

  private static final Logger log = LoggerFactory.getLogger(RunnerExecutionService.class);

  private final RunnerExecutionRecordPort recordPort;
  private final Clock clock;

  @org.springframework.beans.factory.annotation.Autowired
  public RunnerExecutionService(RunnerExecutionRecordPort recordPort) {
    this(recordPort, Clock.systemUTC());
  }

  RunnerExecutionService(RunnerExecutionRecordPort recordPort, Clock clock) {
    this.recordPort = Objects.requireNonNull(recordPort, "recordPort");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  public RunnerExecutionSnapshot markRunning(String runnerExecutionId) {
    OffsetDateTime now = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
    RunnerExecutionSnapshot updated = recordPort.transitionToRunning(runnerExecutionId, now);
    log.info(
        "markRunning runnerExecutionId={} status={}", runnerExecutionId, updated.status().value());
    return updated;
  }

  public Optional<RunnerExecutionSnapshot> findByPublicId(String runnerExecutionId) {
    return recordPort.findByPublicId(runnerExecutionId);
  }

  public RunnerExecutionSnapshot touchActivity(
      String runnerExecutionId, Duration staleTimeoutWindow) {
    OffsetDateTime now = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
    return touchActivity(runnerExecutionId, now, staleTimeoutWindow);
  }

  public RunnerExecutionSnapshot touchActivity(
      String runnerExecutionId, OffsetDateTime activityAt, Duration staleTimeoutWindow) {
    Objects.requireNonNull(activityAt, "activityAt");
    Objects.requireNonNull(staleTimeoutWindow, "staleTimeoutWindow");
    return recordPort.touchActivity(runnerExecutionId, activityAt, staleTimeoutWindow);
  }

  public RunnerExecutionSnapshot recordCompleted(String runnerExecutionId) {
    OffsetDateTime now = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
    RunnerExecutionSnapshot updated = recordPort.markCompleted(runnerExecutionId, now);
    log.info(
        "recordCompleted runnerExecutionId={} status={}",
        runnerExecutionId,
        updated.status().value());
    return updated;
  }

  public RunnerExecutionSnapshot recordFailed(
      String runnerExecutionId, FailureCategory failureCategory) {
    Objects.requireNonNull(failureCategory, "failureCategory");
    OffsetDateTime now = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
    RunnerExecutionSnapshot updated =
        recordPort.markFailed(runnerExecutionId, failureCategory, now);
    log.warn(
        "recordFailed runnerExecutionId={} status={} failureCategory={}",
        runnerExecutionId,
        updated.status().value(),
        failureCategory.value());
    return updated;
  }

  public RunnerExecutionSnapshot recordTimedOut(String runnerExecutionId) {
    OffsetDateTime now = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
    RunnerExecutionSnapshot updated = recordPort.markTimedOut(runnerExecutionId, now);
    log.warn(
        "recordTimedOut runnerExecutionId={} status={}",
        runnerExecutionId,
        updated.status().value());
    return updated;
  }

  public RunnerExecutionSnapshot recordOrphaned(String runnerExecutionId) {
    OffsetDateTime now = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
    RunnerExecutionSnapshot updated = recordPort.markOrphaned(runnerExecutionId, now);
    log.warn(
        "recordOrphaned runnerExecutionId={} status={}",
        runnerExecutionId,
        updated.status().value());
    return updated;
  }

  /**
   * Story 3d-2 (code-review D1) — the reviewed-artifact pin a {@link
   * org.dradgo.domain.registry.RunnerStage#REVIEW} execution carries (set at enqueue), or empty
   * when none was stored. The REVIEW harvest reuses the pin so the verdict references the exact
   * artifact the reviewer saw, falling back to re-deriving when absent.
   */
  public Optional<RunnerExecutionRecordPort.ReviewedArtifactPin> findReviewedArtifactPin(
      String runnerExecutionId) {
    return recordPort.findReviewedArtifactPin(runnerExecutionId);
  }

  /**
   * Story 3g-3 (FR74) — persist the per-execution token counts onto the row (V31 columns).
   * Metadata-only: never changes {@code status}, tolerates an already-terminal row. Used by the
   * REVIEW harvest (the reviewer emits a review-result.v1, not a runner-result.v1, so its usage
   * cannot flow through the broker's {@code captureTokenUsage}).
   */
  public RunnerExecutionSnapshot recordTokenUsage(
      String runnerExecutionId, Integer inputTokens, Integer outputTokens, Integer totalTokens) {
    return recordPort.recordTokenUsage(runnerExecutionId, inputTokens, outputTokens, totalTokens);
  }

  /**
   * Story 3h-2 (AC6) — persist the severity-classified lint findings (already-serialized,
   * already-redacted JSON) onto the LINT execution row's {@code lint_findings} jsonb column.
   * Metadata-only: never changes {@code status}, tolerates a still-running row.
   */
  public RunnerExecutionSnapshot recordLintFindings(
      String runnerExecutionId, String lintFindingsJson) {
    RunnerExecutionSnapshot updated =
        recordPort.recordLintFindings(runnerExecutionId, lintFindingsJson);
    log.info(
        "recordLintFindings runnerExecutionId={} payloadPresent={}",
        runnerExecutionId,
        lintFindingsJson != null);
    return updated;
  }

  /**
   * Story 3.6 AC3/AC6 / Trap T10 — persist the durable redacted-log capture reference + metrics
   * onto the row. Metadata-only: never changes {@code status}, tolerates an already-terminal row.
   */
  public RunnerExecutionSnapshot recordRawOutput(
      String runnerExecutionId, CapturedLogs capturedLogs) {
    Objects.requireNonNull(capturedLogs, "capturedLogs");
    RunnerExecutionSnapshot updated =
        recordPort.recordRawOutput(
            runnerExecutionId,
            capturedLogs.referencePath(),
            capturedLogs.classification(),
            capturedLogs.byteSize(),
            capturedLogs.redactionCount());
    log.info(
        "recordRawOutput runnerExecutionId={} classification={} redactionCount={} byteSize={}",
        runnerExecutionId,
        capturedLogs.classification().value(),
        capturedLogs.redactionCount(),
        capturedLogs.byteSize());
    return updated;
  }
}
