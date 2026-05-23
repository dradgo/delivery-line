package org.dradgo.adapters.rest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.dradgo.application.workflow.ApprovalReviewerRoleResolver;
import org.dradgo.application.workflow.WorkflowCommandService;
import org.dradgo.application.workflow.WorkflowInspectionService;
import org.dradgo.application.workflow.commands.ApproveSpecCommand;
import org.dradgo.application.workflow.commands.RejectSpecCommand;
import org.dradgo.application.workflow.commands.RetryWorkflowCommand;
import org.dradgo.application.workflow.commands.SubmitWorkflowCommand;
import org.dradgo.application.workflow.commands.TakeoverWorkflowCommand;
import org.dradgo.domain.registry.WorkflowState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * faces of one source of truth. The read endpoints are additive: the pre-existing command endpoints
 * are unchanged.
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

  public WorkflowController(
      WorkflowCommandService workflowCommandService,
      WorkflowInspectionService workflowInspectionService,
      ApprovalReviewerRoleResolver approvalReviewerRoleResolver) {
    this.workflowCommandService = workflowCommandService;
    this.workflowInspectionService = workflowInspectionService;
    this.approvalReviewerRoleResolver = approvalReviewerRoleResolver;
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
      @Parameter(description = "Max rows to return (clamped to 1..200).")
          @RequestParam(name = "limit", required = false, defaultValue = "50")
          int limit) {
    WorkflowState stateFilter =
        (state == null || state.isBlank()) ? null : WorkflowState.fromValue(state, "state");
    log.info(
        "REST list workflows received stateFilter={} limit={}",
        stateFilter == null ? "<all>" : stateFilter.value(),
        limit);
    List<WorkflowSummaryResponse> summaries =
        workflowInspectionService.listRuns(stateFilter, limit).stream()
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
    // getStatus() prefix-validates (INVALID_ID_PREFIX) before any lookup and throws RUN_NOT_FOUND.
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

  // ---------------------------------------------------------------------------
  // Command endpoints (stories 1.7 / 1.15). Unchanged by story 6.9 — DO NOT remove.
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
                request.linearTicketReference())));
  }

  @PostMapping(
      value = "/{workflowRunId}/approve-spec",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(operationId = "approveSpec", summary = "Approve a run's specification")
  public WorkflowStateChangeResponse approveSpec(
      @PathVariable String workflowRunId,
      @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
      @Valid @RequestBody ApproveSpecRequest request) {
    return WorkflowStateChangeResponse.from(
        workflowCommandService.approveSpec(
            new ApproveSpecCommand(
                workflowRunId,
                request.artifactId(),
                request.artifactVersion(),
                request.contextVersion(),
                request.actorIdentity(),
                request.actorType(),
                idempotencyKey,
                request.correlationId(),
                approvalReviewerRoleResolver.resolveFor(request.reviewerRole()),
                request.reason())));
  }

  @PostMapping(
      value = "/{workflowRunId}/reject-spec",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(operationId = "rejectSpec", summary = "Reject a run's specification")
  public WorkflowStateChangeResponse rejectSpec(
      @PathVariable String workflowRunId,
      @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
      @Valid @RequestBody RejectSpecRequest request) {
    return WorkflowStateChangeResponse.from(
        workflowCommandService.rejectSpec(
            new RejectSpecCommand(
                workflowRunId,
                request.artifactId(),
                request.artifactVersion(),
                request.contextVersion(),
                request.actorIdentity(),
                request.actorType(),
                idempotencyKey,
                request.correlationId(),
                request.reasonText())));
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
}
