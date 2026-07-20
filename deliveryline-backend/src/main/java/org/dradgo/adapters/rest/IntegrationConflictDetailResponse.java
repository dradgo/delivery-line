package org.dradgo.adapters.rest;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.dradgo.application.integration.conflict.ConflictReconciliationSuggester.SuggestedDecision;
import org.dradgo.application.integration.conflict.ConflictResolutionView;
import org.dradgo.application.integration.conflict.IntegrationConflictService.ConflictDetail;

/**
 * Story 4.18 (AC3) — wire shape for {@code GET /api/v1/integration-conflicts/{conflictId}}. Carries
 * both the internal + external state snapshots (raw JSON strings — the FE parses them) and the
 * per-category safety-ranked {@link ReconciliationDecision} suggestions the story-4.23 dialog
 * pre-fills from. {@code resolvedAt} is nullable (an unresolved conflict has none).
 */
@Schema(
    name = "IntegrationConflictDetail",
    description =
        "Full integration-conflict detail with both state snapshots + ranked suggestions.")
public record IntegrationConflictDetailResponse(
    @Schema(description = "Conflict public id.", example = "icf_abc123") String conflictId,
    @Schema(description = "Owning run public id.", example = "run_abc123") String workflowRunId,
    @Schema(description = "Owning integration link public id.", example = "ilk_abc123")
        String integrationLinkId,
    @Schema(description = "Integration type wire string.", example = "github_pr", nullable = true)
        String integrationType,
    @Schema(
            description = "Conflict category wire string.",
            example = "external_state_advanced",
            allowableValues = {
              "external_state_advanced",
              "external_state_reverted",
              "external_resource_removed",
              "metadata_drift",
              "link_broken"
            })
        String conflictCategory,
    @Schema(description = "External reference (PR/ticket ref).", example = "octo/repo#7")
        String externalRef,
    @Schema(
            description = "Resolution timestamp (ISO-8601 UTC), or null while unresolved.",
            nullable = true)
        OffsetDateTime resolvedAt,
    @Schema(description = "External state snapshot as a raw JSON string.", nullable = true)
        String externalStateSnapshot,
    @Schema(description = "Internal state snapshot as a raw JSON string.", nullable = true)
        String internalStateSnapshot,
    @Schema(description = "Safety-ranked reconciliation decision options (safe first).")
        List<SuggestedDecisionResponse> suggestedDecisions) {

  public static IntegrationConflictDetailResponse from(ConflictDetail detail) {
    ConflictResolutionView view = detail.view();
    List<SuggestedDecisionResponse> suggestions =
        detail.suggestedDecisions().stream().map(SuggestedDecisionResponse::from).toList();
    return new IntegrationConflictDetailResponse(
        view.conflictId(),
        view.workflowRunId(),
        view.integrationLinkId(),
        view.integrationType(),
        view.conflictCategory(),
        view.externalRef(),
        view.resolvedAt() == null ? null : view.resolvedAt().withOffsetSameInstant(ZoneOffset.UTC),
        view.externalStateSnapshot(),
        view.internalStateSnapshot(),
        suggestions);
  }

  /** Story 4.18 (AC3) — one safety-ranked reconciliation option. */
  @Schema(
      name = "SuggestedReconciliationDecision",
      description = "A reconciliation decision option with a coarse safety label.")
  public record SuggestedDecisionResponse(
      @Schema(
              description = "Reconciliation decision wire string.",
              example = "accept_external_state",
              allowableValues = {
                "accept_external_state",
                "accept_internal_state",
                "mark_completed_externally",
                "mark_failed_externally"
              })
          String decision,
      @Schema(
              description = "Coarse safety label.",
              example = "safe",
              allowableValues = {"safe", "risky"})
          String safety) {

    public static SuggestedDecisionResponse from(SuggestedDecision suggestion) {
      return new SuggestedDecisionResponse(suggestion.decision().value(), suggestion.safety());
    }
  }
}
