package org.dradgo.application.workflow;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.dradgo.application.artifact.ActorContext;
import org.dradgo.application.observability.MdcKeys;
import org.dradgo.application.runner.spi.RunnerConsoleStreamPort;
import org.dradgo.application.runner.spi.RunnerExecutionEventPort;
import org.dradgo.application.runner.spi.RunnerExecutionRecordPort;
import org.dradgo.application.runner.spi.RunnerExecutionSnapshot;
import org.dradgo.application.security.RedactionPolicyService;
import org.dradgo.domain.id.PublicIdPrefixes;
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.DataClassification;
import org.dradgo.domain.registry.RunnerExecutionStatus;
import org.dradgo.domain.registry.WorkflowEventDetailKeys;
import org.dradgo.domain.registry.WorkflowEventType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Story 3d-6 (FR68, ADR 0025) — orchestrates the Read-only Diagnostic Console: a LIVE-ONLY,
 * read-only attach to a workflow run's latest runner-execution container, streaming its stdio
 * through a {@link ConsoleStreamSink} with <b>best-effort</b> per-chunk redaction (AC2/AC7), and
 * recording the session as governed history (AC3: {@code console.opened} on open, {@code
 * console.closed} on close).
 *
 * <p>Near-symmetric twin of {@link StepLogStreamService} with three deltas (this is the console,
 * not the log follow):
 *
 * <ul>
 *   <li><b>Attaches the live container pty</b> via {@link RunnerConsoleStreamPort} (DD-2), not the
 *       demuxed log stream.
 *   <li><b>LIVE-ONLY (DD-3):</b> a terminal / absent rex is rejected with {@code console-not-live};
 *       there is NO finished-mode fallback (the finished-state surface is the 3d-5 log viewer).
 *   <li><b>Governed-history (AC3):</b> a live attach appends {@code console.opened}; the close path
 *       appends {@code console.closed}. Console I/O is NOT durably stored — only this session
 *       metadata (the two events, DD-4 detail keys only).
 * </ul>
 *
 * <p>Lives under {@code application.workflow} (alongside {@link WorkflowInspectionService}) — NOT
 * {@code application.runner} as the story's structure note suggested — because the REST controller
 * depends on it and the {@code rest_controllers_stay_thin_and_avoid_spi_or_persistence_or_runner}
 * ArchUnit rule forbids a {@code adapters.rest} → {@code application.runner} edge (story 3d-5
 * placed {@link StepLogStreamService} here for the identical reason).
 *
 * <p><b>Read-only (AC7 / Trap T1/T6).</b> This service performs NO write to {@code runner-logs/}
 * and NO mutation of {@code runner_executions}; raw chunks stay method-local (lambda params) and
 * are redacted before reaching the sink — no field holds a raw chunk. The attach is opened WITHOUT
 * stdin by the adapter, so no input path exists end-to-end.
 */
@Service
public class DiagnosticConsoleService {

  private static final Logger log = LoggerFactory.getLogger(DiagnosticConsoleService.class);

  private static final String REDACTION_PLACEHOLDER_PREFIX = "[REDACTED_";

  private final RunnerConsoleStreamPort runnerConsoleStreamPort;
  private final RunnerExecutionRecordPort runnerExecutionRecordPort;
  private final RedactionPolicyService redactionPolicyService;
  private final RunnerExecutionEventPort runnerExecutionEventPort;
  private final WorkflowInspectionService workflowInspectionService;
  private final Clock clock;

  @Autowired
  public DiagnosticConsoleService(
      RunnerConsoleStreamPort runnerConsoleStreamPort,
      RunnerExecutionRecordPort runnerExecutionRecordPort,
      RedactionPolicyService redactionPolicyService,
      RunnerExecutionEventPort runnerExecutionEventPort,
      WorkflowInspectionService workflowInspectionService) {
    this(
        runnerConsoleStreamPort,
        runnerExecutionRecordPort,
        redactionPolicyService,
        runnerExecutionEventPort,
        workflowInspectionService,
        Clock.systemUTC());
  }

