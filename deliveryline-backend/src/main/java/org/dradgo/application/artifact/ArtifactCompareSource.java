package org.dradgo.application.artifact;

/**
 * Story 4.19 (AC1, Reconciliation 1) — the typed carrier returned by {@link
 * ArtifactService#loadCompareSource(String)} for the Compare Mode read path. Bundles the already
 * availability-gated artifact snapshot, its payload bytes, and the producing actor so {@code
 * RevisionDeltaService} can compute a delta while depending only on {@code ArtifactService} +
 * {@code RedactionPolicyService} (never {@code ArtifactRecordPort} / {@code ArtifactPayloadStore}
 * directly).
 *
 * @param snapshot the loaded, {@code AVAILABLE}, non-{@code LOCAL_ONLY} artifact snapshot. Never
 *     {@code null}.
 * @param payloadBytes the raw (redacted-at-capture) payload bytes read via the payload store; never
 *     {@code null} and never empty (the load gates reject an unreadable/empty payload).
 * @param producedByActor the creating actor identity ({@code workflow_events.actor_identity} via
 *     the artifact's {@code linked_event_id}); {@code null} when no linked event/actor exists
 *     (OQ-3).
 */
public record ArtifactCompareSource(
    ArtifactRecordSnapshot snapshot, byte[] payloadBytes, String producedByActor) {}
