package org.dradgo.adapters.rest;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.dradgo.application.workflow.WorkflowInspectionService.LatestArtifactView;
import org.dradgo.application.workflow.WorkflowInspectionService.LinkedTicketView;
import org.dradgo.application.workflow.WorkflowInspectionService.WorkflowStatusView;

/**
 * Single-run detail for {@code GET /api/v1/workflows/{workflowRunId}} (story 6.9, AC1/AC2). Direct
 * resource shape (no envelope), camelCase, ISO-8601 UTC timestamps, public-prefixed ids. Faithful
 * projection of {@link WorkflowStatusView} so the UI can answer "what happened / what's current /
 * who owns it / next safe action" from backend-reported state (architecture quality gate).
 */
@Schema(name = "WorkflowDetail", description = "Current status detail of a workflow run.")
public record WorkflowDetailResponse(
    @Schema(example = "run_abc123") String workflowRunId,
    @Schema(example = "WaitingForSpecApproval") String currentState,
    String currentActorIdentity,
    String currentActorType,
    String lastEventType,
    OffsetDateTime lastEventAt,
    List<LatestArtifact> latestArtifacts,
    LinkedTicket linkedTicket,
    String failedStage,
    String lastSuccessfulStage,
    OffsetDateTime failureTimestamp,
    String failureCategory,
    OffsetDateTime lastActivityTimestamp,
    @Schema(description = "Next safe operator action for the current state.", example = "view_only")
        String nextSafeAction,
    @Schema(
            description =
                "How many spec rejections this run has accumulated. Increments on every successful"
                    + " rejectSpec call (story 2.10).",
            example = "0")
        int specRejectionLoopCount,
    @Schema(
            description =
                "True once specRejectionLoopCount has crossed the configured escalation threshold."
                    + " Informational — does NOT terminate the workflow (FR13).",
            example = "false")
        boolean escalationMarker) {

  public static WorkflowDetailResponse from(WorkflowStatusView view) {
    return new WorkflowDetailResponse(
        view.workflowRunId(),
        view.currentState() == null ? null : view.currentState().value(),
        view.currentActorIdentity(),
        view.currentActorType(),
        view.lastEventType(),
        toUtc(view.lastEventAt()),
        view.latestArtifacts() == null
            ? List.of()
            : view.latestArtifacts().stream().map(LatestArtifact::from).toList(),
        view.linkedTicket() == null ? null : LinkedTicket.from(view.linkedTicket()),
        view.failedStage(),
        view.lastSuccessfulStage(),
        toUtc(view.failureTimestamp()),
        view.failureCategory(),
        toUtc(view.lastActivityTimestamp()),
        view.nextSafeAction(),
        view.specRejectionLoopCount(),
        view.escalationMarker());
  }

  private static OffsetDateTime toUtc(OffsetDateTime value) {
    return value == null ? null : value.withOffsetSameInstant(ZoneOffset.UTC);
  }

  /** Latest artifact of a given type for the run. */
  @Schema(name = "LatestArtifact")
  public record LatestArtifact(
      @Schema(example = "spec") String artifactType,
      int version,
      String status,
      @Schema(
              description =
                  "Public id of this latest artifact. Resolves the artifact-read endpoint "
                      + "(GET .../artifacts/{artifactId}) and the spec approval/decision bar "
                      + "(story 2.19 resolveSpecArtifactId).",
              example = "art_abc123")
          String artifactId) {

    static LatestArtifact from(LatestArtifactView view) {
      return new LatestArtifact(
          view.artifactType(), view.version(), view.status(), view.artifactId());
    }
  }

  /** Linked external ticket for the run. */
  @Schema(name = "LinkedTicket")
  public record LinkedTicket(
      @Schema(example = "linear") String integrationType,
      @Schema(example = "DEL-1234") String externalRef,
      String syncStatus) {

    static LinkedTicket from(LinkedTicketView view) {
      return new LinkedTicket(view.integrationType(), view.externalRef(), view.syncStatus());
    }
  }
}
