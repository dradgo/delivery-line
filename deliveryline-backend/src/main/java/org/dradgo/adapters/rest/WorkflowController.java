package org.dradgo.adapters.rest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.dradgo.application.observability.MdcKeys;
import org.dradgo.application.recovery.DeveloperTakeoverService;
import org.dradgo.application.recovery.TakeoverResult;
import org.dradgo.application.runner.ManualArtifactSubmissionService;
import org.dradgo.application.runner.ManualArtifactSubmissionService.ManualArtifactSubmissionCommand;
import org.dradgo.application.security.LocalActorIdentityResolver;
import org.dradgo.application.workflow.ApprovalReviewerRoleResolver;
import org.dradgo.application.workflow.WorkflowArchiveResult;
import org.dradgo.application.workflow.WorkflowArchiveService;
import org.dradgo.application.workflow.WorkflowCommandService;
import org.dradgo.application.workflow.WorkflowInspectionService;
import org.dradgo.application.workflow.WorkflowStateChangeResult;
import org.dradgo.application.workflow.commands.AcceptImplementationCommand;
import org.dradgo.application.workflow.commands.ApproveSpecCommand;
import org.dradgo.application.workflow.commands.ArchiveRunCommand;
import org.dradgo.application.workflow.commands.RejectImplementationCommand;
import org.dradgo.application.workflow.commands.RejectSpecCommand;
import org.dradgo.application.workflow.commands.RetryWorkflowCommand;
import org.dradgo.application.workflow.commands.SubmitClarificationCommand;
import org.dradgo.application.workflow.commands.SubmitWorkflowCommand;
import org.dradgo.application.workflow.commands.TakeoverWorkflowCommand;
import org.dradgo.application.workflow.commands.UnarchiveRunCommand;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.RejectionTaxonomy;
import org.dradgo.domain.registry.WorkflowState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST surface for workflow runs.
 *
 * <p>Command endpoints (POST submit / approve-spec / reject-spec / retry / takeover) come from the
 * shared command model (stories 1.7 / 1.15). Story 6.9 adds three <strong>read-only</strong> GET
 * endpoints (list / detail / events) that delegate to {@link WorkflowInspectionService} — the same
 * application service the CLI {@code status}/{@code history} commands use, so REST and CLI are two
 * faces of one source of truth.
 *
 * <p>Story 2.13 rebuilt the approve-spec / reject-spec endpoints and added the new {@code
 * /clarifications/&#123;clarificationId&#125;/answer} endpoint. The rebuild moves the actor +
 * correlation identity from request body fields to HTTP headers — {@code X-Actor-Identity}
 * (optional; property-fallback via {@link LocalActorIdentityResolver}); {@code X-Correlation-Id}
 * (resolved by {@code CorrelationIdFilter} ingress + auto-UUIDv7); {@code Idempotency-Key}
 * (required; missing surfaces as the typed {@link
 * org.dradgo.domain.registry.DomainErrorCode#MISSING_IDEMPOTENCY_KEY} Problem Details code via
 * {@link ProblemDetailsMapper}). RBAC stays audit-only (architecture line 256) — the controller
 * never returns 401/403; it stamps the resolved identity onto the underlying application command
 * for audit-trail correlation.
 */
@RestController
@Validated
@RequestMapping("/api/v1/workflows")
@Tag(name = "Workflows", description = "Inspect and command governed workflow runs.")
public class WorkflowController {

  private static final Logger log = LoggerFactory.getLogger(WorkflowController.class);

  private final WorkflowCommandService workflowCommandService;
  private final WorkflowInspectionService workflowInspectionService;
  private final ApprovalReviewerRoleResolver approvalReviewerRoleResolver;
  private final LocalActorIdentityResolver localActorIdentityResolver;
  // Story 3.25: the RICH developer-takeover service (story 3.22). Wired by the new POST /takeover
  // endpoint (cancelled-runner counts + preserved PR ref). The pre-existing transition-only POST
  // /takeover-workflow endpoint keeps using workflowCommandService.takeoverWorkflow (R1 / R9).
  private final DeveloperTakeoverService developerTakeoverService;
  // Story 3d-8 — governed soft-hide / un-hide of obsolete runs (archive marker + audit event).
  private final WorkflowArchiveService workflowArchiveService;
  // Story 3d-4 — the operator manual-artifact submission path (validate → ingest → finalize →
  // advance, one tx). The bundle retrieval rides workflowInspectionService.getManualBundle.
  private final ManualArtifactSubmissionService manualArtifactSubmissionService;
  private final com.fasterxml.jackson.databind.ObjectMapper objectMapper =
      new com.fasterxml.jackson.databind.ObjectMapper();

  public WorkflowController(
      WorkflowCommandService workflowCommandService,
      WorkflowInspectionService workflowInspectionService,
      ApprovalReviewerRoleResolver approvalReviewerRoleResolver,
      LocalActorIdentityResolver localActorIdentityResolver,
      DeveloperTakeoverService developerTakeoverService,
      WorkflowArchiveService workflowArchiveService,
      ManualArtifactSubmissionService manualArtifactSubmissionService) {
    this.workflowCommandService = workflowCommandService;
    this.workflowInspectionService = workflowInspectionService;
    this.approvalReviewerRoleResolver = approvalReviewerRoleResolver;
    this.localActorIdentityResolver = localActorIdentityResolver;
    this.developerTakeoverService = developerTakeoverService;
    this.workflowArchiveService = workflowArchiveService;
    this.manualArtifactSubmissionService = manualArtifactSubmissionService;
  }

  // ---------------------------------------------------------------------------
  // Read endpoints (story 6.9). Read-only; delegate to WorkflowInspectionService.
  // ---------------------------------------------------------------------------

  @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      operationId = "listWorkflows",
      summary = "List workflow runs",
      description =
          "Newest-first list of workflow runs for the review queue, optionally filtered by state.")
  @ApiResponse(
      responseCode = "200",
      description = "Workflow run summaries (direct array, no envelope).")
  public List<WorkflowSummaryResponse> listWorkflows(
      @Parameter(description = "Optional current-state filter, e.g. WaitingForSpecApproval.")
          @RequestParam(name = "state", required = false)
          String state,
      @Parameter(
              description =
                  "Include soft-hidden (archived) runs. Defaults to false (archived_at IS NULL)"
                      + " — story 3d-8.")
          @RequestParam(name = "includeArchived", required = false, defaultValue = "false")
          boolean includeArchived,
      @Parameter(description = "Max rows to return (clamped to 1..200).")
          @RequestParam(name = "limit", required = false, defaultValue = "50")
          int limit) {
    WorkflowState stateFilter =
        (state == null || state.isBlank()) ? null : WorkflowState.fromValue(state, "state");
    log.info(
        "REST list workflows received stateFilter={} includeArchived={} limit={}",
        stateFilter == null ? "<all>" : stateFilter.value(),
        includeArchived,
        limit);
    List<WorkflowSummaryResponse> summaries =
        workflowInspectionService.listRuns(stateFilter, includeArchived, limit).stream()
            .map(WorkflowSummaryResponse::from)
            .toList();
    log.info("REST list workflows success count={}", summaries.size());
    return summaries;
  }

  @GetMapping(value = "/{workflowRunId}", produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      operationId = "getWorkflow",
      summary = "Get workflow run detail",
      description = "Current status of a single workflow run.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Workflow run detail."),
    @ApiResponse(
        responseCode = "400",
        description = "Malformed run id (INVALID_ID_PREFIX).",
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
  public WorkflowDetailResponse getWorkflow(
      @Parameter(description = "Run public id, e.g. run_abc123.", example = "run_abc123")
          @PathVariable
          String workflowRunId) {
    log.info("REST get workflow detail received workflowRunId={}", workflowRunId);
    WorkflowDetailResponse response =
        WorkflowDetailResponse.from(workflowInspectionService.getStatus(workflowRunId));
    log.info("REST get workflow detail success workflowRunId={}", workflowRunId);
    return response;
  }

  @GetMapping(value = "/{workflowRunId}/events", produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      operationId = "getWorkflowEvents",
      summary = "Get workflow run event history",
      description =
          "Ordered event stream for a run, in the committed wire schema "
              + "(workflow-events-response.schema.json).")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Event history."),
    @ApiResponse(
        responseCode = "400",
        description = "Malformed run id (INVALID_ID_PREFIX) or history too large.",
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
  public WorkflowEventsResponse getWorkflowEvents(
      @Parameter(description = "Run public id, e.g. run_abc123.", example = "run_abc123")
          @PathVariable
          String workflowRunId) {
    log.info("REST get workflow events received workflowRunId={}", workflowRunId);
    WorkflowEventsResponse response =
        WorkflowEventsResponse.from(workflowInspectionService.getEventStream(workflowRunId));
    log.info(
        "REST get workflow events success workflowRunId={} eventCount={}",
        workflowRunId,
        response.events().size());
    return response;
  }

  /**
   * Story 3a-9 (Gate 3): artifact-content read for the run-detail review surface. Returns the
   * redacted artifact body (UTF-8 markdown) plus identity/type/version/status/classification/
   * createdAt/checksum so the Artifact Review Panel (story 2.17) can render the real spec body
   * before a reviewer fires {@code approve_spec}. Read-only and idempotent — no Idempotency-Key.
   *
   * <p>Cross-run safety: an {@code artifactId} not owned by {@code workflowRunId} returns 404
   * {@code ARTIFACT_RECORD_NOT_FOUND} (never another run's artifact); a non-existent run returns
   * 404 {@code RUN_NOT_FOUND}; a malformed id returns 400 {@code INVALID_ID_PREFIX} at the
   * validation boundary — mirroring the detail endpoint.
   */
  /**
   * Story 3d-2 (AC3/AC4/AC6/AC8) — the advisory reviewer verdict for the run, surfaced beside the
   * WaitingForReview Decision Bar. Presentational + advisory-only: it adds NO governed action (the
   * allowed-actions matrix is unchanged) and never gates the human approve/reject decision. State
   * is derived server-side ({@code pending|available|unavailable}); a no-binding project returns
   * {@code unavailable} + {@code no_reviewer_configured} so the frontend renders nothing (AC5).
   */
  @GetMapping(
      value = "/{workflowRunId}/reviewer-verdict",
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      operationId = "getReviewerVerdict",
      summary = "Get the advisory reviewer verdict for a workflow run",
      description =
          "Returns the second-opinion verdict a project-configured reviewer model produced over the "
              + "run's WaitingForReview output artifact, or a pending/unavailable state. Advisory "
              + "only (story 3d-2): never auto-approves/rejects and adds no governed action.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Verdict state (+ verdict when available)."),
    @ApiResponse(
        responseCode = "400",
        description = "Malformed run id (INVALID_ID_PREFIX).",
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
  public ReviewerVerdictResponse getReviewerVerdict(
      @Parameter(description = "Run public id, e.g. run_abc123.", example = "run_abc123")
          @PathVariable
          String workflowRunId) {
    log.info(
        "REST get reviewer-verdict received workflowRunId={}",
        MdcKeys.sanitizeForLog(workflowRunId));
    ReviewerVerdictResponse response =
        ReviewerVerdictResponse.from(workflowInspectionService.getReviewerVerdict(workflowRunId));
    log.info(
        "REST get reviewer-verdict success workflowRunId={} state={}",
        MdcKeys.sanitizeForLog(workflowRunId),
        response.state());
    return response;
  }

  @GetMapping(
      value = "/{workflowRunId}/artifacts/{artifactId}",
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      operationId = "getArtifact",
      summary = "Get a workflow artifact's redacted content",
      description =
          "Returns the redacted payload (UTF-8 markdown body) and metadata of a single artifact "
              + "owned by the run. Backs the Artifact Review Panel spec read (story 3a-9).")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Artifact content + metadata."),
    @ApiResponse(
        responseCode = "400",
        description = "Malformed run or artifact id (INVALID_ID_PREFIX).",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                schema = @Schema(implementation = ProblemDetailsResponse.class))),
    @ApiResponse(
        responseCode = "404",
        description =
            "No such run (RUN_NOT_FOUND) or no such artifact for this run (ARTIFACT_RECORD_NOT_FOUND).",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                schema = @Schema(implementation = ProblemDetailsResponse.class))),
    @ApiResponse(
        responseCode = "503",
        description = "Artifact payload could not be read (ARTIFACT_PAYLOAD_UNAVAILABLE).",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                schema = @Schema(implementation = ProblemDetailsResponse.class)))
  })
  public ArtifactDetailResponse getArtifact(
      @Parameter(description = "Run public id, e.g. run_abc123.", example = "run_abc123")
          @PathVariable
          String workflowRunId,
      @Parameter(description = "Artifact public id, e.g. art_abc123.", example = "art_abc123")
          @PathVariable
          String artifactId) {
    log.info(
        "REST get artifact received workflowRunId={} artifactId={}",
        MdcKeys.sanitizeForLog(workflowRunId),
        MdcKeys.sanitizeForLog(artifactId));
    ArtifactDetailResponse response =
        ArtifactDetailResponse.from(
            workflowInspectionService.getArtifactDetail(workflowRunId, artifactId));
    log.info(
        "REST get artifact success workflowRunId={} artifactId={} artifactType={} version={}",
        MdcKeys.sanitizeForLog(workflowRunId),
        MdcKeys.sanitizeForLog(artifactId),
        response.artifactType(),
        response.version());
    return response;
  }

  /**
   * Returns the backend-derived allowed actions for a workflow run plus a version stamp the UI
   * echoes back on mutations (story 2.14 AC1, AC6, AC11).
   *
   * <p><strong>Forward-compatibility contract (AC11):</strong> the {@code actions} array may grow
   * to include future Epic 3 / Epic 4 wire values (e.g., {@code approve_implementation}, {@code
   * resume}, {@code clear_escalation_marker}). The UI MUST gracefully handle unknown action values
   * by hiding them — UX-DR12 + UX-DR6 cover the unsupported-state rendering path on the frontend.
   * Adding a new {@link org.dradgo.domain.registry.AllowedAction} value is therefore a binary-
   * compatible change on this endpoint.
   */
  @GetMapping(
      value = "/{workflowRunId}/allowed-actions",
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      operationId = "getAllowedActions",
      summary = "Get backend-derived allowed actions for a workflow run",
      description =
          "Returns the typed list of actions valid for the run's current state + actor role, "
              + "plus a version stamp the UI echoes back on mutations to detect staleness "
              + "(story 2.14). Read-only and idempotent — no Idempotency-Key required. "
              + "Forward-compatible: the UI must gracefully hide unknown action values "
              + "(UX-DR12, UX-DR6).")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Allowed actions + version stamp."),
    @ApiResponse(
        responseCode = "400",
        description =
            "Malformed run id (INVALID_ID_PREFIX) or unrecognized actorRole "
                + "(UNKNOWN_ACTOR_ROLE).",
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
  public AllowedActionsResponse getAllowedActions(
      @Parameter(description = "Run public id, e.g. run_abc123.", example = "run_abc123")
          @PathVariable
          String workflowRunId,
      @Parameter(
              description =
                  "Actor role for action gating; defaults to product_reviewer when absent. "
                      + "Recognized values are case-sensitive — any other value returns "
                      + "400 UNKNOWN_ACTOR_ROLE.",
              example = "product_reviewer",
              schema =
                  @Schema(
                      type = "string",
                      allowableValues = {"product_reviewer", "workflow_owner", "developer"},
                      nullable = true))
          @RequestParam(name = "actorRole", required = false)
          String actorRole) {
    // Review P6: normalize the query-param at the controller boundary so the same value is logged
    // here and inside the service (otherwise grepping `actorRole=workflow_owner` misses
    // whitespace-padded requests that succeed via the service's internal `.strip()`).
    String normalizedActorRole = (actorRole == null) ? null : actorRole.strip();
    log.info(
        "REST get allowed-actions received workflowRunId={} actorRole={}",
        MdcKeys.sanitizeForLog(workflowRunId),
        MdcKeys.sanitizeForLog(normalizedActorRole));
    AllowedActionsResponse response =
        AllowedActionsResponse.from(
            workflowInspectionService.getAllowedActions(workflowRunId, normalizedActorRole));
    log.info(
        "REST get allowed-actions success workflowRunId={} actionCount={} workflowState={}",
        MdcKeys.sanitizeForLog(workflowRunId),
        response.actions().size(),
        MdcKeys.sanitizeForLog(response.versionStamp().workflowState()));
    return response;
  }

  // ---------------------------------------------------------------------------
  // Command endpoints
  // ---------------------------------------------------------------------------

  @PostMapping(
      value = "/submit-workflow",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(operationId = "submitWorkflow", summary = "Submit a new workflow run")
  public SubmitWorkflowResponse submit(
      @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
      @Valid @RequestBody SubmitWorkflowRequest request) {
    return SubmitWorkflowResponse.from(
        workflowCommandService.submit(
            new SubmitWorkflowCommand(
                request.actorIdentity(),
                request.actorType(),
                idempotencyKey,
                request.correlationId(),
                request.linearTicketReference(),
                request.projectReference())));
  }

  @PostMapping(
      value = "/{workflowRunId}/approve-spec",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      operationId = "approveSpec",
      summary = "Approve a run's specification (story 2.13 rebuild)")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Approval recorded; state advanced."),
    @ApiResponse(
        responseCode = "400",
        description =
            "MISSING_IDEMPOTENCY_KEY, INVALID_IDEMPOTENCY_KEY, INVALID_COMMAND_PAYLOAD, INVALID_ID_PREFIX.",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                schema = @Schema(implementation = ProblemDetailsResponse.class))),
    @ApiResponse(
        responseCode = "404",
        description = "RUN_NOT_FOUND or ARTIFACT_RECORD_NOT_FOUND.",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                schema = @Schema(implementation = ProblemDetailsResponse.class))),
    @ApiResponse(
        responseCode = "409",
        description =
            "APPROVAL_VERSION_MISMATCH, IDEMPOTENCY_KEY_CONFLICT, ILLEGAL_TRANSITION, or WORKFLOW_RUN_TERMINAL.",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                schema = @Schema(implementation = ProblemDetailsResponse.class))),
    @ApiResponse(
        responseCode = "503",
        description = "ARTIFACT_PAYLOAD_UNAVAILABLE.",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                schema = @Schema(implementation = ProblemDetailsResponse.class)))
  })
  public WorkflowStateChangeResponse approveSpec(
      @PathVariable String workflowRunId,
      @RequestHeader(name = "Idempotency-Key") String idempotencyKey,
      @RequestHeader(name = "X-Actor-Identity", required = false) String actorIdentityHeader,
      HttpServletRequest httpRequest,
      @Valid @RequestBody ApproveSpecRequest request) {
    rejectMultiValuedIdempotencyKeyHeader(httpRequest);
    requireNonBlankIdempotencyKey(idempotencyKey);
    rejectMultiValuedActorIdentityHeader(httpRequest);
    // Story 2.13 round-4 D-R4-1: fail-closed gating on unsafe X-Actor-Identity header values
    // (oversize / control-char / FORMAT-category / surrogate / private-use / unassigned). Runs
    // BEFORE resolve(...) so a hostile or misconfigured value never silently falls back to the
    // property identity (which would mask the rejection in the audit trail).
    localActorIdentityResolver.requireSafe(actorIdentityHeader);
    String actorIdentity = localActorIdentityResolver.resolve(actorIdentityHeader);
    String correlationId = MdcKeys.sanitizeForLog(MDC.get(MdcKeys.CORRELATION_ID));
    log.info(
        "REST approve-spec received workflowRunId={} artifactId={} expectedArtifactVersion={} actorIdentity={}",
        MdcKeys.sanitizeForLog(workflowRunId),
        MdcKeys.sanitizeForLog(request.artifactId()),
        request.expectedArtifactVersion(),
        MdcKeys.sanitizeForLog(actorIdentity));
    WorkflowStateChangeResponse response =
        WorkflowStateChangeResponse.from(
            workflowCommandService.approveSpec(
                new ApproveSpecCommand(
                    workflowRunId,
                    request.artifactId(),
                    request.expectedArtifactVersion(),
                    request.expectedContextBundleVersion(),
                    actorIdentity,
                    ActorType.HUMAN,
                    idempotencyKey,
                    correlationId,
                    approvalReviewerRoleResolver.resolveFor(request.reviewerRole()),
                    request.reason())));
    log.info(
        "REST approve-spec success workflowRunId={} currentState={}",
        workflowRunId,
        response.currentState());
    return response;
  }

  @PostMapping(
      value = "/{workflowRunId}/reject-spec",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      operationId = "rejectSpec",
      summary = "Reject a run's specification (story 2.13 rebuild)")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Rejection recorded; state advanced."),
    @ApiResponse(
        responseCode = "400",
        description =
            "MISSING_IDEMPOTENCY_KEY, INVALID_IDEMPOTENCY_KEY, INVALID_COMMAND_PAYLOAD, INVALID_ID_PREFIX.",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                schema = @Schema(implementation = ProblemDetailsResponse.class))),
    @ApiResponse(
        responseCode = "404",
        description = "RUN_NOT_FOUND or ARTIFACT_RECORD_NOT_FOUND.",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                schema = @Schema(implementation = ProblemDetailsResponse.class))),
    @ApiResponse(
        responseCode = "409",
        description =
            "APPROVAL_VERSION_MISMATCH, IDEMPOTENCY_KEY_CONFLICT, ILLEGAL_TRANSITION, or WORKFLOW_RUN_TERMINAL.",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                schema = @Schema(implementation = ProblemDetailsResponse.class))),
    @ApiResponse(
        responseCode = "503",
        description = "ARTIFACT_PAYLOAD_UNAVAILABLE.",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                schema = @Schema(implementation = ProblemDetailsResponse.class)))
  })
  public WorkflowStateChangeResponse rejectSpec(
      @PathVariable String workflowRunId,
      @RequestHeader(name = "Idempotency-Key") String idempotencyKey,
      @RequestHeader(name = "X-Actor-Identity", required = false) String actorIdentityHeader,
      HttpServletRequest httpRequest,
      @Valid @RequestBody RejectSpecRequest request) {
    rejectMultiValuedIdempotencyKeyHeader(httpRequest);
    requireNonBlankIdempotencyKey(idempotencyKey);
    rejectMultiValuedActorIdentityHeader(httpRequest);
    // Story 2.13 round-4 D-R4-1: see approveSpec javadoc.
    localActorIdentityResolver.requireSafe(actorIdentityHeader);
    String actorIdentity = localActorIdentityResolver.resolve(actorIdentityHeader);
    String correlationId = MdcKeys.sanitizeForLog(MDC.get(MdcKeys.CORRELATION_ID));
    log.info(
        "REST reject-spec received workflowRunId={} artifactId={} expectedArtifactVersion={} actorIdentity={}",
        MdcKeys.sanitizeForLog(workflowRunId),
        MdcKeys.sanitizeForLog(request.artifactId()),
        request.expectedArtifactVersion(),
        MdcKeys.sanitizeForLog(actorIdentity));
    WorkflowStateChangeResponse response =
        WorkflowStateChangeResponse.from(
            workflowCommandService.rejectSpec(
                new RejectSpecCommand(
                    workflowRunId,
                    request.artifactId(),
                    request.expectedArtifactVersion(),
                    request.expectedContextBundleVersion(),
                    actorIdentity,
                    ActorType.HUMAN,
                    idempotencyKey,
                    correlationId,
                    approvalReviewerRoleResolver.resolveFor(request.reviewerRole()),
                    request.taggedFeedback(),
                    request.reasonText())));
    log.info(
        "REST reject-spec success workflowRunId={} currentState={}",
        workflowRunId,
        response.currentState());
    return response;
  }

  @PostMapping(
      value = "/{workflowRunId}/accept-implementation",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      operationId = "acceptImplementation",
      summary = "Accept a run's implementation (story 3.23)")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Acceptance recorded; state advanced."),
    @ApiResponse(
        responseCode = "400",
        description =
            "MISSING_IDEMPOTENCY_KEY, INVALID_IDEMPOTENCY_KEY, INVALID_COMMAND_PAYLOAD, INVALID_ID_PREFIX, INVALID_REVIEWER_ROLE_FOR_ENDPOINT.",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                schema = @Schema(implementation = ProblemDetailsResponse.class))),
    @ApiResponse(
        responseCode = "404",
        description = "RUN_NOT_FOUND or ARTIFACT_RECORD_NOT_FOUND.",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                schema = @Schema(implementation = ProblemDetailsResponse.class))),
    @ApiResponse(
        responseCode = "409",
        description =
            "APPROVAL_VERSION_MISMATCH, IDEMPOTENCY_KEY_CONFLICT, ARTIFACT_PR_LINK_MISMATCH, ILLEGAL_TRANSITION, or WORKFLOW_RUN_TERMINAL.",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                schema = @Schema(implementation = ProblemDetailsResponse.class))),
    @ApiResponse(
        responseCode = "503",
        description = "ARTIFACT_PAYLOAD_UNAVAILABLE.",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                schema = @Schema(implementation = ProblemDetailsResponse.class)))
  })
  public WorkflowStateChangeResponse acceptImplementation(
      @PathVariable String workflowRunId,
      @RequestHeader(name = "Idempotency-Key") String idempotencyKey,
      @RequestHeader(name = "X-Actor-Identity", required = false) String actorIdentityHeader,
      HttpServletRequest httpRequest,
      @Valid @RequestBody AcceptImplementationRequest request) {
    rejectMultiValuedIdempotencyKeyHeader(httpRequest);
    requireNonBlankIdempotencyKey(idempotencyKey);
    rejectMultiValuedActorIdentityHeader(httpRequest);
    // Story 2.13 round-4 D-R4-1: see approveSpec javadoc.
    localActorIdentityResolver.requireSafe(actorIdentityHeader);
    String actorIdentity = localActorIdentityResolver.resolve(actorIdentityHeader);
    String correlationId = MdcKeys.sanitizeForLog(MDC.get(MdcKeys.CORRELATION_ID));
    // Story 3.23 R4: this is a developer-only endpoint. Validate the raw reviewerRole at the
    // boundary with a typed code; do NOT route it through
    // approvalReviewerRoleResolver.resolveFor(..)
    // (its blank -> product_reviewer default would mask the mismatch). This is the key delta from
    // approveSpec. The service persists the role verbatim from the command.
    String reviewerRole =
        requireDeveloperReviewerRole("accept-implementation", request.reviewerRole());
    log.info(
        "REST accept-implementation received workflowRunId={} artifactId={} expectedArtifactVersion={} actorIdentity={}",
        MdcKeys.sanitizeForLog(workflowRunId),
        MdcKeys.sanitizeForLog(request.artifactId()),
        request.expectedArtifactVersion(),
        MdcKeys.sanitizeForLog(actorIdentity));
    WorkflowStateChangeResponse response =
        WorkflowStateChangeResponse.from(
            workflowCommandService.acceptImplementation(
                new AcceptImplementationCommand(
                    workflowRunId,
                    request.artifactId(),
                    request.expectedArtifactVersion(),
                    request.expectedContextBundleVersion(),
                    actorIdentity,
                    ActorType.HUMAN,
                    idempotencyKey,
                    correlationId,
                    reviewerRole,
                    request.reason())));
    log.info(
        "REST accept-implementation success workflowRunId={} currentState={}",
        workflowRunId,
        response.currentState());
    return response;
  }

  @PostMapping(
      value = "/{workflowRunId}/reject-implementation",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      operationId = "rejectImplementation",
      summary = "Reject a run's implementation with structured technical feedback (story 3.24)")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Rejection recorded; state advanced."),
    @ApiResponse(
        responseCode = "400",
        description =
            "MISSING_IDEMPOTENCY_KEY, INVALID_IDEMPOTENCY_KEY, INVALID_COMMAND_PAYLOAD, INVALID_ID_PREFIX, INVALID_REVIEWER_ROLE_FOR_ENDPOINT, INVALID_REJECTION_TAXONOMY, MISSING_REJECTION_TAXONOMY.",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                schema = @Schema(implementation = ProblemDetailsResponse.class))),
    @ApiResponse(
        responseCode = "404",
        description = "RUN_NOT_FOUND or ARTIFACT_RECORD_NOT_FOUND.",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                schema = @Schema(implementation = ProblemDetailsResponse.class))),
    @ApiResponse(
        responseCode = "409",
        description =
            "APPROVAL_VERSION_MISMATCH, IDEMPOTENCY_KEY_CONFLICT, ILLEGAL_TRANSITION, or WORKFLOW_RUN_TERMINAL.",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                schema = @Schema(implementation = ProblemDetailsResponse.class)))
  })
  public WorkflowStateChangeResponse rejectImplementation(
      @PathVariable String workflowRunId,
      @RequestHeader(name = "Idempotency-Key") String idempotencyKey,
      @RequestHeader(name = "X-Actor-Identity", required = false) String actorIdentityHeader,
      HttpServletRequest httpRequest,
      @Valid @RequestBody RejectImplementationRequest request) {
    rejectMultiValuedIdempotencyKeyHeader(httpRequest);
    requireNonBlankIdempotencyKey(idempotencyKey);
    rejectMultiValuedActorIdentityHeader(httpRequest);
    // Story 2.13 round-4 D-R4-1: see approveSpec javadoc.
    localActorIdentityResolver.requireSafe(actorIdentityHeader);
    String actorIdentity = localActorIdentityResolver.resolve(actorIdentityHeader);
    String correlationId = MdcKeys.sanitizeForLog(MDC.get(MdcKeys.CORRELATION_ID));
    // Story 3.24 R4: developer-only endpoint. Validate the raw reviewerRole at the boundary with
    // the
    // typed INVALID_REVIEWER_ROLE_FOR_ENDPOINT idiom shared with accept-implementation (story
    // 3.23);
    // do NOT route it through approvalReviewerRoleResolver.resolveFor(..) (its blank ->
    // product_reviewer default would mask the mismatch). The service persists the role verbatim.
    String reviewerRole =
        requireDeveloperReviewerRole("reject-implementation", request.reviewerRole());
    // Story 3.24 R4: taggedFeedback must be a developer-subset RejectionTaxonomy. An unknown enum
    // string fails Jackson first (INVALID_COMMAND_PAYLOAD); a valid-but-product value (e.g.
    // MISSING_SCOPE) is rejected here as the typed INVALID_REJECTION_TAXONOMY. The service keeps
    // its
    // own defense-in-depth subset guard.
    requireDeveloperRejectionTaxonomy(request.taggedFeedback());
    log.info(
        "REST reject-implementation received workflowRunId={} artifactId={} expectedArtifactVersion={} actorIdentity={}",
        MdcKeys.sanitizeForLog(workflowRunId),
        MdcKeys.sanitizeForLog(request.artifactId()),
        request.expectedArtifactVersion(),
        MdcKeys.sanitizeForLog(actorIdentity));
    WorkflowStateChangeResponse response =
        WorkflowStateChangeResponse.from(
            workflowCommandService.rejectImplementation(
                new RejectImplementationCommand(
                    workflowRunId,
                    request.artifactId(),
                    request.expectedArtifactVersion(),
                    request.expectedContextBundleVersion(),
                    actorIdentity,
                    ActorType.HUMAN,
                    idempotencyKey,
                    correlationId,
                    reviewerRole,
                    request.taggedFeedback(),
                    request.reasonText())));
    log.info(
        "REST reject-implementation success workflowRunId={} currentState={}",
        workflowRunId,
        response.currentState());
    return response;
  }

  // Story 3d-4 (AC1) — read-only retrieval of a parked run's manual input bundle. No
  // Idempotency-Key
  // (idempotent read). Run not parked ⇒ MANUAL_EXECUTION_NOT_APPLICABLE (409); scratch evicted ⇒ a
  // typed bundleNotPersisted state on a 200 (not a 500). Advertised by the OBTAIN_MANUAL_BUNDLE
  // allowed action (registered in 3d-3).
  @GetMapping(value = "/{workflowRunId}/manual-bundle", produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      operationId = "getManualBundle",
      summary = "Get the parked manual step's redacted input bundle (story 3d-4)")
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        description = "Bundle bytes (base64) or a typed unavailable state."),
    @ApiResponse(
        responseCode = "400",
        description = "Malformed run id (INVALID_ID_PREFIX).",
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
                schema = @Schema(implementation = ProblemDetailsResponse.class))),
    @ApiResponse(
        responseCode = "409",
        description = "Run is not parked for manual execution (MANUAL_EXECUTION_NOT_APPLICABLE).",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                schema = @Schema(implementation = ProblemDetailsResponse.class)))
  })
  public ManualBundleResponse getManualBundle(
      @Parameter(description = "Run public id, e.g. run_abc123.", example = "run_abc123")
          @PathVariable
          String workflowRunId) {
    log.info(
        "REST get manual-bundle received workflowRunId={}", MdcKeys.sanitizeForLog(workflowRunId));
    ManualBundleResponse response =
        ManualBundleResponse.from(workflowInspectionService.getManualBundle(workflowRunId));
    log.info(
        "REST get manual-bundle success workflowRunId={} runnerExecutionId={} available={}",
        MdcKeys.sanitizeForLog(workflowRunId),
        MdcKeys.sanitizeForLog(response.runnerExecutionId()),
        response.available());
    return response;
  }

  // Story 3d-4 (AC2-AC6) — governed submission of an operator-produced manual artifact. Runs the
  // SAME
  // runner-contracts output validation an automated runner's output would (no bypass), finalizes
  // the
  // parked awaiting_manual row, and transitions the run out of WaitingForManualExecution. Mirrors
  // the
  // accept/reject-implementation header preamble verbatim (Idempotency-Key + X-Actor-Identity).
  @PostMapping(
      value = "/{workflowRunId}/manual-artifact",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      operationId = "submitManualArtifact",
      summary = "Submit a manually-produced artifact for a parked run (story 3d-4)")
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        description = "Artifact ingested; state advanced out of WaitingForManualExecution."),
    @ApiResponse(
        responseCode = "400",
        description =
            "MISSING_IDEMPOTENCY_KEY, INVALID_IDEMPOTENCY_KEY, INVALID_COMMAND_PAYLOAD, INVALID_ID_PREFIX.",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                schema = @Schema(implementation = ProblemDetailsResponse.class))),
    @ApiResponse(
        responseCode = "404",
        description = "RUN_NOT_FOUND.",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                schema = @Schema(implementation = ProblemDetailsResponse.class))),
    @ApiResponse(
        responseCode = "409",
        description = "MANUAL_EXECUTION_NOT_APPLICABLE, IDEMPOTENCY_KEY_CONFLICT.",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                schema = @Schema(implementation = ProblemDetailsResponse.class))),
    @ApiResponse(
        responseCode = "502",
        description = "RUNNER_OUTPUT_VALIDATION_FAILED, RUNNER_ARTIFACT_TYPE_MISMATCH.",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                schema = @Schema(implementation = ProblemDetailsResponse.class)))
  })
  public WorkflowStateChangeResponse submitManualArtifact(
      @PathVariable String workflowRunId,
      @RequestHeader(name = "Idempotency-Key") String idempotencyKey,
      @RequestHeader(name = "X-Actor-Identity", required = false) String actorIdentityHeader,
      HttpServletRequest httpRequest,
      @Valid @RequestBody ManualArtifactSubmissionRequest request) {
    rejectMultiValuedIdempotencyKeyHeader(httpRequest);
    requireNonBlankIdempotencyKey(idempotencyKey);
    rejectMultiValuedActorIdentityHeader(httpRequest);
    localActorIdentityResolver.requireSafe(actorIdentityHeader);
    String actorIdentity = localActorIdentityResolver.resolve(actorIdentityHeader);
    String correlationId = MdcKeys.sanitizeForLog(MDC.get(MdcKeys.CORRELATION_ID));
    log.info(
        "REST manual-artifact received workflowRunId={} actorIdentity={}",
        MdcKeys.sanitizeForLog(workflowRunId),
        MdcKeys.sanitizeForLog(actorIdentity));
    byte[] payloadBytes = serializeManualResult(request.result());
    Map<String, byte[]> artifactContents = decodeArtifactContents(request.artifactContents());
    WorkflowStateChangeResponse response =
        WorkflowStateChangeResponse.from(
            manualArtifactSubmissionService.submit(
                new ManualArtifactSubmissionCommand(
                    workflowRunId,
                    payloadBytes,
                    artifactContents,
                    idempotencyKey,
                    actorIdentity,
                    ActorType.HUMAN,
                    correlationId)));
    log.info(
        "REST manual-artifact success workflowRunId={} currentState={}",
        MdcKeys.sanitizeForLog(workflowRunId),
        response.currentState());
    return response;
  }

  private byte[] serializeManualResult(com.fasterxml.jackson.databind.JsonNode result) {
    try {
      return objectMapper.writeValueAsBytes(result);
    } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
      Map<String, Object> details = new LinkedHashMap<>();
      details.put("field", "result");
      throw new DomainException(
          DomainErrorCode.INVALID_COMMAND_PAYLOAD,
          "Manual artifact result could not be serialized",
          details);
    }
  }

  private static Map<String, byte[]> decodeArtifactContents(Map<String, String> base64Contents) {
    if (base64Contents == null || base64Contents.isEmpty()) {
      return Map.of();
    }
    Map<String, byte[]> decoded = new LinkedHashMap<>();
    for (Map.Entry<String, String> entry : base64Contents.entrySet()) {
      try {
        decoded.put(entry.getKey(), java.util.Base64.getDecoder().decode(entry.getValue()));
      } catch (IllegalArgumentException error) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("field", "artifactContents");
        details.put("key", entry.getKey());
        throw new DomainException(
            DomainErrorCode.INVALID_COMMAND_PAYLOAD,
            "artifactContents value is not valid base64",
            details);
      }
    }
    return decoded;
  }

  @PostMapping(
      value = "/{workflowRunId}/clarifications/{clarificationId}/answer",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      operationId = "answerClarification",
      summary = "Answer an open clarification on a spec")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Answer recorded against the clarification."),
    @ApiResponse(
        responseCode = "400",
        description =
            "MISSING_IDEMPOTENCY_KEY, INVALID_IDEMPOTENCY_KEY, INVALID_COMMAND_PAYLOAD, INVALID_ID_PREFIX.",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                schema = @Schema(implementation = ProblemDetailsResponse.class))),
    @ApiResponse(
        responseCode = "404",
        description = "RUN_NOT_FOUND, CLARIFICATION_NOT_FOUND, or ARTIFACT_RECORD_NOT_FOUND.",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                schema = @Schema(implementation = ProblemDetailsResponse.class))),
    @ApiResponse(
        responseCode = "409",
        description =
            "CLARIFICATION_ARTIFACT_VERSION_MISMATCH, CLARIFICATION_TERMINAL_STATE, ILLEGAL_CLARIFICATION_TRANSITION, IDEMPOTENCY_KEY_CONFLICT, or WORKFLOW_RUN_TERMINAL.",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                schema = @Schema(implementation = ProblemDetailsResponse.class)))
  })
  public ClarificationAnswerResponse answerClarification(
      @PathVariable String workflowRunId,
      @PathVariable String clarificationId,
      @RequestHeader(name = "Idempotency-Key") String idempotencyKey,
      @RequestHeader(name = "X-Actor-Identity", required = false) String actorIdentityHeader,
      HttpServletRequest httpRequest,
      @Valid @RequestBody AnswerClarificationRequest request) {
    rejectMultiValuedIdempotencyKeyHeader(httpRequest);
    requireNonBlankIdempotencyKey(idempotencyKey);
    rejectMultiValuedActorIdentityHeader(httpRequest);
    // Story 2.13 round-4 D-R4-1: see approveSpec javadoc.
    localActorIdentityResolver.requireSafe(actorIdentityHeader);
    String actorIdentity = localActorIdentityResolver.resolve(actorIdentityHeader);
    String correlationId = MdcKeys.sanitizeForLog(MDC.get(MdcKeys.CORRELATION_ID));
    log.info(
        "REST answer-clarification received workflowRunId={} clarificationId={} artifactId={} expectedArtifactVersion={} answerTextLength={} actorIdentity={}",
        MdcKeys.sanitizeForLog(workflowRunId),
        MdcKeys.sanitizeForLog(clarificationId),
        MdcKeys.sanitizeForLog(request.artifactId()),
        request.expectedArtifactVersion(),
        // Story 2.13 round-4 P-R4-10: @NotBlank + @Valid guarantee non-null by the time the
        // controller body executes — dead defensive null-guard removed.
        request.answerText().length(),
        MdcKeys.sanitizeForLog(actorIdentity));
    WorkflowStateChangeResult result =
        workflowCommandService.answerClarification(
            new SubmitClarificationCommand(
                workflowRunId,
                clarificationId,
                request.artifactId(),
                request.expectedArtifactVersion(),
                request.answerText(),
                actorIdentity,
                ActorType.HUMAN,
                idempotencyKey,
                correlationId));
    ClarificationAnswerResponse response =
        ClarificationAnswerResponse.from(result, clarificationId);
    log.info(
        "REST answer-clarification success workflowRunId={} clarificationId={} clarificationStatus={} currentState={}",
        workflowRunId,
        clarificationId,
        response.clarificationStatus(),
        response.currentState());
    return response;
  }

  @PostMapping(
      value = "/{workflowRunId}/retry-workflow",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(operationId = "retryWorkflow", summary = "Retry a failed workflow run")
  public WorkflowStateChangeResponse retry(
      @PathVariable String workflowRunId,
      @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
      @Valid @RequestBody RetryWorkflowRequest request) {
    return WorkflowStateChangeResponse.from(
        workflowCommandService.retryWorkflow(
            new RetryWorkflowCommand(
                workflowRunId,
                request.actorIdentity(),
                request.actorType(),
                idempotencyKey,
                request.correlationId(),
                request.reasonText())));
  }

  @PostMapping(
      value = "/{workflowRunId}/takeover-workflow",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(operationId = "takeoverWorkflow", summary = "Take over a workflow run")
  public WorkflowStateChangeResponse takeover(
      @PathVariable String workflowRunId,
      @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
      @Valid @RequestBody TakeoverWorkflowRequest request) {
    return WorkflowStateChangeResponse.from(
        workflowCommandService.takeoverWorkflow(
            new TakeoverWorkflowCommand(
                workflowRunId,
                request.actorIdentity(),
                request.actorType(),
                idempotencyKey,
                request.correlationId(),
                request.reasonText())));
  }

  @PostMapping(
      value = "/{workflowRunId}/takeover",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      operationId = "takeover",
      summary = "Take over a workflow run for manual developer continuation (story 3.25)",
      description =
          "Stops orchestrator dispatch, cancels all in-flight + queued runner executions, records a"
              + " developer takeover, and transitions the run to the TakenOver terminal state while"
              + " preserving all prior context (artifacts, audit trail, and the active GitHub PR"
              + " link). This action is non-reversible in E3 — Epic 4 will add takeover-revert;"
              + " until then, a taken-over run can only be closed by an operator action.")
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        description =
            "Takeover recorded; run is now TakenOver. This action is non-reversible in E3 — Epic 4"
                + " will add takeover-revert; until then, a taken-over run can only be closed by an"
                + " operator action."),
    @ApiResponse(
        responseCode = "400",
        description =
            "MISSING_IDEMPOTENCY_KEY, INVALID_IDEMPOTENCY_KEY, INVALID_COMMAND_PAYLOAD, INVALID_REVIEWER_ROLE_FOR_ENDPOINT.",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                schema = @Schema(implementation = ProblemDetailsResponse.class))),
    @ApiResponse(
        responseCode = "404",
        description = "RUN_NOT_FOUND.",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                schema = @Schema(implementation = ProblemDetailsResponse.class))),
    @ApiResponse(
        responseCode = "409",
        description = "IDEMPOTENCY_KEY_CONFLICT, ILLEGAL_TRANSITION, or WORKFLOW_RUN_TERMINAL.",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                schema = @Schema(implementation = ProblemDetailsResponse.class)))
  })
  public TakeoverResponse takeover(
      @PathVariable String workflowRunId,
      @RequestHeader(name = "Idempotency-Key") String idempotencyKey,
      @RequestHeader(name = "X-Actor-Identity", required = false) String actorIdentityHeader,
      HttpServletRequest httpRequest,
      @Valid @RequestBody TakeoverRequest request) {
    rejectMultiValuedIdempotencyKeyHeader(httpRequest);
    requireNonBlankIdempotencyKey(idempotencyKey);
    rejectMultiValuedActorIdentityHeader(httpRequest);
    // Story 2.13 round-4 D-R4-1: see approveSpec javadoc.
    localActorIdentityResolver.requireSafe(actorIdentityHeader);
    String actorIdentity = localActorIdentityResolver.resolve(actorIdentityHeader);
    String correlationId = MdcKeys.sanitizeForLog(MDC.get(MdcKeys.CORRELATION_ID));
    // Story 3.25 R8: developer-only endpoint. Validate the raw reviewerRole at the boundary with
    // the
    // shared typed INVALID_REVIEWER_ROLE_FOR_ENDPOINT idiom (story 3.23), then DISCARD it — the
    // rich
    // DeveloperTakeoverService does NOT accept a reviewer role on the command and hard-codes
    // 'developer' on the recovery_actions insert. The validation is request-shape only (UI
    // fail-fast
    // + symmetry with accept-/reject-implementation), so it stays within the thin-controller rule.
    requireDeveloperReviewerRole("takeover", request.reviewerRole());
    log.info(
        "REST takeover received workflowRunId={} actorIdentity={} reasonLength={}",
        MdcKeys.sanitizeForLog(workflowRunId),
        MdcKeys.sanitizeForLog(actorIdentity),
        // @NotBlank + @Valid guarantee non-null reasonText by the time the body executes; log its
        // length only — the free-form developer prose is never logged verbatim.
        request.reasonText().length());
    TakeoverResult result =
        developerTakeoverService.takeoverWorkflow(
            new TakeoverWorkflowCommand(
                workflowRunId,
                actorIdentity,
                ActorType.HUMAN,
                idempotencyKey,
                correlationId,
                request.reasonText()));
    TakeoverResponse response = TakeoverResponse.from(result);
    log.info(
        "REST takeover success workflowRunId={} currentState={} recoveryActionId={} cancelledInFlightCount={} cancelledQueuedCount={} replayed={}",
        workflowRunId,
        response.currentState(),
        response.recoveryActionId(),
        response.cancelledInFlightCount(),
        response.cancelledQueuedCount(),
        response.replayed());
    return response;
  }

  @PostMapping(
      value = "/{workflowRunId}/archive",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      operationId = "archiveRun",
      summary = "Soft-hide (archive) an obsolete workflow run (story 3d-8)",
      description =
          "Hides a run from the default review queue by setting its archived_at marker and appending"
              + " a governed workflow.archived audit event. Reversible via /unarchive; never deletes"
              + " any row or mutates workflow_events (FR47). Archiving does not change the run's"
              + " workflow state.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Run archived (or idempotent replay)."),
    @ApiResponse(
        responseCode = "400",
        description =
            "MISSING_IDEMPOTENCY_KEY, INVALID_IDEMPOTENCY_KEY, INVALID_COMMAND_PAYLOAD,"
                + " INVALID_ID_PREFIX.",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                schema = @Schema(implementation = ProblemDetailsResponse.class))),
    @ApiResponse(
        responseCode = "404",
        description = "RUN_NOT_FOUND.",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                schema = @Schema(implementation = ProblemDetailsResponse.class))),
    @ApiResponse(
        responseCode = "409",
        description = "ARCHIVE_NOT_APPLICABLE (already archived) or IDEMPOTENCY_KEY_CONFLICT.",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                schema = @Schema(implementation = ProblemDetailsResponse.class)))
  })
  public ArchiveRunResponse archive(
      @PathVariable String workflowRunId,
      @RequestHeader(name = "Idempotency-Key") String idempotencyKey,
      @RequestHeader(name = "X-Actor-Identity", required = false) String actorIdentityHeader,
      HttpServletRequest httpRequest,
      @Valid @RequestBody ArchiveRunRequest request) {
    rejectMultiValuedIdempotencyKeyHeader(httpRequest);
    requireNonBlankIdempotencyKey(idempotencyKey);
    rejectMultiValuedActorIdentityHeader(httpRequest);
    localActorIdentityResolver.requireSafe(actorIdentityHeader);
    String actorIdentity = localActorIdentityResolver.resolve(actorIdentityHeader);
    String correlationId = MdcKeys.sanitizeForLog(MDC.get(MdcKeys.CORRELATION_ID));
    log.info(
        "REST archive received workflowRunId={} actorIdentity={} reasonLength={}",
        MdcKeys.sanitizeForLog(workflowRunId),
        MdcKeys.sanitizeForLog(actorIdentity),
        request.reason().length());
    WorkflowArchiveResult result =
        workflowArchiveService.archiveRun(
            new ArchiveRunCommand(
                workflowRunId,
                actorIdentity,
                ActorType.HUMAN,
                idempotencyKey,
                correlationId,
                request.reason()));
    ArchiveRunResponse response = ArchiveRunResponse.from(result);
    log.info(
        "REST archive success workflowRunId={} currentState={} archivedAt={} replay={}",
        workflowRunId,
        response.currentState(),
        response.archivedAt(),
        result.replay());
    return response;
  }

  @PostMapping(
      value = "/{workflowRunId}/unarchive",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      operationId = "unarchiveRun",
      summary = "Reverse a soft-hide (un-archive) of a workflow run (story 3d-8)",
      description =
          "Clears a run's archived_at marker and appends a governed workflow.unarchived audit event,"
              + " returning it to the default review queue. Symmetric reversal of /archive.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Run un-archived (or idempotent replay)."),
    @ApiResponse(
        responseCode = "400",
        description =
            "MISSING_IDEMPOTENCY_KEY, INVALID_IDEMPOTENCY_KEY, INVALID_COMMAND_PAYLOAD,"
                + " INVALID_ID_PREFIX.",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                schema = @Schema(implementation = ProblemDetailsResponse.class))),
    @ApiResponse(
        responseCode = "404",
        description = "RUN_NOT_FOUND.",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                schema = @Schema(implementation = ProblemDetailsResponse.class))),
    @ApiResponse(
        responseCode = "409",
        description = "ARCHIVE_NOT_APPLICABLE (not archived) or IDEMPOTENCY_KEY_CONFLICT.",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                schema = @Schema(implementation = ProblemDetailsResponse.class)))
  })
  public ArchiveRunResponse unarchive(
      @PathVariable String workflowRunId,
      @RequestHeader(name = "Idempotency-Key") String idempotencyKey,
      @RequestHeader(name = "X-Actor-Identity", required = false) String actorIdentityHeader,
      HttpServletRequest httpRequest,
      @Valid @RequestBody UnarchiveRunRequest request) {
    rejectMultiValuedIdempotencyKeyHeader(httpRequest);
    requireNonBlankIdempotencyKey(idempotencyKey);
    rejectMultiValuedActorIdentityHeader(httpRequest);
    localActorIdentityResolver.requireSafe(actorIdentityHeader);
    String actorIdentity = localActorIdentityResolver.resolve(actorIdentityHeader);
    String correlationId = MdcKeys.sanitizeForLog(MDC.get(MdcKeys.CORRELATION_ID));
    log.info(
        "REST unarchive received workflowRunId={} actorIdentity={}",
        MdcKeys.sanitizeForLog(workflowRunId),
        MdcKeys.sanitizeForLog(actorIdentity));
    WorkflowArchiveResult result =
        workflowArchiveService.unarchiveRun(
            new UnarchiveRunCommand(
                workflowRunId,
                actorIdentity,
                ActorType.HUMAN,
                idempotencyKey,
                correlationId,
                request.reason()));
    ArchiveRunResponse response = ArchiveRunResponse.from(result);
    log.info(
        "REST unarchive success workflowRunId={} currentState={} replay={}",
        workflowRunId,
        response.currentState(),
        result.replay());
    return response;
  }

  /**
   * Story 2.13 review (P10): Spring treats an empty/whitespace {@code Idempotency-Key} header as
   * present, so {@code MissingRequestHeaderException} is never raised and the missing-header
   * Problem Details advice never fires. This guard collapses null/blank values back to {@code
   * MISSING_IDEMPOTENCY_KEY} at the controller boundary so the AC3 contract matches the OpenAPI
   * documentation for callers that send {@code Idempotency-Key:} (no value).
   */
  private static void requireNonBlankIdempotencyKey(String idempotencyKey) {
    if (idempotencyKey == null || idempotencyKey.isBlank()) {
      // Story 2.13 round-4 P-R4-11: LinkedHashMap matches the deterministic key-ordering pattern
      // used by every other typed-rejection details payload in this controller. Future additions
      // of a second detail key cannot silently flip to HashMap iteration order and break the
      // idempotent-replay byte-equality contract pinned by the slice tests.
      Map<String, Object> details = new LinkedHashMap<>();
      details.put("header", "Idempotency-Key");
      throw new DomainException(
          DomainErrorCode.MISSING_IDEMPOTENCY_KEY,
          "Missing required header: Idempotency-Key",
          details);
    }
  }

  /**
   * Story 3.23 R4: the {@code accept-implementation} (and, when it lands, the story 3.24 {@code
   * reject-implementation}) endpoint is developer-only. The body {@code reviewerRole} must equal
   * {@code developer} verbatim; anything else — including null/blank — is rejected at the
   * controller boundary as a typed {@link DomainErrorCode#INVALID_REVIEWER_ROLE_FOR_ENDPOINT}.
   * Unlike {@code approve-spec}, the value is deliberately NOT routed through {@code
   * ApprovalReviewerRoleResolver.resolveFor(...)} (whose blank-fallback default {@code
   * product_reviewer} would mask the mismatch). This is request-shape validation only (no domain
   * decision), so it stays within the story 1.11 thin-controller ArchUnit rule. The returned
   * trimmed value is the canonical {@code developer} role placed on the command.
   */
  private static String requireDeveloperReviewerRole(String action, String reviewerRole) {
    String trimmed = reviewerRole == null ? null : reviewerRole.trim();
    if (!"developer".equals(trimmed)) {
      // Story 3.23 logging task: audit the boundary rejection before throwing so the typed
      // INVALID_REVIEWER_ROLE_FOR_ENDPOINT path is observable without re-deploying. The shared
      // helper is invoked from both accept-implementation (3.23) and reject-implementation (3.24);
      // the {action} label keeps the audit line attributed to the correct endpoint.
      log.warn(
          "REST {} rejected: reviewerRole must be 'developer' actualReviewerRole={}",
          action,
          MdcKeys.sanitizeForLog(reviewerRole));
      Map<String, Object> details = new LinkedHashMap<>();
      details.put("field", "reviewerRole");
      details.put("expected", "developer");
      details.put("actual", reviewerRole);
      throw new DomainException(
          DomainErrorCode.INVALID_REVIEWER_ROLE_FOR_ENDPOINT,
          "Reviewer role must be 'developer' for this endpoint",
          details);
    }
    return trimmed;
  }

  /**
   * Story 3.24 R4: the {@code reject-implementation} endpoint accepts only the
   * <strong>developer</strong>-subset {@link RejectionTaxonomy} values (story 3.21). A valid-but-
   * product value (e.g. {@code MISSING_SCOPE}) is rejected at the controller boundary as a typed
   * {@link DomainErrorCode#INVALID_REJECTION_TAXONOMY}. An entirely-unknown enum string never
   * reaches here — Jackson deserialization fails first and the {@code
   * HttpMessageNotReadableException} advice surfaces {@code INVALID_COMMAND_PAYLOAD}. This is
   * request-shape validation only (no domain decision), staying within the story 1.11
   * thin-controller ArchUnit rule; the service keeps its own defense-in-depth subset guard.
   */
  private static void requireDeveloperRejectionTaxonomy(RejectionTaxonomy taggedFeedback) {
    if (!taggedFeedback.isDeveloperValue()) {
      // Story 3.24 logging task: audit the boundary rejection before throwing so the typed
      // INVALID_REJECTION_TAXONOMY path is observable without re-deploying.
      log.warn(
          "REST reject-implementation rejected: taggedFeedback must be a developer-subset value taggedFeedback={}",
          MdcKeys.sanitizeForLog(taggedFeedback.value()));
      Map<String, Object> details = new LinkedHashMap<>();
      details.put("field", "taggedFeedback");
      details.put("value", taggedFeedback.value());
      throw new DomainException(
          DomainErrorCode.INVALID_REJECTION_TAXONOMY,
          "Tagged feedback must be a developer-rejection taxonomy value",
          details);
    }
  }

  /**
   * Story 2.13 review (P9): Spring's {@code @RequestHeader(name = "X-Actor-Identity")} silently
   * coalesces multiple values into a comma-delimited string, which would let a misconfigured client
   * (or a forwarded-header chain) inject a non-canonical actor identity into the audit trail. This
   * guard reads the raw {@code HttpServletRequest} header list and rejects requests carrying more
   * than one {@code X-Actor-Identity} header as {@link
   * org.dradgo.domain.registry.DomainErrorCode#INVALID_COMMAND_PAYLOAD} so the failure surfaces to
   * the caller as a typed Problem Details body rather than corrupting downstream audit columns.
   */
  private static void rejectMultiValuedActorIdentityHeader(HttpServletRequest httpRequest) {
    rejectMultiValuedHeader(httpRequest, "X-Actor-Identity");
  }

  /**
   * Story 2.13 round-3 review P8: mirror of {@link #rejectMultiValuedActorIdentityHeader} for the
   * {@code Idempotency-Key} header. Spring coalesces duplicate header values into a comma-joined
   * string that {@link org.dradgo.application.idempotency.IdempotencyKeyValidator} would reject,
   * but the error code path then surfaces as {@code INVALID_IDEMPOTENCY_KEY} instead of a typed
   * envelope-violation; worse, a hostile proxy could shape the comma-joined value to look benign to
   * the validator and skew idempotency partitioning across distinct callers. This guard fails fast
   * at the controller boundary so the failure is unambiguously attributable to header duplication
   * rather than caller key format.
   */
  private static void rejectMultiValuedIdempotencyKeyHeader(HttpServletRequest httpRequest) {
    rejectMultiValuedHeader(httpRequest, "Idempotency-Key");
  }

  private static void rejectMultiValuedHeader(HttpServletRequest httpRequest, String headerName) {
    if (httpRequest == null) {
      return;
    }
    Enumeration<String> headers = httpRequest.getHeaders(headerName);
    if (headers == null) {
      return;
    }
    List<String> values = Collections.list(headers);
    if (values.size() > 1) {
      Map<String, Object> details = new LinkedHashMap<>();
      details.put("header", headerName);
      details.put("valueCount", values.size());
      throw new DomainException(
          DomainErrorCode.INVALID_COMMAND_PAYLOAD,
          "Multiple " + headerName + " headers supplied; exactly one is allowed",
          details);
    }
    // Story 2.13 round-4 P-R4-2: RFC 7230 §3.2.2 permits intermediaries to fold multiple same-name
    // headers into a single comma-separated field-line. servletRequest.getHeaders(...) then returns
    // a single element and the count guard above misses the duplication. A bare comma in either
    // X-Actor-Identity or Idempotency-Key has no legitimate use (the actor identity is a single
    // operator handle; the idempotency key format is alphanumeric+dash) so we reject as a typed
    // INVALID_COMMAND_PAYLOAD before the comma-joined value reaches the resolver / validator and
    // skews the audit trail or idempotency partitioning.
    if (!values.isEmpty() && values.get(0) != null && values.get(0).indexOf(',') >= 0) {
      Map<String, Object> details = new LinkedHashMap<>();
      details.put("header", headerName);
      details.put("reason", "comma_folded_multi_value");
      throw new DomainException(
          DomainErrorCode.INVALID_COMMAND_PAYLOAD,
          "Comma-folded multi-value "
              + headerName
              + " header detected; exactly one value is allowed",
          details);
    }
  }
}
