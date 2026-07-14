package org.dradgo.application.artifact;

/**
 * Story 4.16 (AC1 / Reconciliation 12) — the outcome of a {@code
 * ArtifactReconciliationService.repairArtifactDrift} invocation, consumed by the REST {@code
 * ArtifactRepairResponse} and the CLI text/JSON renderers.
 *
 * @param driftId the {@code adr_} public id of the repaired drift
 * @param repairAction the applied {@link RepairAction} wire token
 * @param recoveryActionId the {@code rcv_} public id of the appended {@code recovery_actions} row
 * @param repairedEventId the {@code evt_} public id of the appended {@code artifact.driftRepaired}
 *     event
 * @param resolved whether the drift row was resolved ({@code resolved_at} set) — {@code false} for
 *     a {@code re_verify_checksum} that still mismatched (the drift is left open — Reconciliation
 *     8)
 * @param correlationId the per-repair correlation id (from the actor context)
 * @param replayed {@code true} when this is an idempotent replay of a prior succeeded repair
 */
public record ArtifactRepairResult(
    String driftId,
    String repairAction,
    String recoveryActionId,
    String repairedEventId,
    boolean resolved,
    String correlationId,
    boolean replayed) {}
