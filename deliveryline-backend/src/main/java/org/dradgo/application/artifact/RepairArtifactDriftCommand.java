package org.dradgo.application.artifact;

/**
 * Story 4.16 (AC1/AC5 / Reconciliation 12) — the single coordinator input for {@code
 * ArtifactReconciliationService.repairArtifactDrift}. The REST {@code ArtifactDriftController} and
 * the {@code deliveryline operator artifact-repair} CLI both build this and call the coordinator,
 * so validation + idempotency are single-sourced.
 *
 * @param driftId the {@code adr_} public id of the drift to repair
 * @param repairAction the raw {@link RepairAction} wire token; validated by the service (so the
 *     typed {@code INVALID_REPAIR_ACTION_FOR_DRIFT_CATEGORY} is reachable) — NOT pre-parsed at the
 *     adapter
 * @param reasonText optional operator note recorded on the {@code artifact.driftRepaired} event
 * @param completionEvidence action-specific — REQUIRED for {@code mark_operation_complete} (else
 *     {@code MISSING_REPAIR_REQUIRED_FIELD}); null otherwise
 * @param backupSource action-specific — carried for the {@code restore_from_backup} E4 stub; null
 *     otherwise
 * @param actor the resolved operator actor context (identity + type + correlationId)
 * @param idempotencyKey the caller-supplied idempotency key (validated by the service)
 */
public record RepairArtifactDriftCommand(
    String driftId,
    String repairAction,
    String reasonText,
    String completionEvidence,
    String backupSource,
    ActorContext actor,
    String idempotencyKey) {}
