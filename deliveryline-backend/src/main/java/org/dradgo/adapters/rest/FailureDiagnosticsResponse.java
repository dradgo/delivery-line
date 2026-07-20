package org.dradgo.adapters.rest;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.List;
import org.dradgo.application.workflow.WorkflowInspectionService.FailureDiagnostics;
import org.dradgo.application.workflow.WorkflowInspectionService.IntegrationSyncStatusView;
import org.dradgo.application.workflow.WorkflowInspectionService.RecommendedActionView;
import org.dradgo.application.workflow.WorkflowInspectionService.RunnerLogReferenceView;

/**
 * Story 4.4 (AC4) — response body for {@code GET
 * /api/v1/workflows/{workflowRunId}/failure-diagnostics}. Flattens {@link FailureDiagnostics} into
 * a stable wire shape for the operator deep-dive panel: the NFR7 five-questions fields above the
 * fold, the {@code integrationSyncStatus: { linear, github }} pair, an optional runner-log
 * reference (its {@code runnerExecutionId} drives the download link), and the safety-ranked
 * recommended actions. Nullable fields are documented, never {@link java.util.Optional}.
 */
public record FailureDiagnosticsResponse(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "Failed") String currentState,
    @Schema(nullable = true, example = "execution") String failedStage,
    @Schema(nullable = true, example = "Executing") String lastSuccessfulStage,
    @Schema(nullable = true, example = "runner_timeout") String failureCategory,
    @Schema(nullable = true, description = "Redacted, control-char-stripped failure reason.")
        String failureReason,
    @Schema(nullable = true) OffsetDateTime failureTimestamp,
    @Schema(nullable = true) OffsetDateTime lastActivityTimestamp,
    @Schema(nullable = true, example = "corr_abc123") String correlationId,
    @Schema(nullable = true, example = "Executing") String lastGoodState,
    @Schema(nullable = true, description = "Why recovery is currently blocked, if it is.")
        String currentBlockingReason,
    @Schema(nullable = true, example = "retry") String nextSafeAction,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            description = "NFR7 'who acted' — latest governed actor, or 'system'.",
            example = "local-operator")
        String lastActorIdentity,
    @Schema(nullable = true) RunnerLogReferenceResponse runnerLogReference,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        IntegrationSyncStatusPairResponse integrationSyncStatus,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        List<RecommendedActionResponse> recommendedRecoveryActions) {

  /**
   * A single safety-ranked recovery recommendation (advisory; only {@code retry} is wired today).
   */
  @Schema(name = "RecommendedAction")
  public record RecommendedActionResponse(
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "retry") String actionType,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "safe") String safetyLevel,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String reason,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String precondition) {

    static RecommendedActionResponse from(RecommendedActionView view) {
      return new RecommendedActionResponse(
          view.actionType(), view.safetyLevel(), view.reason(), view.precondition());
    }
  }

  /** Per-integration sync status; {@code stale}/{@code failed} is the FE drift flag. */
  @Schema(name = "IntegrationSyncStatus")
  public record IntegrationSyncStatusResponse(
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "linear")
          String integrationType,
      @Schema(nullable = true, example = "LIN-123") String externalRef,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "synced") String syncStatus,
      @Schema(nullable = true) OffsetDateTime lastSyncAt) {

    static IntegrationSyncStatusResponse from(IntegrationSyncStatusView view) {
      if (view == null) {
        return null;
      }
      return new IntegrationSyncStatusResponse(
          view.integrationType(), view.externalRef(), view.syncStatus(), view.lastSyncAt());
    }
  }

  /** The {@code { linear, github }} sync-status pair; either link may be null. */
  @Schema(name = "IntegrationSyncStatusPair")
  public record IntegrationSyncStatusPairResponse(
      @Schema(nullable = true) IntegrationSyncStatusResponse linear,
      @Schema(nullable = true) IntegrationSyncStatusResponse github) {}

  /**
   * Content-free reference to the redacted runner log; {@code runnerExecutionId} drives download.
   */
  @Schema(name = "FailureRunnerLogReference")
  public record RunnerLogReferenceResponse(
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "rex_abc123")
          String runnerExecutionId,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String referencePath,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long byteSize,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "shareable-redacted")
          String classification,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int redactionCount) {

    static RunnerLogReferenceResponse from(RunnerLogReferenceView view) {
      if (view == null) {
        return null;
      }
      return new RunnerLogReferenceResponse(
          view.runnerExecutionId(),
          view.referencePath(),
          view.byteSize(),
          view.classification(),
          view.redactionCount());
    }
  }

  public static FailureDiagnosticsResponse from(FailureDiagnostics diagnostics) {
    return new FailureDiagnosticsResponse(
        diagnostics.currentState().value(),
        diagnostics.failedStage(),
        diagnostics.lastSuccessfulStage(),
        diagnostics.failureCategory(),
        diagnostics.failureReason(),
        diagnostics.failureTimestamp(),
        diagnostics.lastActivityTimestamp(),
        diagnostics.correlationId(),
        diagnostics.lastGoodState(),
        diagnostics.currentBlockingReason(),
        diagnostics.nextSafeAction(),
        diagnostics.lastActorIdentity(),
        RunnerLogReferenceResponse.from(diagnostics.runnerLogReference()),
        new IntegrationSyncStatusPairResponse(
            IntegrationSyncStatusResponse.from(diagnostics.linearSyncStatus()),
            IntegrationSyncStatusResponse.from(diagnostics.githubSyncStatus())),
        diagnostics.recommendedRecoveryActions().stream()
            .map(RecommendedActionResponse::from)
            .toList());
  }
}
