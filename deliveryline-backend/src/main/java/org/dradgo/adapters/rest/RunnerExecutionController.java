package org.dradgo.adapters.rest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.LinkedHashMap;
import java.util.Map;
import org.dradgo.application.observability.MdcKeys;
import org.dradgo.application.security.LocalActorIdentityResolver;
import org.dradgo.application.workflow.RunnerLogDownloadAuditService;
import org.dradgo.application.workflow.WorkflowInspectionService;
import org.dradgo.application.workflow.WorkflowInspectionService.RedactedRunnerLogView;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.DomainErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Story 4.4 (AC5) — the FIRST attachment-download surface in the backend: streams a runner
 * execution's ALREADY-redacted log as a {@code text/plain} attachment. Served only over the
 * existing localhost-only REST binding ({@code server.address=127.0.0.1} + {@code
 * RestBindingGuard}, story 6.9) to the single local operator — this endpoint adds NO new binding.
 *
 * <p><strong>Redaction posture.</strong> The bytes were post-hoc scanned + redacted by story 3.6 at
 * container exit and served VERBATIM through {@link WorkflowInspectionService#getRedactedRunnerLog}
 * — never re-redacted, never routed through {@code redactForExport} (which would throw on the
 * default {@code local-only} classification). This is a local-operator read, not an export.
 *
 * <p><strong>Gating.</strong> Mirrors {@code RunnerLogStreamController}: the {@code rex_} id is
 * resolved to its run and the same {@code view_runner_logs} allowed-actions gate is applied before
 * serving. An unavailable log (missing rex / logs not captured / classification not servable) or a
 * gate denial maps to 404 {@code RUNNER_EXECUTION_NOT_FOUND} (no new error code — Reconciliation
 * 12). A successful download appends a best-effort {@code audit.logDownloaded} event.
 */
@RestController
@RequestMapping("/api/v1/runner-executions")
@Tag(name = "Runner executions", description = "Inspect and download runner-execution artifacts.")
public class RunnerExecutionController {

  private static final Logger log = LoggerFactory.getLogger(RunnerExecutionController.class);

  /**
   * AC5 wire value of {@code AllowedAction.VIEW_RUNNER_LOGS} (string-gated to keep the enum in
   * {@link WorkflowInspectionService} — ArchUnit allowed_action_derivation rule).
   */
  private static final String VIEW_RUNNER_LOGS_ACTION = "view_runner_logs";

  private final WorkflowInspectionService workflowInspectionService;
  private final RunnerLogDownloadAuditService runnerLogDownloadAuditService;
  private final LocalActorIdentityResolver localActorIdentityResolver;

  public RunnerExecutionController(
      WorkflowInspectionService workflowInspectionService,
      RunnerLogDownloadAuditService runnerLogDownloadAuditService,
      LocalActorIdentityResolver localActorIdentityResolver) {
    this.workflowInspectionService = workflowInspectionService;
    this.runnerLogDownloadAuditService = runnerLogDownloadAuditService;
    this.localActorIdentityResolver = localActorIdentityResolver;
  }

  @GetMapping(value = "/{rexId}/logs/download", produces = MediaType.TEXT_PLAIN_VALUE)
  @Operation(
      operationId = "downloadRunnerLog",
      summary = "Download a runner execution's redacted log",
      description =
          "Returns the runner execution's ALREADY-redacted stdout/stderr as a text/plain "
              + "attachment (story 3.6 post-hoc redaction is the authoritative guarantee; served "
              + "verbatim, never re-redacted). Served only over the localhost-only binding to the "
              + "single local operator; gated by the view_runner_logs allowed-action. The download "
              + "is recorded as a best-effort audit.logDownloaded event. An unavailable log or gate "
              + "denial returns 404 RUNNER_EXECUTION_NOT_FOUND.")
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        description = "The redacted runner log as a text/plain attachment.",
        content = @Content(mediaType = MediaType.TEXT_PLAIN_VALUE)),
    @ApiResponse(
        responseCode = "400",
        description = "Malformed runner-execution id (INVALID_ID_PREFIX).",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                schema = @Schema(implementation = ProblemDetailsResponse.class))),
    @ApiResponse(
        responseCode = "404",
        description =
            "No such runner execution, no captured/servable log, or view_runner_logs not allowed "
                + "(RUNNER_EXECUTION_NOT_FOUND).",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                schema = @Schema(implementation = ProblemDetailsResponse.class)))
  })
  public ResponseEntity<String> downloadRunnerLog(
      @Parameter(
              description = "Runner execution public id, e.g. rex_abc123.",
              example = "rex_abc123")
          @PathVariable
          String rexId,
      @Parameter(
              description =
                  "Actor role for action gating; view_runner_logs is role-agnostic in the "
                      + "runner-execution states, so the default resolves the gate for any operator.",
              example = "workflow_owner",
              schema =
                  @Schema(
                      type = "string",
                      allowableValues = {"product_reviewer", "workflow_owner", "developer"},
                      nullable = true))
          @RequestParam(name = "actorRole", required = false)
          String actorRole,
      @RequestHeader(name = "X-Actor-Identity", required = false) String actorIdentityHeader) {
    String normalizedActorRole = actorRole == null ? null : actorRole.strip();
    // Prefix-validates rex_ (INVALID_ID_PREFIX → 400) and resolves the already-redacted body.
    RedactedRunnerLogView view = workflowInspectionService.getRedactedRunnerLog(rexId);
    if (!view.available()) {
      log.warn(
          "REST runner-log download unavailable runnerExecutionId={} reason={}",
          MdcKeys.sanitizeForLog(rexId),
          view.reason());
      throw runnerLogNotFound(rexId, view.reason());
    }

    // Reuse the SSE viewer's state-based view_runner_logs gate (Reconciliation 6). RBAC is
    // audit-only (never 401/403); a state that forbids viewing hides the resource as 404.
    boolean allowed =
        workflowInspectionService.isActionAllowed(
            view.workflowRunId(), normalizedActorRole, VIEW_RUNNER_LOGS_ACTION);
    if (!allowed) {
      log.warn(
          "REST runner-log download denied runnerExecutionId={} workflowRunId={} reason=view_runner_logs_not_allowed",
          MdcKeys.sanitizeForLog(rexId),
          MdcKeys.sanitizeForLog(view.workflowRunId()));
      throw runnerLogNotFound(rexId, "view_runner_logs_not_allowed");
    }

    String actorIdentity = localActorIdentityResolver.resolve(actorIdentityHeader);
    log.info(
        "REST runner-log download serving redacted log runnerExecutionId={} workflowRunId={} classification={} byteSize={}",
        MdcKeys.sanitizeForLog(rexId),
        MdcKeys.sanitizeForLog(view.workflowRunId()),
        view.classification(),
        view.byteSize());

    // Best-effort audit (OQ-2): a failed append must NOT fail the download.
    try {
      runnerLogDownloadAuditService.recordLogDownloaded(
          view.workflowRunId(), rexId, actorIdentity, ActorType.HUMAN);
    } catch (RuntimeException auditFailure) {
      log.warn(
          "REST runner-log download audit append failed (best-effort) runnerExecutionId={} cause={}",
          MdcKeys.sanitizeForLog(rexId),
          auditFailure.toString());
    }

    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.TEXT_PLAIN);
    headers.setContentDisposition(
        ContentDisposition.attachment().filename("runner-" + rexId + ".log").build());
    return new ResponseEntity<>(
        renderLogBody(view), headers, org.springframework.http.HttpStatus.OK);
  }

  private static String renderLogBody(RedactedRunnerLogView view) {
    StringBuilder body = new StringBuilder();
    body.append("==== stdout ====\n");
    body.append(view.stdout() == null ? "" : view.stdout());
    if (body.length() == 0 || body.charAt(body.length() - 1) != '\n') {
      body.append('\n');
    }
    body.append("\n==== stderr ====\n");
    body.append(view.stderr() == null ? "" : view.stderr());
    if (body.charAt(body.length() - 1) != '\n') {
      body.append('\n');
    }
    if (view.truncated()) {
      body.append("\n[log truncated — exceeded the read cap]\n");
    }
    return body.toString();
  }

  private static DomainException runnerLogNotFound(String rexId, String reason) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("runnerExecutionId", rexId);
    details.put("reason", reason);
    return new DomainException(
        DomainErrorCode.RUNNER_EXECUTION_NOT_FOUND,
        "Runner execution log not available: " + rexId,
        details);
  }
}
