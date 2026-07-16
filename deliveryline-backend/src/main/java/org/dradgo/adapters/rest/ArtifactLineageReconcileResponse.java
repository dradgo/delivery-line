package org.dradgo.adapters.rest;

import io.swagger.v3.oas.annotations.media.Schema;
import org.dradgo.application.artifact.LineageReconciliationResult;

/**
 * Story 4.16a (AC9) — response body for {@code POST
 * /api/v1/artifacts/{artifactId}/reconcile-lineage}. Maps {@link LineageReconciliationResult} 1:1.
 * {@code lineageReferenceArtifactId} is the chosen new parent (reattach) or the new fork head
 * (create_explicit_fork), absent for terminate; {@code replayed} is {@code true} for an idempotent
 * replay of a prior succeeded reconcile.
 */
public record ArtifactLineageReconcileResponse(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String targetArtifactId,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String lineageAction,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String recoveryActionId,
    @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED) String reconciledEventId,
    @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED) String lineageReferenceArtifactId,
    @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED) String correlationId,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean replayed) {

  public static ArtifactLineageReconcileResponse from(LineageReconciliationResult result) {
    return new ArtifactLineageReconcileResponse(
        result.targetArtifactId(),
        result.lineageAction(),
        result.recoveryActionId(),
        result.reconciledEventId(),
        result.lineageReferenceArtifactId(),
        result.correlationId(),
        result.replayed());
  }
}
