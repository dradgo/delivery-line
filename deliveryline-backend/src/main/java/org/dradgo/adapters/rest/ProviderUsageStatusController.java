package org.dradgo.adapters.rest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Optional;
import org.dradgo.application.observability.MdcKeys;
import org.dradgo.application.workflow.WorkflowInspectionService;
import org.dradgo.application.workflow.WorkflowInspectionService.ProviderUsageStatusView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Story 3d-7 (FR69, AC5) — read endpoint for the Provider Limit Status indicator: the latest
 * per-credential provider usage/limit snapshot for a run (5h/weekly windows, or the documented "not
 * exposed" state). One-shot JSON read (NOT a stream) consumed by the frontend via the generated
 * client + by the CLI.
 *
 * <p><b>Server-side gating (AC5 / Trap T5).</b> Before returning data the controller computes the
 * run's allowed actions (the same {@link WorkflowInspectionService} matrix backing {@code
 * /allowed-actions}) and returns {@code 403} when {@code view_provider_usage_status} is absent —
 * the backend is the REAL guard, not just the frontend gate. Localhost-only is inherited from
 * {@code server.address=127.0.0.1} + {@code RestBindingGuard} (no new binding).
 *
 * <p><b>No secret (AC4).</b> The response carries only window numbers, timestamps, and the
 * non-secret account label — never a token/key/account secret.
 */
@RestController
@RequestMapping("/api/v1/workflows")
@Tag(name = "Workflows", description = "Inspect and command governed workflow runs.")
public class ProviderUsageStatusController {

  private static final Logger log = LoggerFactory.getLogger(ProviderUsageStatusController.class);

  /** AC5 wire value of {@code AllowedAction.VIEW_PROVIDER_USAGE_STATUS} (string-gated). */
  private static final String VIEW_PROVIDER_USAGE_STATUS_ACTION = "view_provider_usage_status";

  private final WorkflowInspectionService workflowInspectionService;

  public ProviderUsageStatusController(WorkflowInspectionService workflowInspectionService) {
    this.workflowInspectionService = workflowInspectionService;
  }

  @GetMapping(
      value = "/{workflowRunId}/provider-usage",
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      operationId = "getProviderUsageStatus",
      summary = "Latest per-credential provider usage/limit status for a run",
      description =
          "Returns the latest NON-SECRET provider usage/limit snapshot for the run — the 5-hour + "
              + "weekly window status (or the documented 'not exposed by provider' state). Values "
              + "are provider-reported and as-of a timestamp; never fabricated. Gated by the "
              + "view_provider_usage_status allowed-action (offered in the runner-execution states). "
              + "Returns present=false when no snapshot has been captured yet. Carries no secret "
              + "material — only window numbers, timestamps, and a non-secret account label.")
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        description = "Latest snapshot, or present=false when none."),
    // Review 2026-06-24 (#331): the gate denial is status-only (no body) — document it as a
    // no-content 403 rather than inheriting the 200 JSON body from the return type. The project has
    // no 403 DomainErrorCode / problem+json denial convention yet (the SSE siblings signal denial
    // in-stream), and the story scope-guards a new error code; an explicit empty @Content keeps the
    // OpenAPI contract honest without one.
    @ApiResponse(
        responseCode = "403",
        description = "view_provider_usage_status is not allowed for the run's state.",
        content = @Content),
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
  public ResponseEntity<ProviderUsageStatusResponse> getProviderUsageStatus(
      @Parameter(description = "Run public id, e.g. run_abc123.", example = "run_abc123")
          @PathVariable
          String workflowRunId,
      @Parameter(
              description =
                  "Actor role for action gating; defaults to product_reviewer when absent. "
                      + "view_provider_usage_status is role-agnostic in the runner-execution states.",
              example = "product_reviewer",
              schema =
                  @Schema(
                      type = "string",
                      allowableValues = {"product_reviewer", "workflow_owner", "developer"},
                      nullable = true))
          @RequestParam(name = "actorRole", required = false)
          String actorRole) {
    String normalizedActorRole = actorRole == null ? null : actorRole.strip();
    // Server-side gating (AC5 / Trap T5). getAllowedActions resolves the run + role (throwing the
    // standard RUN_NOT_FOUND / INVALID_ID_PREFIX / UNKNOWN_ACTOR_ROLE Problem Details first).
    boolean allowed =
        AllowedActionsResponse.from(
                workflowInspectionService.getAllowedActions(workflowRunId, normalizedActorRole))
            .actions()
            .contains(VIEW_PROVIDER_USAGE_STATUS_ACTION);
    log.info(
        "REST provider-usage status open workflowRunId={} actorRole={} allowed={}",
        MdcKeys.sanitizeForLog(workflowRunId),
        MdcKeys.sanitizeForLog(normalizedActorRole),
        allowed);
    if (!allowed) {
      log.warn(
          "REST provider-usage status denied workflowRunId={} "
              + "reason=view_provider_usage_status_not_allowed",
          MdcKeys.sanitizeForLog(workflowRunId));
      return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
    }
    Optional<ProviderUsageStatusView> snapshot =
        workflowInspectionService.getProviderUsageStatus(workflowRunId);
    ProviderUsageStatusResponse body =
        snapshot
            .map(ProviderUsageStatusResponse::from)
            .orElseGet(ProviderUsageStatusResponse::absent);
    log.info(
        "REST provider-usage status success workflowRunId={} present={} signalState={}",
        MdcKeys.sanitizeForLog(workflowRunId),
        body.present(),
        body.signalState());
    return ResponseEntity.ok(body);
  }
}
