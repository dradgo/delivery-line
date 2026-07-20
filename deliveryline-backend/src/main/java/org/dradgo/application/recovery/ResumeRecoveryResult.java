package org.dradgo.application.recovery;

import org.dradgo.domain.registry.WorkflowState;

/**
 * Result returned by {@link RecoveryService#resume(String, String,
 * org.dradgo.application.artifact.ActorContext, String)} (story 4.5 AC1).
 *
 * <p>The {@code replayed} flag distinguishes the fresh-resume path (a new {@code recovery_actions}
 * row + new {@code recovery.resumed} event + a Paused → prior-executing-state transition + runner
 * re-dispatch) from the idempotent-replay path (no new rows / no new transition / no re-dispatch).
 * On replay, {@code recoveryActionPublicId} carries the prior row's id and {@code
 * resumedEventPublicId} the prior {@code resulting_event_id}; {@code newRunnerExecutionPublicId} is
 * left null (no new dispatch occurs). {@code newRunnerExecutionPublicId} is also null on a fresh
 * resume when auto-dispatch is off (the shared test profile) — {@code redispatchAfterRetry} returns
 * {@code null} and the bare transition stays observable without a runner.
 *
 * @param recoveryActionPublicId rcv_… id of the recovery_actions row (always present)
 * @param resumedEventPublicId evt_… id of the recovery.resumed event on a fresh resume; on replay
 *     it carries the prior row's {@code resulting_event_id} (non-null)
 * @param newRunnerExecutionPublicId rex_… id of the re-dispatched runner_executions row (null on
 *     replay AND null when auto-dispatch is off)
 * @param resultingState the recovered prior executing state the run resumed into (Executing today)
 * @param correlationId sanitized correlationId carried through the operation
 * @param replayed true when this call returned an existing row without re-appending events,
 *     re-transitioning, or re-dispatching the runner
 */
public record ResumeRecoveryResult(
    String recoveryActionPublicId,
    String resumedEventPublicId,
    String newRunnerExecutionPublicId,
    WorkflowState resultingState,
    String correlationId,
    boolean replayed) {}
