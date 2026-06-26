package org.dradgo.adapters.rest;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.dradgo.application.workflow.WorkflowInspectionService.WorkflowRunSummaryView;

/**
 * Lean queue/list item for {@code GET /api/v1/workflows} (story 6.9, AC1/AC2). Direct resource
 * shape — the list endpoint returns a JSON array of these, with no {@code {data: …}} envelope.
 */
@Schema(name = "WorkflowSummary", description = "Queue/list summary of a workflow run.")
public record WorkflowSummaryResponse(
    @Schema(description = "Run public id.", example = "run_abc123") String workflowRunId,
    @Schema(description = "Current workflow state.", example = "WaitingForSpecApproval")
        String currentState,
    @Schema(description = "Linear ticket reference, if linked.", example = "DEL-1234")
        String ticketRef,
    @Schema(description = "Timestamp of the most recent event (ISO-8601 UTC).")
        OffsetDateTime lastEventAt,
    @Schema(description = "Type of the most recent event.", example = "workflow.stateChanged")
        String lastEventType,
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
        boolean escalationMarker,
    @Schema(
            description =
                "Soft-hide marker (story 3d-8). Non-null when the run has been archived (hidden"
                    + " from the default queue); null for a live run. The queue renders an"
                    + " archived/hidden badge when present.",
            nullable = true)
        OffsetDateTime archivedAt,
    @Schema(
            description = "Parent workflow run public id, when this run is a split child.",
            nullable = true)
        String parentRunId,
    @Schema(
            description = "Project public id for the run.",
            nullable = true,
            example = "prj_default")
        String projectId,
    @Schema(
            description = "Project display name for the run.",
            nullable = true,
            example = "Default project")
        String projectName,
    @Schema(description = "Project slug for the run.", nullable = true, example = "default")
        String projectSlug) {

  // NOTE: Story 2.12 added `pendingClarifications` to the application-layer
  // {@link WorkflowRunSummaryView} but the REST surface does NOT yet expose it — the OpenAPI
  // snapshot regen is deferred to story 2.13 per the story's Git Intelligence note. Story 2.14
  // reads `WorkflowInspectionService.getRunSummary()` directly for the gate logic without
  // depending on the REST shape; when story 2.13 regenerates the snapshot, this DTO + the
  // `WorkflowSummaryResponse.from` mapper will be widened with the new field at the END of the
  // parameter list.
  public static WorkflowSummaryResponse from(WorkflowRunSummaryView view) {
    return new WorkflowSummaryResponse(
        view.workflowRunId(),
        view.currentState(),
        view.ticketRef(),
        toUtc(view.lastEventAt()),
        view.lastEventType(),
        view.specRejectionLoopCount(),
        view.escalationMarker(),
        toUtc(view.archivedAt()),
        view.parentRunId(),
        view.projectId(),
        view.projectName(),
        view.projectSlug());
  }

  private static OffsetDateTime toUtc(OffsetDateTime value) {
    return value == null ? null : value.withOffsetSameInstant(ZoneOffset.UTC);
  }
}
