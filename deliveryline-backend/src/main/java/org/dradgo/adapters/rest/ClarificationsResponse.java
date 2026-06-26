package org.dradgo.adapters.rest;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.dradgo.application.workflow.WorkflowInspectionService.ClarificationView;

/**
 * Wire response for {@code GET /api/v1/workflows/{workflowRunId}/clarifications} — the
 * clarification-READ surface that was reserved by story 2.18 but never exposed (the backend
 * inspection method {@link WorkflowInspectionService#getClarifications(String)} existed with no
 * controller wiring, and the frontend {@code useClarifications} hook stayed a disabled stub). This
 * endpoint exposes the existing read model so the Clarification Region can surface the open
 * questions a spec runner now raises (the emission half was activated separately).
 *
 * <p><strong>Field shape is pinned to the frontend-owned {@code ClarificationView}
 * interface</strong> ({@code deliveryline-frontend/src/features/workflows/clarificationView.ts}) so
 * flipping the hook to live needs ZERO region/container changes — the container already normalizes
 * this exact shape. The backend {@link ClarificationView} record is a subset (open/answered
 * anatomy); the frontend's extra lifecycle fields (acceptedAt, incorporatedAt,
 * supersededByArtifactId, noEffectReason) are all optional there, so omitting them here is
 * contract-compatible.
 *
 * <p>Read-only and idempotent — no Idempotency-Key. An unknown/empty run yields an empty list
 * (mirroring the service's list-by-run contract), so the region renders the calm "no open
 * questions" state rather than erroring.
 */
@Schema(
    name = "ClarificationsResponse",
    description = "Clarifications raised against a workflow run's specification.",
    requiredProperties = {"clarifications"})
public record ClarificationsResponse(
    @ArraySchema(
            schema = @Schema(implementation = ClarificationItemResponse.class),
            arraySchema = @Schema(requiredMode = Schema.RequiredMode.REQUIRED))
        List<ClarificationItemResponse> clarifications) {

  public static ClarificationsResponse from(List<ClarificationView> views) {
    return new ClarificationsResponse(views.stream().map(ClarificationItemResponse::from).toList());
  }

  private static OffsetDateTime toUtc(OffsetDateTime value) {
    return value == null ? null : value.withOffsetSameInstant(ZoneOffset.UTC);
  }

  /** One clarification in wire shape — mirrors the frontend {@code ClarificationView}. */
  @Schema(
      name = "Clarification",
      description = "A single clarification raised against a spec artifact version.",
      requiredProperties = {
        "clarificationId",
        "workflowRunId",
        "artifactId",
        "artifactVersion",
        "questionId",
        "questionText",
        "status",
        "createdAt"
      })
  public record ClarificationItemResponse(
      @Schema(
              example = "clr_abc123",
              pattern = "^clr_[A-Za-z0-9_]{4,}$",
              requiredMode = Schema.RequiredMode.REQUIRED)
          String clarificationId,
      @Schema(
              example = "run_abc123",
              pattern = "^run_[A-Za-z0-9_]{4,}$",
              requiredMode = Schema.RequiredMode.REQUIRED)
          String workflowRunId,
      @Schema(
              example = "art_abc123",
              pattern = "^art_[A-Za-z0-9_]{4,}$",
              requiredMode = Schema.RequiredMode.REQUIRED)
          String artifactId,
      @Schema(minimum = "1", requiredMode = Schema.RequiredMode.REQUIRED) int artifactVersion,
      @Schema(example = "Q-001", minLength = 1, requiredMode = Schema.RequiredMode.REQUIRED)
          String questionId,
      @Schema(
              description = "UNTRUSTED — rendered only via the frontend SafeMarkdownRenderer.",
              requiredMode = Schema.RequiredMode.REQUIRED)
          String questionText,
      @Schema(
              allowableValues = {
                "open",
                "answered",
                "accepted",
                "incorporated",
                "superseded",
                "rejected_invalid"
              },
              requiredMode = Schema.RequiredMode.REQUIRED)
          String status,
      @Schema(nullable = true, description = "UNTRUSTED reviewer wording — sanitized render only.")
          String answerText,
      @Schema(nullable = true) String answeredByActor,
      @Schema(
              nullable = true,
              allowableValues = {"human", "agent", "system", "service_account"})
          String answeredByActorType,
      @Schema(nullable = true) OffsetDateTime answeredAt,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED) OffsetDateTime createdAt) {

    static ClarificationItemResponse from(ClarificationView view) {
      return new ClarificationItemResponse(
          view.clarificationId(),
          view.workflowRunId(),
          view.artifactId(),
          view.artifactVersion(),
          view.questionId(),
          view.questionText(),
          view.status(),
          view.answerText(),
          view.answeredByActor(),
          view.answeredByActorType(),
          toUtc(view.answeredAt()),
          toUtc(view.createdAt()));
    }
  }
}
