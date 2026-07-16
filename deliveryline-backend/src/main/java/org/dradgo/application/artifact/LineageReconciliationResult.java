package org.dradgo.application.artifact;

/**
 * Story 4.16a (AC1/AC6 / Reconciliation 10) — the outcome of a {@code
 * ArtifactReconciliationService.reconcileLineage} invocation, consumed by the REST {@code
 * ArtifactLineageReconcileResponse} and the CLI text/JSON renderers.
 *
 * @param targetArtifactId the {@code art_} public id of the reconciled artifact
 * @param lineageAction the applied {@link LineageAction} wire token
 * @param recoveryActionId the {@code rcv_} public id of the appended {@code recovery_actions} row
 * @param reconciledEventId the {@code evt_} public id of the appended {@code
 *     artifact.lineageReconciled} event
 * @param lineageReferenceArtifactId the SECONDARY artifact the action involved — the chosen new
 *     parent for {@code reattach_to_existing_lineage}, the newly created fork head for {@code
 *     create_explicit_fork}; {@code null} for {@code terminate_ambiguous_lineage}
 * @param correlationId the per-reconcile correlation id (from the actor context)
 * @param replayed {@code true} when this is an idempotent replay of a prior succeeded reconcile
 */
public record LineageReconciliationResult(
    String targetArtifactId,
    String lineageAction,
    String recoveryActionId,
    String reconciledEventId,
    String lineageReferenceArtifactId,
    String correlationId,
    boolean replayed) {}
