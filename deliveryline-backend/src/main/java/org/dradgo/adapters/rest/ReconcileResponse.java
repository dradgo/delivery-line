package org.dradgo.adapters.rest;

import io.swagger.v3.oas.annotations.media.Schema;
import org.dradgo.application.recovery.ReconcileRecoveryResult;

/**
 * Story 4.11 (AC8) — response body for {@code POST /api/v1/workflows/{workflowRunId}/reconcile}.
 * Rich recovery-result DTO mapped from the {@code done} story 4.6 {@link ReconcileRecoveryResult},
 * carrying the post-reconcile run state, the {@code recovery_actions} row id ({@code rcv_} prefix),
 * the {@code recovery.reconciled} event id, the resolved {@code integration_conflicts} id ({@code
 * icf_} prefix), the stamped correlation id, and the idempotent-replay flag.
 *
 * <p><strong>{@code currentState} is NON-NULL → {@code requiredMode=REQUIRED} — a deliberate
 * DIVERGENCE from {@link ResumeResponse#currentState} (which is nullable).</strong> Both reconcile
 * code paths resolve {@code resultingState} to a concrete {@link
 * org.dradgo.domain.registry.WorkflowState}: the fresh path sets it to {@code priorState} (never
 * null) when other unresolved conflicts remain, else {@code RECONCILED}; the replay path defaults a
 * null event state to {@code RECONCILED}. There is no {@code orElse(null)} deferral here (resume
 * had one — story 4.5's finding), so {@link #from} calls {@code result.resultingState().value()}
 * directly with NO null-guard — a {@code NOT_REQUIRED} here would under-specify a field that is
 * always present.
 *
 * <p>Nullability of the remaining fields:
 *
 * <ul>
 *   <li>{@code reconciledEventId} carries the {@code recovery.reconciled} event id on a fresh
 *       reconcile; on replay it carries the prior row's resulting-event id. Marked NOT_REQUIRED for
 *       defensive tolerance.
 *   <li>{@code resolvedConflictId} is the {@code icf_...} id that was closed — REQUIRED (the
 *       service always reconciles a concrete conflict).
 *   <li>{@code correlationId} is {@code null} in {@code @WebMvcTest} slices that don't register
 *       {@code CorrelationIdFilter}.
 * </ul>
 *
 * <p><strong>OQ-2 deferral:</strong> epic AC8 also lists {@code integration_conflicts.resolved_at}
 * and {@code integration_links.external_metadata}. Neither is carried by {@link
 * ReconcileRecoveryResult}, so both are DEFERRED (surfacing them would widen the 4.6 application
 * result — out of scope for this pure-adapter story). {@code recoveryActionId} doubles as {@code
 * integration_conflicts.resolved_by_action_id} (the service passes the recovery-action id into
 * {@code resolveConflict}).
 *
 * <p>The structural fields ({@code workflowRunId}, {@code currentState}, {@code recoveryActionId},
 * {@code resolvedConflictId}, {@code replayed}) are marked {@code requiredMode=REQUIRED} so
 * generated TS clients can rely on them.
 */
public record ReconcileResponse(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String workflowRunId,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String currentState,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String recoveryActionId,
    @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED) String reconciledEventId,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String resolvedConflictId,
    @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED) String correlationId,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean replayed) {

  /**
   * Maps the story 4.6 {@link ReconcileRecoveryResult} onto the wire DTO. {@code workflowRunId} is
   * passed explicitly from the {@code @PathVariable} because {@link ReconcileRecoveryResult} — like
   * {@code ResumeRecoveryResult} — carries no {@code workflowRunId} field. Unlike {@code
   * ResumeResponse.from}, {@code resultingState()} is dereferenced directly with NO null-guard —
   * both reconcile paths resolve a concrete state (Reconciliation 7).
   */
  public static ReconcileResponse from(String workflowRunId, ReconcileRecoveryResult result) {
    return new ReconcileResponse(
        workflowRunId,
        result.resultingState().value(),
        result.recoveryActionPublicId(),
        result.reconciledEventPublicId(),
        result.resolvedConflictId(),
        result.correlationId(),
        result.replayed());
  }
}
