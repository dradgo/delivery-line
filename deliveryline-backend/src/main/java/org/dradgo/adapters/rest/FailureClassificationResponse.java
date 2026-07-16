package org.dradgo.adapters.rest;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.List;
import org.dradgo.application.workflow.WorkflowInspectionService;
import org.dradgo.application.workflow.WorkflowInspectionService.FailureClassificationView;

/**
 * Story 4.24 (AC2/AC5/AC9) — response body for {@code GET
 * /api/v1/workflows/{workflowRunId}/failure-classification}. A faithful projection of the {@code
 * done} story 4.9 {@link FailureClassificationView}: the run's CURRENT operator-applied taxonomy
 * classification (nullable — {@code null} means never classified) plus the ordered,
 * most-recent-first prior classifications reconstructed from the {@code recovery.failureClassified}
 * event chain.
 *
 * <p><strong>A never-classified run returns 200</strong> with {@code currentTaxonomyValue == null}
 * and {@code priorClassifications == []} (NOT 404) so the FE renders "not yet classified" without
 * special-casing an error. {@code RUN_NOT_FOUND} (404) is reserved for an unknown run id; a
 * malformed id is {@code INVALID_ID_PREFIX} (400). Read-only + idempotent — no {@code
 * Idempotency-Key}, no actor, no {@code role}.
 *
 * <p>{@code workflowRunId} is passed explicitly from the {@code @PathVariable} because the view —
 * like every inspection view — carries no run id. All current-* fields are nullable-documented (a
 * never-classified run leaves them null); {@code priorClassifications} is REQUIRED and never-null
 * (empty list when there are none).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FailureClassificationResponse(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "run_abc123")
        String workflowRunId,
    @Schema(nullable = true, example = "agent_execution_failure") String currentTaxonomyValue,
    @Schema(nullable = true, example = "agent_execution_failure") String currentDisplayLabel,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean deprecated,
    @Schema(nullable = true) String deprecatedReplacementValue,
    @Schema(nullable = true) OffsetDateTime classifiedAt,
    @Schema(nullable = true, example = "local-operator") String classifiedBy,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        List<PriorClassification> priorClassifications) {

  /**
   * One overwritten prior classification, reconstructed from the event chain (most-recent-first).
   */
  @Schema(name = "PriorClassification")
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record PriorClassification(
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "context_gap")
          String taxonomyValue,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "context_gap")
          String displayLabel,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED) OffsetDateTime classifiedAt,
      @Schema(nullable = true, example = "local-operator") String classifiedBy) {

    static PriorClassification from(WorkflowInspectionService.PriorClassification view) {
      return new PriorClassification(
          view.taxonomyValue(), view.displayLabel(), view.classifiedAt(), view.classifiedBy());
    }
  }

  public static FailureClassificationResponse from(
      String workflowRunId, FailureClassificationView view) {
    return new FailureClassificationResponse(
        workflowRunId,
        view.currentTaxonomyValue(),
        view.currentDisplayLabel(),
        view.deprecated(),
        view.deprecatedReplacementValue(),
        view.classifiedAt(),
        view.classifiedBy(),
        view.priorClassifications().stream().map(PriorClassification::from).toList());
  }
}
