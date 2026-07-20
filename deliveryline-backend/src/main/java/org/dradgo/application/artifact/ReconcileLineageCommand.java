package org.dradgo.application.artifact;

/**
 * Story 4.16a (AC1/AC2 / Reconciliation 1/10) — the single coordinator input for {@code
 * ArtifactReconciliationService.reconcileLineage}. The REST {@code ArtifactLineageController} and
 * the {@code deliveryline operator reconcile-lineage} CLI both build this and call the coordinator,
 * so validation + idempotency are single-sourced.
 *
 * <p><strong>Model A</strong> — the operator acts DIRECTLY on an ambiguous/orphan artifact public
 * id ({@code art_…}); lineage conflicts are transient (never persisted), so there is NO {@code
 * conflictId}/{@code driftId} handle to look up (Reconciliation 1).
 *
 * @param targetArtifactId the {@code art_} public id of the ambiguous/orphan artifact to reconcile
 * @param lineageAction the raw {@link LineageAction} wire token; validated by the service (so the
 *     typed {@code INVALID_LINEAGE_RECOVERY_ACTION} is reachable) — NOT pre-parsed at the adapter
 * @param chosenParentArtifactId action-specific — REQUIRED for {@code reattach_to_existing_lineage}
 *     (the {@code art_} id of the lineage leaf to re-parent onto; else {@code
 *     MISSING_LINEAGE_RECOVERY_FIELD}); null otherwise
 * @param reasonText optional operator note recorded on the {@code artifact.lineageReconciled} event
 * @param actor the resolved operator actor context (identity + type + correlationId)
 * @param idempotencyKey the caller-supplied idempotency key (validated by the service)
 */
public record ReconcileLineageCommand(
    String targetArtifactId,
    String lineageAction,
    String chosenParentArtifactId,
    String reasonText,
    ActorContext actor,
    String idempotencyKey) {}
