package org.dradgo.adapters.rest;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.dradgo.application.workflow.WorkflowInspectionService.WorkflowEventStreamItem;
import org.dradgo.application.workflow.WorkflowInspectionService.WorkflowEventStreamView;
import org.dradgo.application.workflow.WorkflowInspectionService.WorkflowRunHeaderView;

/**
 * Wire response for {@code GET /api/v1/workflows/{workflowRunId}/events} (story 6.9, AC1/AC2).
 *
 * <p><strong>This shape is pinned to the committed contract</strong> {@code
 * fixture-event-streams/schema/workflow-events-response.schema.json} (story 1.23) — story 2.6's
 * generated TypeScript client binds to it, and {@code WorkflowEventsSchemaConformanceContractTest}
 * validates a live response against the schema file. Field names/types must match the schema
 * exactly. Sourced from {@code WorkflowEventRecord} (carries {@code workflowRunPublicId}), never
 * from the divergent {@code WorkflowEventView}. {@code details.idempotencyKey} (and other
 * server-only keys) are stripped upstream in the application service.
 */
@Schema(
    name = "WorkflowEventsResponse",
    description = "Ordered event history for a workflow run.",
    requiredProperties = {"workflowRun", "events"})
public record WorkflowEventsResponse(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) WorkflowRunRef workflowRun,
    @ArraySchema(
            minItems = 1,
            schema = @Schema(implementation = WorkflowEventResponse.class),
            arraySchema = @Schema(requiredMode = Schema.RequiredMode.REQUIRED))
        List<WorkflowEventResponse> events) {

  public static WorkflowEventsResponse from(WorkflowEventStreamView view) {
    return new WorkflowEventsResponse(
        WorkflowRunRef.from(view.workflowRun()),
        view.events().stream().map(WorkflowEventResponse::from).toList());
  }

  private static OffsetDateTime toUtc(OffsetDateTime value) {
    return value == null ? null : value.withOffsetSameInstant(ZoneOffset.UTC);
  }

  /** Run header: {@code {publicId, ticketRef, createdAt, terminalState}}. */
  @Schema(
      name = "WorkflowRunRef",
      description = "Run header for an event stream.",
      requiredProperties = {"publicId", "ticketRef", "createdAt", "terminalState"})
  public record WorkflowRunRef(
      @Schema(
              example = "run_abc123",
              pattern = "^run_[A-Za-z0-9_]{4,}$",
              requiredMode = Schema.RequiredMode.REQUIRED)
          String publicId,
      @Schema(example = "DEL-1234", minLength = 1, requiredMode = Schema.RequiredMode.REQUIRED)
          String ticketRef,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED) OffsetDateTime createdAt,
      @Schema(
              description = "State at the end of the returned stream (the run's current state).",
              nullable = true,
              allowableValues = {
                "Inbox",
                "Planned",
                "Investigating",
                "WaitingForSpecApproval",
                "Executing",
                "WaitingForReview",
                "Completed",
                "Failed",
                "Paused",
                "TakenOver",
                "Reconciled"
              },
              requiredMode = Schema.RequiredMode.REQUIRED)
          String terminalState) {

    static WorkflowRunRef from(WorkflowRunHeaderView header) {
      return new WorkflowRunRef(
          header.publicId(), header.ticketRef(), toUtc(header.createdAt()), header.terminalState());
    }
  }

  /** One workflow event in wire shape. Mirrors {@code $defs/event} in the committed schema. */
  @Schema(
      name = "WorkflowEvent",
      description = "A single workflow event.",
      requiredProperties = {
        "publicId",
        "workflowRunPublicId",
        "eventType",
        "actorIdentity",
        "actorType",
        "interventionMarker",
        "createdAt",
        "details"
      })
  public record WorkflowEventResponse(
      @Schema(
              example = "evt_abc123",
              pattern = "^evt_[A-Za-z0-9_]{4,}$",
              requiredMode = Schema.RequiredMode.REQUIRED)
          String publicId,
      @Schema(
              example = "run_abc123",
              pattern = "^run_[A-Za-z0-9_]{4,}$",
              requiredMode = Schema.RequiredMode.REQUIRED)
          String workflowRunPublicId,
      @Schema(
              example = "workflow.stateChanged",
              allowableValues = {
                "workflow.stateChanged",
                "approval.requested",
                "approval.approved",
                "approval.rejected",
                "escalation.required",
                "artifact.draftCreated",
                "artifact.available",
                "artifact.failed",
                "artifact.versionCreated",
                "runner.started",
                "runner.failed",
                "runner.dispatched",
                "runner.heartbeatStale",
                "runner.timeout",
                "runner.orphaned",
                "runner.completed",
                "recovery.retried",
                "recovery.dispatchFailed",
                "recovery.reconciled",
                "artifact.lineageRecovered",
                "integration.linked",
                "export.created",
                "clarification.answered",
                "clarification.accepted",
                "clarification.incorporated",
                "clarification.superseded",
                "clarification.rejectedInvalid",
                "clarification.noEffectReason"
              },
              requiredMode = Schema.RequiredMode.REQUIRED)
          String eventType,
      @Schema(
              nullable = true,
              allowableValues = {
                "Inbox",
                "Planned",
                "Investigating",
                "WaitingForSpecApproval",
                "Executing",
                "WaitingForReview",
                "Completed",
                "Failed",
                "Paused",
                "TakenOver",
                "Reconciled"
              })
          String priorState,
      @Schema(
              nullable = true,
              allowableValues = {
                "Inbox",
                "Planned",
                "Investigating",
                "WaitingForSpecApproval",
                "Executing",
                "WaitingForReview",
                "Completed",
                "Failed",
                "Paused",
                "TakenOver",
                "Reconciled"
              })
          String resultingState,
      @Schema(minLength = 1, requiredMode = Schema.RequiredMode.REQUIRED) String actorIdentity,
      @Schema(
              example = "system",
              allowableValues = {"human", "agent", "system", "service_account"},
              requiredMode = Schema.RequiredMode.REQUIRED)
          String actorType,
      @Schema(nullable = true) String reason,
      @Schema(
              nullable = true,
              allowableValues = {
                "runner_timeout",
                "runner_crash",
                "runner_contract_violation",
                "runner_non_zero_exit",
                "runner_late_result",
                "runner_duplicate_result",
                "runner_malformed_output",
                "orphan"
              })
          String failureCategory,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean interventionMarker,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED) OffsetDateTime createdAt,
      @Schema(
              requiredMode = Schema.RequiredMode.REQUIRED,
              description =
                  "Open detail map; server-only keys (e.g. idempotencyKey) are stripped. "
                      + "Known keys include linearTicketReference, artifactId, artifactVersion, "
                      + "contextVersion, correlationId, failedStage, triggeringEventId, "
                      + "recoveryActionId, recoveryRetriedEventId, compensationFailed, "
                      + "reviewerRole, taggedFeedback, specRejectionLoopCount, "
                      + "escalationMarker, artifactVariant, and feedback.")
          Map<String, Object> details) {

    static WorkflowEventResponse from(WorkflowEventStreamItem item) {
      return new WorkflowEventResponse(
          item.publicId(),
          item.workflowRunPublicId(),
          item.eventType(),
          item.priorState(),
          item.resultingState(),
          item.actorIdentity(),
          item.actorType(),
          item.reason(),
          item.failureCategory(),
          item.interventionMarker(),
          toUtc(item.createdAt()),
          item.details());
    }
  }
}
