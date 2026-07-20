package org.dradgo.adapters.rest;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.dradgo.application.recovery.RerunFromStepPreviewResult;

/**
 * Story 4.22 (AC5) — response body for the <strong>non-mutating</strong> {@code GET
 * /api/v1/workflows/{workflowRunId}/preview-rerun-from-step?targetStep=X}. Mapped from the story
 * 4.22 {@link RerunFromStepPreviewResult}; carries the resolved target step (echoed back so the
 * caller can correlate the answer to the requested step) plus the audit lists a fresh rerun WOULD
 * supersede / invalidate.
 *
 * <p><strong>Both id lists are REQUIRED + never-null</strong> (mirror {@link
 * RerunFromStepResponse#supersededArtifactIds()} / {@link
 * RerunFromStepResponse#invalidatedApprovalIds()}) — empty when the run has nothing at/beyond the
 * step / no current approval, so the generated TS client can rely on the arrays existing rather
 * than null-guarding. {@code workflowRunId} + {@code targetStep} are REQUIRED structural echoes.
 */
public record PreviewRerunFromStepResponse(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String workflowRunId,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String targetStep,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<String> supersededArtifactIds,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<String> invalidatedApprovalIds) {

  /**
   * Maps the story 4.22 {@link RerunFromStepPreviewResult} onto the wire DTO. {@code workflowRunId}
   * is passed explicitly from the {@code @PathVariable} (the result carries no run id); {@code
   * targetStep} is the controller's normalized ({@code .strip()}) request token, echoed so the
   * caller can correlate the answer to the step it asked about.
   */
  public static PreviewRerunFromStepResponse from(
      String workflowRunId, String targetStep, RerunFromStepPreviewResult result) {
    return new PreviewRerunFromStepResponse(
        workflowRunId, targetStep, result.supersededArtifactIds(), result.invalidatedApprovalIds());
  }
}
