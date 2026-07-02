package org.dradgo.adapters.rest;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.dradgo.application.workflow.WorkflowInspectionService.StepExecutionView;

/**
 * Story 3g-4 (FR74, AC1) — one runner execution (step) of a run for {@code GET
 * /api/v1/workflows/{workflowRunId}/steps}. Read-only diagnostic projection of a {@link
 * StepExecutionView}: the step's {@code stage}/{@code status}/{@code createdAt} plus 3g-3's three
 * per-execution token counts. Each token count is nullable and passes through verbatim: {@code
 * null} = "not reported" (the 3d-7 posture — never {@code 0}); {@code 0} = "the agent reported
 * zero". {@code totalTokens} is NOT synthesized from input+output.
 */
@Schema(name = "StepExecution", description = "A runner execution (step) of a workflow run.")
public record StepExecutionResponse(
    @Schema(example = "rex_abc123") String runnerExecutionId,
    @Schema(example = "implement", nullable = true) String stage,
    @Schema(example = "completed", nullable = true) String status,
    OffsetDateTime createdAt,
    @Schema(
            description = "Agent input tokens for this step; null when not reported (never 0).",
            nullable = true,
            example = "1024")
        Integer inputTokens,
    @Schema(
            description = "Agent output tokens for this step; null when not reported (never 0).",
            nullable = true,
            example = "512")
        Integer outputTokens,
    @Schema(
            description = "Agent total tokens for this step; null when not reported (never 0).",
            nullable = true,
            example = "1536")
        Integer totalTokens) {

  public static StepExecutionResponse from(StepExecutionView view) {
    return new StepExecutionResponse(
        view.runnerExecutionId(),
        view.stage(),
        view.status(),
        toUtc(view.createdAt()),
        view.inputTokens(),
        view.outputTokens(),
        view.totalTokens());
  }

  private static OffsetDateTime toUtc(OffsetDateTime value) {
    return value == null ? null : value.withOffsetSameInstant(ZoneOffset.UTC);
  }
}
