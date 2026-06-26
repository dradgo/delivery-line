package org.dradgo.adapters.rest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import org.dradgo.application.observability.MdcKeys;
import org.dradgo.application.workflow.DiagnosticConsoleService;
import org.dradgo.application.workflow.WorkflowInspectionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Story 3d-6 (FR68, ADR 0025) — the SECOND streaming surface (after story 3d-5): a Server-Sent
 * Events endpoint that attaches a READ-ONLY diagnostic console to a workflow run's latest
 * runner-execution container while it is LIVE, streaming its stdio to the single local operator.
 *
 * <p><b>Read-only + LIVE-ONLY (AC2, ADR 0025 D1 / DD-1/DD-3).</b> The attach is opened WITHOUT
 * stdin (no input channel is wired end-to-end — the provable non-mutation guarantee); the console
 * rejects a finished/absent execution with a typed {@code console-not-live} end/error and never
 * engages an attach. There is NO host shell, NO write to the workspace, NO finished-mode fallback
 * (the finished-state diagnostic surface is the story 3d-5 log viewer).
 *
 * <p><b>Governed history (AC3).</b> Opening a console appends a {@code console.opened} event;
 * closing it appends {@code console.closed}. Console I/O is NOT durably stored — only this session
 * metadata.
 *
 * <p><b>Best-effort redaction (AC7).</b> Live chunks are redacted best-effort before they leave the
 * server; nothing the console shows changes persisted/exported content (the console never writes to
 * {@code runner-logs/} nor mutates {@code runner_executions}). The authoritative redaction
 * guarantee remains the story-3.6 persisted post-hoc scan, which the console does not touch.
 *
 * <p><b>Localhost-only (AC5).</b> Served only over the existing localhost binding ({@code
 * server.address=127.0.0.1} + {@code RestBindingGuard}, story 6.9) — this endpoint adds NO new
 * binding.
 *
 * <p><b>Server-side gating (AC4 / Trap T5).</b> Before attaching, the controller computes the run's
 * allowed actions (the same {@code WorkflowInspectionService} matrix backing {@code
 * /allowed-actions}) and serves an {@code error}+{@code end} event when {@code
 * open_diagnostic_console} is absent — the backend is the real guard, not just the frontend gate.
 *
 * <p>SSE events: {@code console} {@code {stream,chunk,seq}}, {@code status} {@code {phase,rex}},
 * {@code end} {@code {reason}}, {@code error} {@code {reason}}. The frontend consumes these via a
 * hand-written {@code EventSource} (SSE is not a typed REST call), so the endpoint is documented in
 * OpenAPI for the drift gate but has no generated client method.
 */
@RestController
@RequestMapping("/api/v1/workflows")
@Tag(name = "Workflows", description = "Inspect and command governed workflow runs.")
public class RunnerDiagnosticConsoleController {

  private static final Logger log =
      LoggerFactory.getLogger(RunnerDiagnosticConsoleController.class);

  /**
   * AC4 wire value of {@code AllowedAction.OPEN_DIAGNOSTIC_CONSOLE} (string-gated to keep the enum
   * in WorkflowInspectionService — ArchUnit allowed_action_derivation rule).
   */
  private static final String OPEN_DIAGNOSTIC_CONSOLE_ACTION = "open_diagnostic_console";

  // Max-session-duration cap; an attach that outlives this completes via onTimeout (the
  // subscription
  // is released, console.closed appended). 30 min is generous for a single operator diagnosing one
  // step. Dedicated pool (OQ-5) keeps the console lifecycle independent of the 3d-5 log stream.
  private static final long SESSION_TIMEOUT_MS = Duration.ofMinutes(30).toMillis();

  private final DiagnosticConsoleService diagnosticConsoleService;
  private final WorkflowInspectionService workflowInspectionService;
  // Dedicated bounded daemon executor for the (potentially blocking) attach so the servlet
  // container
  // thread is released immediately after returning the emitter. Single local operator → small pool.
  private final ExecutorService consoleExecutor =
      Executors.newFixedThreadPool(2, namedDaemonThreadFactory());

