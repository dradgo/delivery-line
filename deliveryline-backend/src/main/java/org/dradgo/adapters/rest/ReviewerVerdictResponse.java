package org.dradgo.adapters.rest;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.dradgo.application.workflow.WorkflowInspectionService.ReviewerVerdictView;

/**
 * Story 3d-2 (AC3/AC4/AC6/AC8) — the advisory reviewer verdict for {@code GET
 * /api/v1/workflows/{workflowRunId}/reviewer-verdict}. Presentational + advisory-only: it carries
 * NO governed action (the human approve/reject actions are unchanged, AC8) and never gates the
 * Decision Bar. The frontend renders the panel only when a verdict / pending / unavailable-with-
 * reason exists, and renders NOTHING for a no-binding project ({@code state == "unavailable"} +
 * {@code unavailableReason == "no_reviewer_configured"}, AC5).
 *
 * <p>Server-derived state (DD-5): the frontend stays dumb. Verdict fields are non-null only when
 * {@code state == "available"}; {@code unavailableReason} is non-null only when {@code state ==
 * "unavailable"}.
 */
@Schema(
    name = "ReviewerVerdict",
    description =
        "Advisory second-opinion verdict surfaced beside the WaitingForReview Decision Bar.")
public record ReviewerVerdictResponse(
    @Schema(
            description = "Verdict state.",
            allowableValues = {"pending", "available", "unavailable"},
            example = "available")
        String state,
    @Schema(
            description = "Advisory outcome; null unless state=available.",
            allowableValues = {"pass", "concern", "fail"},
            example = "concern",
            nullable = true)
        String outcome,
    @Schema(
            description = "Redacted reviewer rationale; null unless state=available (or absent).",
            nullable = true)
        String rationale,
    @Schema(
            description = "Model that produced the review (kind + image tag); null when unknown.",
            example = "claude:it-3d2",
            nullable = true)
        String reviewerModelIdentity,
    @Schema(
            description = "Model that produced the reviewed artifact; null when unknown.",
            example = "codex:it-3d2",
            nullable = true)
        String producerModelIdentity,
    @Schema(
            description =
                "True when the reviewer model equals the producer model (same-model self-review) —"
                    + " surfaced as a panel warning, never a refusal (AC4).",
            example = "false")
        boolean selfReview,
    @Schema(
            description =
                "Why no verdict is available (e.g. a failure category, or no_reviewer_configured"
                    + " when the project has no reviewer binding); null unless state=unavailable.",
            example = "runner_crash",
            nullable = true)
        String unavailableReason,
    @Schema(
            description = "When the verdict was recorded; null unless state=available.",
            nullable = true)
        OffsetDateTime createdAt) {

  public static ReviewerVerdictResponse from(ReviewerVerdictView view) {
    return new ReviewerVerdictResponse(
        view.state(),
        view.outcome(),
        view.rationale(),
        view.reviewerModelIdentity(),
        view.producerModelIdentity(),
        view.selfReview(),
        view.unavailableReason(),
        toUtc(view.createdAt()));
  }

  private static OffsetDateTime toUtc(OffsetDateTime value) {
    return value == null ? null : value.withOffsetSameInstant(ZoneOffset.UTC);
  }
}
