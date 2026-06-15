package org.dradgo.adapters.rest;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.dradgo.application.workflow.WorkflowInspectionService.RunnerQueueStatus;
import org.dradgo.application.workflow.WorkflowInspectionService.WorkerStatus;

/**
 * Story 3.19 (AC4) — response body for {@code GET /api/v1/runner-queue/status}. Translates the
 * application-layer {@link RunnerQueueStatus} typed view (AC1) into the camelCase wire shape. Per
 * AC10 this and {@code WorkflowInspectionService} are the only places the typed view is referenced
 * outside the CLI surface — the controller stays thin and never re-derives any count.
 */
public record RunnerQueueStatusResponse(
    @Schema(requiredMode = RequiredMode.REQUIRED) int poolSize,
    @Schema(requiredMode = RequiredMode.REQUIRED) long activeWorkers,
    @Schema(requiredMode = RequiredMode.REQUIRED) long idleWorkers,
    @Schema(requiredMode = RequiredMode.REQUIRED) long queueDepth,
    @Schema(requiredMode = RequiredMode.NOT_REQUIRED) String oldestQueuedAt,
    @Schema(requiredMode = RequiredMode.REQUIRED) long oldestQueuedAgeSeconds,
    @Schema(requiredMode = RequiredMode.REQUIRED) long inFlightExecutions,
    @Schema(requiredMode = RequiredMode.REQUIRED) long recentThroughputPerMinute,
    @Schema(requiredMode = RequiredMode.REQUIRED) long staleQueuedCount,
    @Schema(requiredMode = RequiredMode.REQUIRED) long staleDispatchedCount,
    @Schema(requiredMode = RequiredMode.REQUIRED) List<WorkerStatusResponse> workers) {

  public static RunnerQueueStatusResponse from(RunnerQueueStatus view) {
    return new RunnerQueueStatusResponse(
        view.poolSize(),
        view.activeWorkers(),
        view.idleWorkers(),
        view.queueDepth(),
        view.oldestQueuedAt() == null
            ? null
            : DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(view.oldestQueuedAt()),
        view.oldestQueuedAgeSeconds(),
        view.inFlightExecutions(),
        view.recentThroughputPerMinute(),
        view.staleQueuedCount(),
        view.staleDispatchedCount(),
        view.workers().stream().map(WorkerStatusResponse::from).toList());
  }

  /** Per-worker current-work entry (one per busy worker). */
  public record WorkerStatusResponse(
      @Schema(requiredMode = RequiredMode.REQUIRED) String workerId,
      @Schema(requiredMode = RequiredMode.REQUIRED) String state,
      @Schema(requiredMode = RequiredMode.NOT_REQUIRED) String currentRunnerExecutionId,
      @Schema(requiredMode = RequiredMode.NOT_REQUIRED) String currentWorkflowRunId,
      @Schema(requiredMode = RequiredMode.NOT_REQUIRED) String dispatchedAt,
      @Schema(requiredMode = RequiredMode.NOT_REQUIRED) String currentStage) {

    public static WorkerStatusResponse from(WorkerStatus worker) {
      return new WorkerStatusResponse(
          worker.workerId(),
          worker.state(),
          worker.currentRunnerExecutionId(),
          worker.currentWorkflowRunId(),
          worker.dispatchedAt() == null
              ? null
              : DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(worker.dispatchedAt()),
          worker.currentStage());
    }
  }
}