  public RunnerDiagnosticConsoleController(
      DiagnosticConsoleService diagnosticConsoleService,
      WorkflowInspectionService workflowInspectionService) {
    this.diagnosticConsoleService = diagnosticConsoleService;
    this.workflowInspectionService = workflowInspectionService;
  }

  @GetMapping(
      value = "/{workflowRunId}/diagnostic-console/stream",
      produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  @Operation(
      operationId = "streamDiagnosticConsole",
      summary = "Attach a read-only diagnostic console to a run's live runner execution",
      description =
          "Server-Sent Events stream of a READ-ONLY console attached to the run's latest runner "
              + "execution while it is LIVE. The attach is opened WITHOUT stdin — input is disabled "
              + "end-to-end (a pure streaming pty), which is the provable non-mutating guarantee "
              + "(ADR 0025 D1). LIVE-ONLY: a finished/absent execution is rejected with a typed "
              + "console-not-live end/error and no attach is engaged (the finished-state diagnostic "
              + "surface is the runner-logs viewer). Live chunks are redacted BEST-EFFORT; nothing "
              + "the console shows changes persisted/exported content (the console never writes to "
              + "runner-logs/ nor mutates runner_executions — the story-3.6 persisted scan remains "
              + "the authoritative redaction guarantee). Opening appends a console.opened governed "
              + "event and closing appends console.closed (session metadata only; console I/O is not "
              + "durably stored, ADR 0025 D4). Served only over the localhost-only binding to the "
              + "single local operator; gated by the open_diagnostic_console allowed-action "
              + "(EXECUTING or INVESTIGATING + workflow_owner). See story 3d-10's per-step-execution-control walkthrough "
              + "for the console-safety posture. Events: console {stream,chunk,seq}, status "
              + "{phase,rex}, end {reason}, error {reason}.")
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        description =
            "An event stream (text/event-stream). When open_diagnostic_console is not allowed for "
                + "the run's state/role, or the execution is not live, the stream carries a single "
                + "error+end event instead of console chunks."),
    @ApiResponse(
        responseCode = "400",
        description = "Malformed run id (INVALID_ID_PREFIX) or unrecognized actorRole.",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                schema = @Schema(implementation = ProblemDetailsResponse.class))),
    @ApiResponse(
        responseCode = "404",
        description = "No such run (RUN_NOT_FOUND).",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                schema = @Schema(implementation = ProblemDetailsResponse.class)))
  })
  public SseEmitter streamDiagnosticConsole(
      @Parameter(description = "Run public id, e.g. run_abc123.", example = "run_abc123")
          @PathVariable
          String workflowRunId,
      @Parameter(
              description =
                  "Actor role for action gating; defaults to product_reviewer when absent. The "
                      + "diagnostic console is offered ONLY to workflow_owner (the run owner / local "
                      + "operator), so a caller must pass actorRole=workflow_owner to open it.",
              example = "workflow_owner",
              schema =
                  @Schema(
                      type = "string",
                      allowableValues = {"product_reviewer", "workflow_owner", "developer"},
                      nullable = true))
          @RequestParam(name = "actorRole", required = false)
          String actorRole) {
    String normalizedActorRole = actorRole == null ? null : actorRole.strip();
    String correlationId = MDC.get(MdcKeys.CORRELATION_ID);
    SseEmitter emitter = new SseEmitter(SESSION_TIMEOUT_MS);
    SseConsoleStreamSink sink = new SseConsoleStreamSink(emitter);

    // Server-side gating (AC4 / Trap T5). getAllowedActions resolves the run + role (throwing the
    // standard RUN_NOT_FOUND / INVALID_ID_PREFIX / UNKNOWN_ACTOR_ROLE Problem Details BEFORE the
    // attach). AllowedActionsResponse is the only adapters.rest class permitted to translate the
    // typed AllowedAction list to wire strings, so the gate reads the string array here.
    boolean allowed =
        AllowedActionsResponse.from(
                workflowInspectionService.getAllowedActions(workflowRunId, normalizedActorRole))
            .actions()
            .contains(OPEN_DIAGNOSTIC_CONSOLE_ACTION);
    log.info(
        "REST diagnostic-console open workflowRunId={} actorRole={} allowed={}",
        MdcKeys.sanitizeForLog(workflowRunId),
        MdcKeys.sanitizeForLog(normalizedActorRole),
        allowed);
    if (!allowed) {
      log.warn(
          "REST diagnostic-console denied workflowRunId={} reason=open_diagnostic_console_not_allowed",
          MdcKeys.sanitizeForLog(workflowRunId));
      sink.onError("open_diagnostic_console_not_allowed");
      sink.onEnd("forbidden");
      return emitter;
    }

    SubscriptionGate gate = new SubscriptionGate();
    emitter.onCompletion(() -> gate.close());
    emitter.onTimeout(
        () -> {
          log.warn(
              "REST diagnostic-console timeout workflowRunId={}",
              MdcKeys.sanitizeForLog(workflowRunId));
          gate.close();
          emitter.complete();
        });
    emitter.onError(
        throwable -> {
          log.warn(
              "REST diagnostic-console client-disconnect workflowRunId={} cause={}",
              MdcKeys.sanitizeForLog(workflowRunId),
              throwable == null ? "<none>" : throwable.toString());
          gate.close();
        });

    consoleExecutor.execute(
        () -> {
          String priorCorrelation =
              correlationId == null
                  ? null
                  : MdcKeys.beginScope(MdcKeys.CORRELATION_ID, correlationId);
          try {
            AutoCloseable handle =
                diagnosticConsoleService.openConsole(workflowRunId, normalizedActorRole, sink);
            gate.install(handle);
          } catch (RuntimeException openFailure) {
            log.warn(
                "REST diagnostic-console failed workflowRunId={} cause={}",
                MdcKeys.sanitizeForLog(workflowRunId),
                openFailure.toString());
            sink.onError("console-failed");
            sink.onEnd("console-failed");
          } finally {
            if (correlationId != null) {
              MdcKeys.endScope(MdcKeys.CORRELATION_ID, priorCorrelation);
            }
          }
        });
    return emitter;
  }

  @PreDestroy
  void shutdownExecutor() {
    consoleExecutor.shutdownNow();
  }

  private static ThreadFactory namedDaemonThreadFactory() {
    AtomicInteger counter = new AtomicInteger();
    return runnable -> {
      Thread thread = new Thread(runnable, "diagnostic-console-" + counter.incrementAndGet());
      thread.setDaemon(true);
      return thread;
    };
  }

  /**
   * Race-safe holder for the in-flight {@link AutoCloseable} console subscription. The executor
   * task {@link #install(AutoCloseable)}s the handle once the attach starts; an emitter terminal
   * callback {@link #close()}s it (which appends {@code console.closed}). If the client disconnects
   * BEFORE the handle is installed, {@code install} closes it immediately so no attach thread leaks
   * (Trap T3).
   */
  private static final class SubscriptionGate {

    private AutoCloseable handle;
    private boolean closed;

    synchronized void install(AutoCloseable incoming) {
      if (closed) {
        closeQuietly(incoming);
      } else {
        handle = incoming;
      }
    }

    synchronized void close() {
      closed = true;
      closeQuietly(handle);
      handle = null;
    }

    private static void closeQuietly(AutoCloseable closeable) {
      if (closeable == null) {
        return;
      }
      try {
        closeable.close();
      } catch (Exception ignored) {
        // Best-effort release; the underlying service/adapter logs its own close failures.
      }
    }
  }
}
