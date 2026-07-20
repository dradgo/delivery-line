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
import org.dradgo.application.workflow.StepLogStreamService;
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
 * Story 3d-5 (FR65, ADR 0025) — the FIRST streaming surface in the backend: a Server-Sent Events
 * endpoint that follows a workflow run's latest runner-execution logs LIVE (Docker {@code logs
 * --follow}) and, for a finished execution, replays the story-3.6 persisted post-hoc-redacted log.
 * One endpoint, both states (AC1).
 *
 * <p><b>Redaction posture (AC2).</b> The live stream applies <b>best-effort</b> per-line redaction;
 * the <b>authoritative</b> redaction guarantee remains the story-3.6 persisted post-hoc scan that
 * the finished mode replays. The stream is served only over the existing localhost-only REST
 * binding ({@code server.address=127.0.0.1} + {@code RestBindingGuard}, story 6.9) to the single
 * local operator — this endpoint adds NO new binding.
 *
 * <p><b>No new persisted store (AC3 / ADR 0025 D4).</b> Live streams from Docker; finished reads
 * the existing redacted files. Nothing is written; {@code runner_executions} is never mutated.
 *
 * <p><b>Server-side gating (AC6 / Trap T5).</b> Before opening the stream the controller computes
 * the run's allowed actions (the same {@code WorkflowInspectionService} matrix backing {@code
 * /allowed-actions}) and serves an {@code error}+{@code end} event when {@code view_runner_logs} is
 * absent — the backend is the real guard, not just the frontend gate.
 *
 * <p>SSE events: {@code log} {@code {stream,line,seq}}, {@code status} {@code {phase,rex}}, {@code
 * end} {@code {reason}}, {@code error} {@code {reason}}. The frontend consumes these via a
 * hand-written {@code EventSource} (SSE is not a typed REST call), so the endpoint is documented in
 * OpenAPI for the drift gate but has no generated client method.
 */
@RestController
@RequestMapping("/api/v1/workflows")
@Tag(name = "Workflows", description = "Inspect and command governed workflow runs.")
public class RunnerLogStreamController {

  private static final Logger log = LoggerFactory.getLogger(RunnerLogStreamController.class);

  /**
   * AC6 wire value of {@code AllowedAction.VIEW_RUNNER_LOGS} (string-gated to keep the enum in
   * WorkflowInspectionService — ArchUnit allowed_action_derivation rule).
   */
  private static final String VIEW_RUNNER_LOGS_ACTION = "view_runner_logs";

  // OQ-4 — max-stream-duration cap; a follow that outlives this completes via onTimeout (the
  // subscription is released). 30 min is generous for a single operator watching one step.
  private static final long STREAM_TIMEOUT_MS = Duration.ofMinutes(30).toMillis();

  private final StepLogStreamService stepLogStreamService;
  private final WorkflowInspectionService workflowInspectionService;
  // OQ-4 — bounded executor for the (potentially blocking) follow/replay so the servlet container
  // thread is released immediately after returning the emitter. Single local operator → small pool.
  private final ExecutorService streamExecutor =
      Executors.newFixedThreadPool(4, namedDaemonThreadFactory());

  public RunnerLogStreamController(
      StepLogStreamService stepLogStreamService,
      WorkflowInspectionService workflowInspectionService) {
    this.stepLogStreamService = stepLogStreamService;
    this.workflowInspectionService = workflowInspectionService;
  }

