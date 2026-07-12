package org.dradgo.application.recovery;

/**
 * Story 4.9 — result of {@code RecoveryService.classifyFailure}. Mirrors {@link
 * ReconcileRecoveryResult}'s shape; there is NO resulting workflow state because classify is a pure
 * metadata operation (no transition — epic AC10).
 *
 * @param recoveryActionPublicId the {@code rcv_} id of the {@code action_type='classify_failure'}
 *     row
 * @param classifiedEventPublicId the {@code evt_} id of the {@code recovery.failureClassified}
 *     audit event
 * @param taxonomyValue the applied taxonomy wire value
 * @param priorTaxonomyValue the wire value this classification overwrote; {@code null} on first
 *     classify (AC9 — story 4.14 AC8 renders "classified as X (previously Y)")
 * @param correlationId the CURRENT call's correlation id (replay echoes the caller's, not the
 *     winning attempt's — the retry precedent)
 * @param replayed {@code true} when this result was replayed from a prior succeeded attempt under
 *     the same idempotency key
 */
public record ClassifyFailureResult(
    String recoveryActionPublicId,
    String classifiedEventPublicId,
    String taxonomyValue,
    String priorTaxonomyValue,
    String correlationId,
    boolean replayed) {}
