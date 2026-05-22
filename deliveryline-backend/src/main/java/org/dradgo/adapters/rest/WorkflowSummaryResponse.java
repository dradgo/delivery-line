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
        String lastEventType) {

  public static WorkflowSummaryResponse from(WorkflowRunSummaryView view) {
    return new WorkflowSummaryResponse(
        view.workflowRunId(),
        view.currentState(),
        view.ticketRef(),
        toUtc(view.lastEventAt()),
        view.lastEventType());
  }

  private static OffsetDateTime toUtc(OffsetDateTime value) {
    return value == null ? null : value.withOffsetSameInstant(ZoneOffset.UTC);
  }
}