  @GetMapping(
      value = "/{workflowRunId}/runner-logs/stream",
      produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  @Operation(
      operationId = "streamRunnerLogs",
      summary = "Stream a run's latest runner-execution logs (live + historical)",
      description =
          "Server-Sent Events stream of the run's latest runner execution. While the execution is "
              + "live the container's logs are followed (docker logs --follow) with BEST-EFFORT "
              + "per-line redaction; once finished, the persisted post-hoc-redacted log (story 3.6) "
              + "is replayed — that persisted scan is the AUTHORITATIVE redaction guarantee, the "
              + "live redaction is best-effort only (ADR 0025). Served only over the localhost-only "
              + "binding to the single local operator; gated by the view_runner_logs allowed-action. "
              + "Persists nothing and never mutates runner_executions (ADR 0025 D4). Epic 4 story 4.4 "
              + "ADDED a separate redacted-log ATTACHMENT download (GET "
              + "/api/v1/runner-executions/{rexId}/logs/download, downloadRunnerLog) — this reverses "
              + "the earlier 'no separate download surface' note; this SSE viewer remains the live "
              + "follow. Events: log {stream,line,seq}, status {phase,rex}, end {reason}, error "
              + "{reason}.")
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        description =
            "An event stream (text/event-stream). When view_runner_logs is not allowed for the "
                + "run's state, the stream carries a single error+end event instead of log lines."),
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
  public SseEmitter streamRunnerLogs(
      @Parameter(description = "Run public id, e.g. run_abc123.", example = "run_abc123")
          @PathVariable
          String workflowRunId,
      @Parameter(
              description =
                  "Actor role for action gating; defaults to product_reviewer when absent. "
                      + "view_runner_logs is role-agnostic in the runner-execution states, so the "
                      + "default resolves the gate for any operator.",
              example = "product_reviewer",
              schema =
                  @Schema(
                      type = "string",
                      allowableValues = {"product_reviewer", "workflow_owner", "developer"},
                      nullable = true))
          @RequestParam(name = "actorRole", required = false)
          String actorRole) {
    String normalizedActorRole = actorRole == null ? null : actorRole.strip();
    String correlationId = MDC.get(MdcKeys.CORRELATION_ID);
    SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
    SseLogStreamSink sink = new SseLogStreamSink(emitter);

    // Server-side gating (AC6 / Trap T5). getAllowedActions resolves the run + role (throwing the
    // standard RUN_NOT_FOUND / INVALID_ID_PREFIX / UNKNOWN_ACTOR_ROLE Problem Details BEFORE the
    // stream opens). AllowedActionsResponse is the only adapters.rest class permitted to translate
    // the typed AllowedAction list to wire strings, so the gate reads the string array here.
    boolean allowed =
        AllowedActionsResponse.from(
                workflowInspectionService.getAllowedActions(workflowRunId, normalizedActorRole))
            .actions()
            .contains(VIEW_RUNNER_LOGS_ACTION);
    log.info(
        "REST runner-logs stream open workflowRunId={} actorRole={} allowed={}",
        MdcKeys.sanitizeForLog(workflowRunId),
        MdcKeys.sanitizeForLog(normalizedActorRole),
        allowed);
    if (!allowed) {
      log.warn(
          "REST runner-logs stream denied workflowRunId={} reason=view_runner_logs_not_allowed",
          MdcKeys.sanitizeForLog(workflowRunId));
      sink.onError("view_runner_logs_not_allowed");
      sink.onEnd("forbidden");
      return emitter;
    }

    SubscriptionGate gate = new SubscriptionGate();
    emitter.onCompletion(() -> gate.close());
    emitter.onTimeout(
        () -> {
          log.warn(
              "REST runner-logs stream timeout workflowRunId={}",
              MdcKeys.sanitizeForLog(workflowRunId));
          gate.close();
          emitter.complete();
        });
    emitter.onError(
        throwable -> {
          log.warn(
              "REST runner-logs stream client-disconnect workflowRunId={} cause={}",
              MdcKeys.sanitizeForLog(workflowRunId),
              throwable == null ? "<none>" : throwable.toString());
          gate.close();
        });

    streamExecutor.execute(
        () -> {
          String priorCorrelation =
              correlationId == null
                  ? null
                  : MdcKeys.beginScope(MdcKeys.CORRELATION_ID, correlationId);
          try {
            AutoCloseable handle = stepLogStreamService.streamRunnerLogs(workflowRunId, sink);
            gate.install(handle);
          } catch (RuntimeException streamFailure) {
            log.warn(
                "REST runner-logs stream failed workflowRunId={} cause={}",
                MdcKeys.sanitizeForLog(workflowRunId),
                streamFailure.toString());
            sink.onError("stream-failed");
            sink.onEnd("stream-failed");
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
    streamExecutor.shutdownNow();
  }

  private static ThreadFactory namedDaemonThreadFactory() {
    AtomicInteger counter = new AtomicInteger();
    return runnable -> {
      Thread thread = new Thread(runnable, "runner-log-stream-" + counter.incrementAndGet());
      thread.setDaemon(true);
      return thread;
    };
  }

  /**
   * Race-safe holder for the in-flight {@link AutoCloseable} follow subscription. The executor task
   * {@link #install(AutoCloseable)}s the handle once the follow starts; an emitter terminal
   * callback {@link #close()}s it. If the client disconnects BEFORE the handle is installed, {@code
   * install} closes it immediately so no follow thread leaks (Trap T3).
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
        // Best-effort release; the underlying adapter already logs its own close failures.
      }
    }
  }
}
