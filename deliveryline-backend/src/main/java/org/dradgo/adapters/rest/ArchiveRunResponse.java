package org.dradgo.adapters.rest;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.dradgo.application.workflow.WorkflowArchiveResult;

/**
 * Response body for the archive / un-archive endpoints (story 3d-8). Carries the (unchanged)
 * workflow state plus the {@code archivedAt} marker so the client reflects the new hidden/live
 * state immediately: non-null after a hide, null after an un-hide.
 */
@Schema(name = "ArchiveRun", description = "Result of an archive / un-archive action.")
public record ArchiveRunResponse(
    @Schema(description = "Run public id.", example = "run_abc123") String workflowRunId,
    @Schema(description = "Current workflow state (unchanged by archiving).", example = "Failed")
        String currentState,
    @Schema(
            description = "Soft-hide marker: non-null after archive, null after un-archive.",
            nullable = true)
        OffsetDateTime archivedAt) {

  public static ArchiveRunResponse from(WorkflowArchiveResult result) {
    return new ArchiveRunResponse(
        result.workflowRunId(),
        result.currentState() == null ? null : result.currentState().value(),
        toUtc(result.archivedAt()));
  }

  private static OffsetDateTime toUtc(OffsetDateTime value) {
    return value == null ? null : value.withOffsetSameInstant(ZoneOffset.UTC);
  }
}
