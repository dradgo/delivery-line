package org.dradgo.adapters.rest;

import io.swagger.v3.oas.annotations.media.Schema;
import org.dradgo.application.recovery.PauseRecoveryResult;

/**
 * Story 4.13 (AC8) — response body for {@code POST /api/v1/workflows/{workflowRunId}/pause}. Rich
 * recovery-result DTO mapped from the {@code done} story 4.8 {@link PauseRecoveryResult}, carrying
 * the resulting {@code Paused} state, the pausable-source {@code priorState} (what resume goes back
 * to), the {@code recovery_actions} row id ({@code rcv_} prefix), the {@code recovery.paused} event
 * id, the two {@code cancelled_for_pause} runner counts, the stamped correlation id, and the
 * idempotent-replay flag.
 *
 * <p><strong>⚠️ The two state fields have DIFFERENT nullability — pause's unique response trap
 * (Reconciliation 6).</strong>
 *
 * <ul>
 *   <li>{@code currentState} = {@code resultingState}, <strong>always {@link
 *       org.dradgo.domain.registry.WorkflowState#PAUSED}</strong> (never null) → {@code
 *       requiredMode=REQUIRED}, and {@link #from} calls {@code result.resultingState().value()}
 *       directly with NO null-guard — like {@link ReconcileResponse#currentState}, UNLIKE {@link
 *       ResumeResponse#currentState} (nullable).
 *   <li>{@code priorState} is the pausable source state the run was in just before pause. It is
 *       non-null on a fresh pause but <strong>MAY BE NULL on a degenerate replay</strong> (the
 *       persisted {@code recovery.paused} event lost its typed {@code priorState} AND no {@code →
 *       Paused} transition event survives — the record javadoc mandates the null-guard) → {@code
 *       requiredMode=NOT_REQUIRED}, and {@link #from} calls {@code result.priorState() == null ?
 *       null : result.priorState().value()}. This is neither resume (currentState nullable, no
 *       prior field) nor reconcile (currentState required, no prior field) — pause has both a
 *       required-current AND a nullable-prior at once.
 *   <li>{@code cancelledInFlightCount} ({@code pending} + {@code running} flipped) and {@code
 *       cancelledQueuedCount} ({@code queued} flipped) are {@code int} primitives, 0 on replay →
 *       both {@code requiredMode=REQUIRED}.
 *   <li>{@code pausedEventId} carries the {@code recovery.paused} event id on a fresh pause; on
 *       replay the prior row's {@code resulting_event_id} (non-null) — {@code NOT_REQUIRED} for
 *       defensive tolerance.
 *   <li>{@code correlationId} is {@code null} in {@code @WebMvcTest} slices that don't register
 *       {@code CorrelationIdFilter}.
 * </ul>
 *
 * <p>The structural fields ({@code workflowRunId}, {@code currentState}, {@code recoveryActionId},
 * the two counts, {@code replayed}) are marked {@code requiredMode=REQUIRED} so generated TS
 * clients can rely on them.
 */
public record PauseResponse(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String workflowRunId,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String currentState,
    @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED) String priorState,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String recoveryActionId,
    @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED) String pausedEventId,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int cancelledInFlightCount,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int cancelledQueuedCount,
    @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED) String correlationId,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean replayed) {

  /**
   * Maps the story 4.8 {@link PauseRecoveryResult} onto the wire DTO. {@code workflowRunId} is
   * passed explicitly from the {@code @PathVariable} because {@link PauseRecoveryResult} — like
   * {@code ResumeRecoveryResult}/{@code ReconcileRecoveryResult} — carries no {@code workflowRunId}
   * field. {@code currentState} = {@code resultingState().value()} dereferenced directly (always
   * {@code PAUSED}, no null-guard — Reconciliation 6); {@code priorState} IS null-guarded (may be
   * null on a degenerate replay per the record javadoc).
   */
  public static PauseResponse from(String workflowRunId, PauseRecoveryResult result) {
    String priorState = result.priorState() == null ? null : result.priorState().value();
    return new PauseResponse(
        workflowRunId,
        result.resultingState().value(),
        priorState,
        result.recoveryActionPublicId(),
        result.pausedEventPublicId(),
        result.cancelledInFlightCount(),
        result.cancelledQueuedCount(),
        result.correlationId(),
        result.replayed());
  }
}
