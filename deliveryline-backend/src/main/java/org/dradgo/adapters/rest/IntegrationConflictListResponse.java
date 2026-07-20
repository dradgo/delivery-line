package org.dradgo.adapters.rest;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.dradgo.application.integration.conflict.ConflictSummary;
import org.dradgo.application.integration.conflict.IntegrationConflictService.ConflictListResult;

/**
 * Story 4.18 (AC2) — wire shape for {@code GET /api/v1/integration-conflicts}. An OBJECT carrier
 * (not a bare array) because the list bundles the global unresolved/resolved counts + their
 * breakdowns + the {@code nextCursor} alongside the {@code conflicts} page. Mirrors the
 * object-carrier + static {@code from} style of {@link AuditQueryResponse} / {@link
 * OperatorRunSummaryResponse}; enums render to their wire strings.
 */
@Schema(
    name = "IntegrationConflictListResponse",
    description = "Integration-conflict list page with global unresolved/resolved counts.")
public record IntegrationConflictListResponse(
    @Schema(description = "The current page of conflicts (detected_at DESC).")
        List<ConflictSummaryResponse> conflicts,
    @Schema(description = "Total currently-unresolved, non-archived conflicts.", example = "3")
        long totalUnresolved,
    @Schema(description = "Total resolved, non-archived conflicts.", example = "12")
        long totalResolved,
    @Schema(description = "Unresolved-conflict counts keyed by conflict category (wire strings).")
        Map<String, Long> totalUnresolvedByCategory,
    @Schema(
            description =
                "Unresolved-conflict counts keyed by integration (linear/github/unknown).")
        Map<String, Long> totalUnresolvedByIntegration,
    @Schema(
            description =
                "Opaque keyset cursor for the next page, or null on the last page. Echo it back as"
                    + " the cursor query param to fetch more.",
            nullable = true)
        String nextCursor) {

  public static IntegrationConflictListResponse from(ConflictListResult result) {
    List<ConflictSummaryResponse> conflicts =
        result.conflicts().stream().map(ConflictSummaryResponse::from).toList();
    return new IntegrationConflictListResponse(
        conflicts,
        result.totalUnresolved(),
        result.totalResolved(),
        result.totalUnresolvedByCategory(),
        result.totalUnresolvedByIntegration(),
        result.nextCursor());
  }

  /** Story 4.18 (AC2) — one conflict summary row (non-secret ids/refs/states only). */
  @Schema(
      name = "IntegrationConflictSummary",
      description = "One integration-conflict summary row.")
  public record ConflictSummaryResponse(
      @Schema(description = "Conflict public id.", example = "icf_abc123") String conflictId,
      @Schema(description = "Owning integration link public id.", example = "ilk_abc123")
          String integrationLinkId,
      @Schema(description = "Owning run public id.", example = "run_abc123") String workflowRunId,
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
      @Schema(description = "Integration type wire string.", example = "github_pr", nullable = true)
          String integrationType,
      @Schema(description = "External reference (PR/ticket ref).", example = "octo/repo#7")
          String externalRef,
      @Schema(description = "Detection timestamp (ISO-8601 UTC).") OffsetDateTime detectedAt) {

    public static ConflictSummaryResponse from(ConflictSummary summary) {
      return new ConflictSummaryResponse(
          summary.conflictId(),
          summary.integrationLinkId(),
          summary.workflowRunId(),
          summary.conflictCategory(),
          summary.integrationType(),
          summary.externalRef(),
          summary.detectedAt() == null ? null : summary.detectedAt().atOffset(ZoneOffset.UTC));
    }
  }
}
