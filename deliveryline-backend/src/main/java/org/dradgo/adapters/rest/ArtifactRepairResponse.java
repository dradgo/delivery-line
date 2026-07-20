package org.dradgo.adapters.rest;

import io.swagger.v3.oas.annotations.media.Schema;
import org.dradgo.application.artifact.ArtifactRepairResult;

/**
 * Story 4.16 (AC5) — response body for {@code POST /api/v1/artifact-drift/{driftId}/repair}. Maps
 * {@link ArtifactRepairResult} 1:1. {@code resolved} is {@code false} for a {@code
 * re_verify_checksum} that still mismatched (the drift is left open); {@code replayed} is {@code
 * true} for an idempotent replay of a prior succeeded repair.
 */
public record ArtifactRepairResponse(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String driftId,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String repairAction,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String recoveryActionId,
    @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED) String repairedEventId,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean resolved,
    @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED) String correlationId,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean replayed) {

  public static ArtifactRepairResponse from(ArtifactRepairResult result) {
    return new ArtifactRepairResponse(
        result.driftId(),
        result.repairAction(),
        result.recoveryActionId(),
        result.repairedEventId(),
        result.resolved(),
        result.correlationId(),
        result.replayed());
  }
}
