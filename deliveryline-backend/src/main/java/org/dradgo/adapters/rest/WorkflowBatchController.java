package org.dradgo.adapters.rest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.dradgo.application.observability.MdcKeys;
import org.dradgo.application.workflow.WorkflowBatchSubmissionService;
import org.dradgo.application.workflow.commands.SubmitBatchCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Story 3.18 — REST surface for batch workflow submission.
 *
 * <p>Carried as a dedicated controller (rather than another method on {@code WorkflowController})
 * so the new {@link WorkflowBatchSubmissionService} dependency does not fan out into the ~10
 * existing {@code @WebMvcTest(controllers = WorkflowController.class)} slices, and so the batch
 * concern stays isolated. It is auto-covered by the existing {@code REST_CONTROLLERS_*} ArchUnit
 * rules (story 3.18 Task 8 note). Thin by construction — it depends only on the application service
 * surface.
 *
 * <p>The endpoint ALWAYS returns 200 with the {@link BatchSubmissionResponse}; per-ticket
 * rejections live in the response body, never a non-2xx status — including a mid-batch {@code
 * RUNNER_QUEUE_FULL}, which the service catches per-ticket and reports as rejected outcomes rather
 * than surfacing as a 503. Non-2xx is reserved for whole-batch failures that abort before the loop
 * — a malformed payload (400) or an idempotency-key conflict (409).
 */
@RestController
@Validated
@RequestMapping("/api/v1/workflows")
@Tag(name = "Workflows", description = "Inspect and command governed workflow runs.")
public class WorkflowBatchController {

  private static final Logger log = LoggerFactory.getLogger(WorkflowBatchController.class);

  private final WorkflowBatchSubmissionService workflowBatchSubmissionService;

  public WorkflowBatchController(WorkflowBatchSubmissionService workflowBatchSubmissionService) {
    this.workflowBatchSubmissionService = workflowBatchSubmissionService;
  }

  @PostMapping(
      value = "/batch",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      operationId = "submitBatch",
      summary = "Submit a batch of workflow tickets (best-effort, per-ticket outcomes)")
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        description =
            "Batch accepted; body carries per-ticket queued/rejected outcomes (a rejected ticket"
                + " does NOT make the batch non-2xx)."),
    @ApiResponse(
        responseCode = "400",
        description =
            "MISSING_IDEMPOTENCY_KEY, INVALID_IDEMPOTENCY_KEY, or INVALID_COMMAND_PAYLOAD (empty"
                + " list, oversize, or above the configured batch maximum).",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                schema = @Schema(implementation = ProblemDetailsResponse.class))),
    @ApiResponse(
        responseCode = "409",
        description = "IDEMPOTENCY_KEY_CONFLICT (same key, different fingerprint).",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                schema = @Schema(implementation = ProblemDetailsResponse.class)))
  })
  public BatchSubmissionResponse submitBatch(
      @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
      @Valid @RequestBody BatchSubmissionRequest request) {
    log.info(
        "REST submit-batch received tickets={} actorIdentity={}",
        request.linearTicketReferences().size(),
        MdcKeys.sanitizeForLog(request.actorIdentity()));
    BatchSubmissionResponse response =
        BatchSubmissionResponse.from(
            workflowBatchSubmissionService.submitBatch(
                new SubmitBatchCommand(
                    request.linearTicketReferences(),
                    request.actorIdentity(),
                    request.actorType(),
                    idempotencyKey,
                    request.correlationId())));
    log.info(
        "REST submit-batch success batchId={} queuedCount={} rejectedCount={}",
        response.batchId(),
        response.queuedCount(),
        response.rejectedCount());
    return response;
  }
}