  // Package-private clock-injecting overload for deterministic tests (no Clock bean is published).
  DiagnosticConsoleService(
      RunnerConsoleStreamPort runnerConsoleStreamPort,
      RunnerExecutionRecordPort runnerExecutionRecordPort,
      RedactionPolicyService redactionPolicyService,
      RunnerExecutionEventPort runnerExecutionEventPort,
      WorkflowInspectionService workflowInspectionService,
      Clock clock) {
    this.runnerConsoleStreamPort =
        Objects.requireNonNull(runnerConsoleStreamPort, "runnerConsoleStreamPort");
    this.runnerExecutionRecordPort =
        Objects.requireNonNull(runnerExecutionRecordPort, "runnerExecutionRecordPort");
    this.redactionPolicyService =
        Objects.requireNonNull(redactionPolicyService, "redactionPolicyService");
    this.runnerExecutionEventPort =
        Objects.requireNonNull(runnerExecutionEventPort, "runnerExecutionEventPort");
    this.workflowInspectionService =
        Objects.requireNonNull(workflowInspectionService, "workflowInspectionService");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * Resolve the run's latest runner execution and, if it is LIVE, attach a read-only console
   * streaming its stdio through {@code sink}; otherwise reject with {@code console-not-live} (DD-3
   * — no finished-mode fallback). Returns an {@link AutoCloseable} the caller MUST close when the
   * client disconnects / the emitter terminates (Trap T3): for a live attach it appends {@code
   * console.closed} on close then releases the subscription; for the rejected / no-op cases it is a
   * no-op (no {@code console.opened} was appended). Best-effort throughout — failures surface via
   * {@code sink.onError}/{@code sink.onEnd}, not exceptions.
   */
  public AutoCloseable openConsole(String workflowRunId, String actorRole, ConsoleStreamSink sink) {
    PublicIdPrefixes.require(workflowRunId, PublicIdPrefixes.WORKFLOW_RUN);
    String priorRunMdc = MdcKeys.beginScope(MdcKeys.WORKFLOW_RUN_ID, workflowRunId);
    try {
      Optional<String> latestRex =
          workflowInspectionService.findLatestRunnerExecutionId(workflowRunId);
      if (latestRex.isEmpty()) {
        log.info(
            "diagnostic console decision workflowRunId={} decision=not-live reason=no-runner-execution",
            workflowRunId);
        rejectNotLive(sink);
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
        if (!isLive(status)) {
          // LIVE-ONLY (DD-3 / Trap T4): a terminal/absent execution has no container to attach. NO
          // console.opened event is appended for a rejected attach (AC3).
          log.info(
              "diagnostic console decision workflowRunId={} runnerExecutionId={} status={} decision=not-live",
              workflowRunId,
              rex,
              status == null ? "<unknown>" : status.value());
          rejectNotLive(sink);
          return noop();
        }
        return attachLive(workflowRunId, rex, actorRole, sink);
      } finally {
        MdcKeys.endScope(MdcKeys.RUNNER_EXECUTION_ID, priorRexMdc);
      }
    } finally {
      MdcKeys.endScope(MdcKeys.WORKFLOW_RUN_ID, priorRunMdc);
    }
  }

  private AutoCloseable attachLive(
      String workflowRunId, String rex, String actorRole, ConsoleStreamSink sink) {
    AtomicLong seq = new AtomicLong();
    // AC7 — best-effort redaction COUNT only (never content); logged on session end.
    AtomicLong redactionCount = new AtomicLong();
    // Guards single-append of console.closed across the two close triggers (container-exited onEnd
    // callback vs the caller closing the returned handle). Mirrors a once-latch (DD-5).
    AtomicBoolean closed = new AtomicBoolean(false);
    ActorContext actor = resolveActor(actorRole);

    // Raw chunks arrive here as method-local lambda params ONLY; each is redacted (best-effort)
    // before reaching the sink — no field ever holds a raw chunk (Trap T1).
    RunnerConsoleStreamPort.ConsoleSubscription subscription =
        runnerConsoleStreamPort.attachConsole(
            rex,
            (stream, rawChunk) -> {
              String redactedChunk;
              try {
                redactedChunk =
                    redactionPolicyService
                        .redact(rawChunk, DataClassification.LOCAL_ONLY.value())
                        .sanitizedText();
              } catch (RuntimeException redactionFailure) {
                // Best-effort redaction (AC2): a single chunk the redactor cannot process MUST NOT
                // abort the whole attach and MUST NOT leak the raw chunk. Substitute a safe
                // placeholder and keep streaming — log the cause only, NEVER the content (AC7).
                log.warn(
                    "diagnostic console redaction failed (chunk replaced, best-effort) runnerExecutionId={} cause={}",
                    rex,
                    redactionFailure.toString());
                redactedChunk = "[REDACTION_ERROR]";
              }
              redactionCount.addAndGet(countPlaceholders(redactedChunk));
              sink.onChunk(stream, redactedChunk, seq.getAndIncrement());
            },
            () -> {
              if (closed.compareAndSet(false, true)) {
                appendConsoleClosed(workflowRunId, rex, actor, "container-exited");
                log.info(
                    "diagnostic console session ended workflowRunId={} runnerExecutionId={} chunkCount={} redactionCount={} reason=container-exited",
                    workflowRunId,
                    rex,
                    seq.get(),
                    redactionCount.get());
                sink.onEnd("container-exited");
              }
            });

    if (!subscription.isLive()) {
      // Status said live but no container survives — LIVE-ONLY reject (no fallback, no event).
      subscription.close();
      log.info(
          "diagnostic console decision workflowRunId={} runnerExecutionId={} decision=not-live (no live container at attach)",
          workflowRunId,
          rex);
      rejectNotLive(sink);
      return noop();
    }

    // The container can exit during attach setup: the onEnd callback above then wins the `closed`
    // latch, appends console.closed and ends the sink. Do NOT append a console.opened for an
    // already-ended session (that would invert the AC3 open/close ordering) and do NOT re-surface a
    // live phase — just release the (already-finished) subscription.
    if (closed.get()) {
      subscription.close();
      log.info(
          "diagnostic console decision workflowRunId={} runnerExecutionId={} decision=not-live (container exited during attach setup)",
          workflowRunId,
          rex);
      return noop();
    }

    // Live attach engaged → record the governed session-open event (AC3) and surface the live
    // phase. Guard the open-event append (Trap T3): if it throws, the live subscription MUST be
    // released here — the controller's catch unwinds before gate.install(handle) runs, so an
    // unreleased attach thread would leak. Claim the `closed` latch first so a racing
    // container-exit onEnd cannot later append an orphan console.closed for a session that never
    // opened, and surface the failure via the sink (the service contract is no-throw, best-effort).
    try {
      appendConsoleOpened(workflowRunId, rex, actor);
    } catch (RuntimeException openFailure) {
      closed.set(true);
      log.warn(
          "diagnostic console open-event append failed (releasing attach) workflowRunId={} runnerExecutionId={} cause={}",
          workflowRunId,
          rex,
          openFailure.toString());
      subscription.close();
      sink.onError("console-failed");
      sink.onEnd("console-failed");
      return noop();
    }
    sink.onStatus("live", rex);
    log.info(
        "diagnostic console opened workflowRunId={} runnerExecutionId={} actorRole={} decision=live",
        workflowRunId,
        rex,
        MdcKeys.sanitizeForLog(actorRole));

    return () -> {
      if (closed.compareAndSet(false, true)) {
        appendConsoleClosed(workflowRunId, rex, actor, "session-closed");
        log.info(
            "diagnostic console session closed workflowRunId={} runnerExecutionId={} chunkCount={} redactionCount={} reason=session-closed",
            workflowRunId,
            rex,
            seq.get(),
            redactionCount.get());
      }
      subscription.close();
    };
  }

  private static void rejectNotLive(ConsoleStreamSink sink) {
    sink.onError("console-not-live");
    sink.onEnd("not-live");
  }

  private void appendConsoleOpened(String workflowRunId, String rex, ActorContext actor) {
    runnerExecutionEventPort.append(
        workflowRunId,
        WorkflowEventType.CONSOLE_OPENED,
        actor,
        "diagnostic_console_opened",
        null,
        OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC),
        consoleEventDetails(workflowRunId, rex));
  }

