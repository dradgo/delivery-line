package org.dradgo.application.integration.conflict.spi;

import java.time.Instant;

/**
 * Story 4.17 — insert request for one detected {@code integration_conflicts} row. {@code
 * conflictCategory} is an {@code IntegrationConflictCategory} wire value; the two snapshot fields
 * are already-serialized JSON strings the adapter casts to {@code jsonb} ({@code
 * internalStateSnapshot} captures the run's {@code currentState}; {@code externalStateSnapshot}
 * captures the cached-vs-fresh external facts + the classifying {@code
 * IntegrationFailureCategory.value()} per AC5). {@code resolvedAt}/{@code resolvedByActionId} are
 * UNWRITTEN by this producer story (populated later by 4.6).
 */
public record NewIntegrationConflict(
    String publicId,
    String integrationLinkPublicId,
    String workflowRunPublicId,
    String conflictCategory,
    String internalStateSnapshot,
    String externalStateSnapshot,
    Instant detectedAt) {}
