package org.dradgo.adapters.rest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.dradgo.application.workflow.WorkflowInspectionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Story 3.19 (AC4/AC9) — REST surface for runner-queue + worker-pool inspection.
 *
 * <p>A dedicated controller at the distinct {@code /api/v1/runner-queue} resource (a sibling of
 * {@code WorkflowController}, NOT a method on it) keeps queue inspection isolated and is
 * auto-covered by the {@code REST_CONTROLLERS_*} ArchUnit rules. Thin by construction: it depends
 * only on {@link WorkflowInspectionService} (the single read seam — AC10) and maps the typed view
 * to {@link RunnerQueueStatusResponse}. Idempotent GET; {@code X-Correlation-Id} is echoed globally
 * by {@code CorrelationIdFilter} (no per-controller work). Rate-limit-friendly for a
 * Grafana/Prometheus scrape.
 */
@RestController
@RequestMapping("/api/v1/runner-queue")
@Tag(name = "Runner Queue", description = "Inspect the runner execution queue + worker pool.")
public class RunnerQueueController {

  private static final Logger log = LoggerFactory.getLogger(RunnerQueueController.class);

  private final WorkflowInspectionService workflowInspectionService;

  public RunnerQueueController(WorkflowInspectionService workflowInspectionService) {
    this.workflowInspectionService = workflowInspectionService;
  }

  @GetMapping(value = "/status", produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      operationId = "getRunnerQueueStatus",
      summary =
          "Runner worker-pool state, queue depth, oldest-queued age, stale counts, and per-worker"
              + " current work. Optional batchId scopes the counts + workers to one batch.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Current runner-queue + worker-pool status."),
    @ApiResponse(
        responseCode = "400",
        description = "INVALID_ID_PREFIX — the supplied batchId is not a bat_ public id.",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                schema = @Schema(implementation = ProblemDetailsResponse.class)))
  })
  public RunnerQueueStatusResponse getStatus(
      @Parameter(description = "Scope queue depth + workers to one batch (bat_...)")
          @RequestParam(name = "batchId", required = false)
          String batchId) {
    log.info("REST runner-queue status received batchId={}", batchId);
    RunnerQueueStatusResponse response =
        RunnerQueueStatusResponse.from(workflowInspectionService.getRunnerQueueStatus(batchId));
    log.info(
        "REST runner-queue status success batchId={} queueDepth={} activeWorkers={}",
        batchId,
        response.queueDepth(),
        response.activeWorkers());
    return response;
  }
}
