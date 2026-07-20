package org.dradgo.adapters.rest;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.dradgo.application.audit.AuditQueryService.AuditEventRow;
import org.dradgo.application.audit.AuditQueryService.AuditQueryResult;

/**
 * Story 4.3 (AC6) — wire shape for {@code GET /api/v1/audit/by-ticket/{ticketRef}} and {@code GET
 * /api/v1/audit/by-run/{workflowRunId}}. An OBJECT carrier (not a bare array) because the audit
 * result bundles {@code totalCount} + {@code nextCursor} pagination alongside the {@code events}
 * page. Mirrors the object-carrier + static {@code from} style of {@link
 * OperatorRunSummaryResponse} (enums → wire strings). The application enums ({@code
 * WorkflowEventType}/{@code ActorType}/{@code WorkflowState}/{@code FailureCategory}) render to
 * their wire strings; {@code reason} is already redacted by the service.
 */
@Schema(
    name = "AuditQueryResponse",
    description = "Audit history query result with an events page.")
public record AuditQueryResponse(
    @Schema(
            description = "Total events matching the filter (independent of limit).",
            example = "42")
        long totalCount,
    @Schema(
            description =
                "Opaque keyset cursor for the next page, or null on the last page. Echo it back as"
                    + " the cursor query param to fetch more.",
            nullable = true)
        String nextCursor,
    @Schema(description = "The current page of audit events (timestamp DESC).")
        List<AuditEventResponse> events) {

  public static AuditQueryResponse from(AuditQueryResult result) {
    List<AuditEventResponse> events =
        result.events().stream().map(AuditEventResponse::from).toList();
    return new AuditQueryResponse(result.totalCount(), result.nextCursor(), events);
  }

  /**
   * Story 4.3 (AC1) — one flat audit event row. Nullable reference fields carry {@code
   * nullable=true} so {@code openapi-typescript} emits nullable unions.
   */
  @Schema(name = "AuditEventRow", description = "One flat audit event row.")
  public record AuditEventResponse(
      @Schema(description = "Event public id.", example = "evt_abc123") String eventId,
      @Schema(description = "Event-type wire string.", example = "workflow.stateChanged")
          String eventType,
      @Schema(description = "Owning run public id.", example = "run_abc123") String workflowRunId,
      @Schema(description = "Actor identity.", example = "system") String actorIdentity,
      @Schema(description = "Actor-type wire string.", example = "system") String actorType,
      @Schema(description = "Event timestamp (ISO-8601 UTC).") OffsetDateTime timestamp,
      @Schema(description = "Prior workflow state, null on non-transition events.", nullable = true)
          String priorState,
      @Schema(
              description = "Resulting workflow state, null on non-transition events.",
              nullable = true)
          String resultingState,
      @Schema(description = "Failure category, when applicable.", nullable = true)
          String failureCategory,
      @Schema(description = "Redacted reason text.", nullable = true) String reason,
      @Schema(description = "Correlation id (from event details), when present.", nullable = true)
          String correlationId,
      @Schema(
              description = "Best-effort linked artifact id (from event details), when present.",
              nullable = true)
          String linkedArtifactId) {

    public static AuditEventResponse from(AuditEventRow row) {
      return new AuditEventResponse(
          row.eventId(),
          row.eventType(),
          row.workflowRunId(),
          row.actorIdentity(),
          row.actorType() == null ? null : row.actorType().value(),
          toUtc(row.timestamp()),
          row.priorState() == null ? null : row.priorState().value(),
          row.resultingState() == null ? null : row.resultingState().value(),
          row.failureCategory() == null ? null : row.failureCategory().value(),
          row.reason(),
          row.correlationId(),
          row.linkedArtifactId());
    }

    private static OffsetDateTime toUtc(OffsetDateTime value) {
      return value == null ? null : value.withOffsetSameInstant(ZoneOffset.UTC);
    }
  }
}
