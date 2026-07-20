package org.dradgo.application.recovery;

import org.dradgo.domain.registry.WorkflowState;

/**
 * Result returned by {@link RecoveryService#pause(String, String,
 * org.dradgo.application.artifact.ActorContext, String)} (story 4.8 AC1) — feeds the story 4.13
 * REST response.
 *
 * <p>The {@code replayed} flag distinguishes the fresh-pause path (a new {@code recovery_actions}
 * row + new {@code recovery.paused} event + a source → Paused transition + runner cancel-flips +
 * best-effort container stop) from the idempotent-replay path (no new rows / no new transition / no
 * cancellation). On replay, {@code recoveryActionPublicId} carries the prior row's id, {@code
 * pausedEventPublicId} the prior {@code resulting_event_id}, {@code priorState} is re-derived from
 * the persisted {@code recovery.paused} event's typed {@code priorState()}, and BOTH cancellation
 * counts are {@code 0} — the replayed call cancelled nothing (the original call's counts are not
 * recomputed, mirroring takeover's null-counts-on-replay contract).
 *
 * @param recoveryActionPublicId rcv_… id of the recovery_actions row (always present)
 * @param pausedEventPublicId evt_… id of the recovery.paused event on a fresh pause; on replay it
 *     carries the prior row's {@code resulting_event_id} (non-null)
 * @param priorState the pausable source state the run was in just before the pause — the state
 *     resume (4.5) will transition back to (it reads the typed {@code priorState} off the {@code
 *     WORKFLOW_STATE_CHANGED → Paused} event, which records the same value). Non-null on a fresh
 *     pause; MAY BE NULL on a degenerate replay where the persisted {@code recovery.paused} event
 *     lost its typed {@code priorState} AND no {@code → Paused} transition event survives to fall
 *     back on — REST/CLI mappers (4.13) must null-guard
 * @param cancelledInFlightCount {@code pending} + {@code running} rows flipped to {@code
 *     cancelled_for_pause} by THIS call (0 on replay)
 * @param cancelledQueuedCount {@code queued} rows flipped to {@code cancelled_for_pause} by THIS
 *     call (0 on replay)
 * @param resultingState always {@link WorkflowState#PAUSED}
 * @param correlationId sanitized correlationId carried through the operation
 * @param replayed true when this call returned an existing row without re-appending events,
 *     re-transitioning, or cancelling any runner work
 */
public record PauseRecoveryResult(
    String recoveryActionPublicId,
    String pausedEventPublicId,
    WorkflowState priorState,
    int cancelledInFlightCount,
    int cancelledQueuedCount,
    WorkflowState resultingState,
    String correlationId,
    boolean replayed) {}