  /**
   * Append {@code console.closed}. Invoked OFF the request thread (the controller's terminal
   * callback or the docker attach's onEnd) where there is no ambient transaction; the underlying
   * {@code WorkflowEventWritePort} write self-transacts (Spring Data {@code saveAndFlush}), so the
   * append commits independently and never fails on an absent/committed tx (DD-5 / Trap T10 /
   * {@code post-commit-hook-needs-requires-new}). Best-effort: a close-path append failure is
   * logged and swallowed — it must never break session teardown.
   */
  private void appendConsoleClosed(
      String workflowRunId, String rex, ActorContext actor, String reason) {
    try {
      runnerExecutionEventPort.append(
          workflowRunId,
          WorkflowEventType.CONSOLE_CLOSED,
          actor,
          reason,
          null,
          OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC),
          consoleEventDetails(workflowRunId, rex));
    } catch (RuntimeException appendFailure) {
      log.warn(
          "diagnostic console close-event append failed workflowRunId={} runnerExecutionId={} cause={}",
          workflowRunId,
          rex,
          appendFailure.toString());
    }
  }

  /**
   * DD-4 — carry ONLY the already-allow-listed detail keys (no {@code WorkflowEventDetailKeys}
   * fan-out). Open/close pairing for the single-operator MVP is by rex + timestamp ordering.
   */
  private static Map<String, Object> consoleEventDetails(String workflowRunId, String rex) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put(WorkflowEventDetailKeys.RUNNER_EXECUTION_ID, rex);
    details.put(WorkflowEventDetailKeys.WORKFLOW_RUN_ID, workflowRunId);
    return details;
  }

  /**
   * The console session actor: the local operator. Identity is the resolved actor role (the gate
   * only offers the console to {@code workflow_owner}); a blank role falls back to {@code operator}
   * so the non-blank {@link ActorContext} invariant always holds.
   */
  private static ActorContext resolveActor(String actorRole) {
    String identity = actorRole == null || actorRole.isBlank() ? "operator" : actorRole.strip();
    return new ActorContext(identity, ActorType.HUMAN, null);
  }

  /** Count redaction placeholders in a sanitized chunk — COUNT only, never the content (AC7). */
  private static int countPlaceholders(String sanitizedChunk) {
    if (sanitizedChunk == null || sanitizedChunk.isEmpty()) {
      return 0;
    }
    int count = 0;
    int from = 0;
    int index;
    while ((index = sanitizedChunk.indexOf(REDACTION_PLACEHOLDER_PREFIX, from)) >= 0) {
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
      // Nothing to release: rejected / no-runner-execution paths hold no live resources and
      // appended no console.opened (so there is no console.closed to pair).
    };
  }
}
