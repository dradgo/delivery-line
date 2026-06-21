package org.dradgo.application.runner;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.dradgo.application.observability.MdcKeys;
import org.dradgo.application.runner.spi.RunnerExecutionRecordPort;
import org.dradgo.application.runner.spi.RunnerExecutionSnapshot;
import org.dradgo.application.runner.spi.RunnerLogStore;
import org.dradgo.application.runner.spi.RunnerLogStreamPort;
import org.dradgo.application.security.RedactionPolicyService;
import org.dradgo.application.workflow.WorkflowInspectionService;
import org.dradgo.domain.id.PublicIdPrefixes;
import org.dradgo.domain.registry.DataClassification;
import org.dradgo.domain.registry.RunnerExecutionStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Story 3d-5 (FR65, ADR 0025) — orchestrates the Step Execution Log Viewer's ONE backend stream
 * covering BOTH states for a workflow run's latest runner execution:
 *
 * <ul>
 *   <li><b>Live</b> (status PENDING/RUNNING/QUEUED): follow the container via {@link
 *       RunnerLogStreamPort}, applying <b>best-effort</b> per-line redaction before the line
 *       reaches the sink (AC2 — authoritative redaction remains the story-3.6 persisted scan).
 *   <li><b>Finished</b> (terminal, or no live container): replay the ALREADY-redacted persisted log
 *       from the story-3.6 store ({@link RunnerLogStore#readRedacted}) — NO re-redaction (Trap T4).
 * </ul>
 *
 * <p><b>Persists nothing (ADR 0025 D4 / Trap T6).</b> This service reads only; it never writes to
 * {@code runner-logs/} nor mutates {@code runner_executions}. Raw live lines stay method-local
 * (lambda params) and are redacted before reaching the sink — no field holds a raw line (Trap T1).
 * The Spring {@code SseEmitter} stays in the REST adapter; this service speaks {@link
 * LogStreamSink}.
 */
@Service
public class StepLogStreamService {

  private static final Logger log = LoggerFactory.getLogger(StepLogStreamService.class);

  private final RunnerLogStreamPort runnerLogStreamPort;
  private final RunnerLogStore runnerLogStore;
  private final RunnerExecutionRecordPort runnerExecutionRecordPort;
  private final RedactionPolicyService redactionPolicyService;
  private final WorkflowInspectionService workflowInspectionService;

  public StepLogStreamService(
      RunnerLogStreamPort runnerLogStreamPort,
      RunnerLogStore runnerLogStore,
      RunnerExecutionRecordPort runnerExecutionRecordPort,
      RedactionPolicyService redactionPolicyService,
      WorkflowInspectionService workflowInspectionService) {
    this.runnerLogStreamPort = runnerLogStreamPort;
    this.runnerLogStore = runnerLogStore;
    this.runnerExecutionRecordPort = runnerExecutionRecordPort;
    this.redactionPolicyService = redactionPolicyService;
    this.workflowInspectionService = workflowInspectionService;
  }

  /**
   * Resolve the run's latest runner execution and stream its logs through {@code sink}. Returns an
   * {@link AutoCloseable} the caller MUST close when the client disconnects / the emitter
   * terminates (Trap T3): for live mode it is the active follow subscription; for finished / no-op
   * cases it is a no-op. Best-effort throughout — failures surface via {@code sink.onError}/{@code
   * sink.onEnd}, not exceptions.
   */
  public AutoCloseable streamRunnerLogs(String workflowRunId, LogStreamSink sink) {
    PublicIdPrefixes.require(workflowRunId, PublicIdPrefixes.WORKFLOW_RUN);
    String priorRunMdc = MdcKeys.beginScope(MdcKeys.WORKFLOW_RUN_ID, workflowRunId);
    try {
      Optional<String> latestRex =
          workflowInspectionService.findLatestRunnerExecutionId(workflowRunId);
      if (latestRex.isEmpty()) {
        log.info(
            "step log stream decision workflowRunId={} decision=no-runner-execution",
            workflowRunId);
        sink.onEnd("no-runner-execution");
        return noop();
      }
      String rex = latestRex.get();
      String priorRexMdc = MdcKeys.beginScope(MdcKeys.RUNNER_EXECUTION_ID, rex);
      try {
        RunnerExecutionStatus status =
            runnerExecutionRecordPort
                .findByPublicId(rex)
                .map(RunnerExecutionSnapshot::status)
                .orElse(null);
        if (isLive(status)) {
          return streamLive(workflowRunId, rex, sink);
        }
        log.info(
            "step log stream decision workflowRunId={} runnerExecutionId={} status={} decision=finished",
            workflowRunId,
            rex,
            status == null ? "<unknown>" : status.value());
        replayFinished(rex, sink);
        return noop();
      } finally {
        MdcKeys.endScope(MdcKeys.RUNNER_EXECUTION_ID, priorRexMdc);
      }
    } finally {
      MdcKeys.endScope(MdcKeys.WORKFLOW_RUN_ID, priorRunMdc);
    }
  }

  private AutoCloseable streamLive(String workflowRunId, String rex, LogStreamSink sink) {
    AtomicLong seq = new AtomicLong();
    // AC7 — best-effort redaction COUNT only (never content); logged on stream end.
    AtomicLong redactionCount = new AtomicLong();
    // Raw lines arrive here as method-local lambda params ONLY; each is redacted (best-effort)
    // before reaching the sink — no field ever holds a raw line (Trap T1).
    RunnerLogStreamPort.LiveLogSubscription subscription =
        runnerLogStreamPort.followLiveLogs(
            rex,
            (stream, rawLine) -> {
              String redactedLine;
              try {
                redactedLine =
                    redactionPolicyService
                        .redact(rawLine, DataClassification.LOCAL_ONLY.value())
                        .sanitizedText();
              } catch (RuntimeException redactionFailure) {
                // Best-effort redaction (AC2): a single line the redactor cannot process MUST NOT
                // abort the whole live follow, and MUST NOT leak the raw line. Substitute a safe
                // placeholder and keep following — log the cause only, NEVER the content (AC7).
                log.warn(
                    "step log stream redaction failed (line replaced, best-effort) runnerExecutionId={} cause={}",
                    rex,
                    redactionFailure.toString());
                redactedLine = "[REDACTION_ERROR]";
              }
              redactionCount.addAndGet(countPlaceholders(redactedLine));
              sink.onLine(stream, redactedLine, seq.getAndIncrement());
            },
            () -> {
              log.info(
                  "step log stream live ended workflowRunId={} runnerExecutionId={} lineCount={} redactionCount={} reason=container-exited",
                  workflowRunId,
                  rex,
                  seq.get(),
                  redactionCount.get());
              sink.onEnd("container-exited");
            });
    if (!subscription.isLive()) {
      // Status said live but no container survives — fall back to finished mode (best-effort).
      subscription.close();
      log.info(
          "step log stream decision workflowRunId={} runnerExecutionId={} decision=finished-fallback (no live container)",
          workflowRunId,
          rex);
      replayFinished(rex, sink);
      return noop();
    }
    sink.onStatus("live", rex);
    log.info(
        "step log stream decision workflowRunId={} runnerExecutionId={} decision=live",
        workflowRunId,
        rex);
    return subscription;
  }

  private void replayFinished(String rex, LogStreamSink sink) {
    Optional<RedactedRunnerLog> captured = runnerLogStore.readRedacted(rex);
    if (captured.isEmpty()) {
      log.info("step log stream finished replay runnerExecutionId={} reason=no-captured-log", rex);
      sink.onStatus("finished", rex);
      sink.onEnd("no-captured-log");
      return;
    }
    sink.onStatus("finished", rex);
    RedactedRunnerLog redacted = captured.get();
    // The bytes are already authoritatively redacted (story 3.6) — replay verbatim, NEVER re-redact
    // (Trap T4).
    long seq = 0;
    seq = replayStream("stdout", redacted.stdout(), sink, seq);
    replayStream("stderr", redacted.stderr(), sink, seq);
    log.info(
        "step log stream finished replay complete runnerExecutionId={} truncated={}",
        rex,
        redacted.truncated());
    sink.onEnd("finished-replay-complete");
  }

  private static long replayStream(String stream, String text, LogStreamSink sink, long startSeq) {
    if (text == null || text.isEmpty()) {
      return startSeq;
    }
    long seq = startSeq;
    // Split into lines WITHOUT a trailing-empty-element (split with -1 would emit a spurious blank
    // line for a text ending in \n). The replayed content is already redacted.
    String[] lines = text.split("\n", -1);
    int lineCount = lines.length;
    // Drop a single trailing empty element produced by a terminal newline.
    if (lineCount > 0 && lines[lineCount - 1].isEmpty()) {
      lineCount--;
    }
    for (int i = 0; i < lineCount; i++) {
      String line = lines[i];
      if (!line.isEmpty() && line.charAt(line.length() - 1) == '\r') {
        line = line.substring(0, line.length() - 1);
      }
      sink.onLine(stream, line, seq++);
    }
    return seq;
  }

  private static final String REDACTION_PLACEHOLDER_PREFIX = "[REDACTED_";

  /** Count redaction placeholders in a sanitized line — COUNT only, never the content (AC7). */
  private static int countPlaceholders(String sanitizedLine) {
    if (sanitizedLine == null || sanitizedLine.isEmpty()) {
      return 0;
    }
    int count = 0;
    int from = 0;
    int index;
    while ((index = sanitizedLine.indexOf(REDACTION_PLACEHOLDER_PREFIX, from)) >= 0) {
      count++;
      from = index + REDACTION_PLACEHOLDER_PREFIX.length();
    }
    return count;
  }

  private static boolean isLive(RunnerExecutionStatus status) {
    return status == RunnerExecutionStatus.PENDING
        || status == RunnerExecutionStatus.RUNNING
        || status == RunnerExecutionStatus.QUEUED;
  }

  private static AutoCloseable noop() {
    return () -> {
      // Nothing to release: finished / no-runner-execution paths hold no live resources.
    };
  }
}
