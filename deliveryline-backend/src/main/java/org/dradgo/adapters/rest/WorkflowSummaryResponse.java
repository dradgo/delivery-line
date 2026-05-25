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
        boolean escalationMarker) {

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
        view.escalationMarker());
  }

  private static OffsetDateTime toUtc(OffsetDateTime value) {
    return value == null ? null : value.withOffsetSameInstant(ZoneOffset.UTC);
  }
}
