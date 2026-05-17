package org.dradgo.application.recovery;

/**
 * Result returned by {@link RecoveryService#retry(String, String,
 * org.dradgo.application.artifact.ActorContext)} (story 1.18 AC2).
 *
 * <p>The {@code Replayed} flag distinguishes the fresh-retry path (a new {@code recovery_actions}
 * row + new {@code recovery.retried} event + new runner dispatch from {@code RunnerBroker}) from
 * the idempotent-replay path (no new rows / no new dispatch). On replay, {@code
 * recoveryActionPublicId} carries the prior row's id; the event / runner ids are left null because
 * the original ids are not necessarily preserved through the {@link
 * org.dradgo.application.recovery.spi.RecoveryActionRecordPort} surface.
 *
 * @param recoveryActionPublicId rcv_… id of the recovery_actions row (always present)
 * @param recoveryRetriedEventPublicId evt_… id of the recovery.retried event (null on replay)
 * @param newRunnerExecutionPublicId rex_… id of the new runner_executions row (null on replay)
 * @param correlationId sanitized correlationId carried through the operation
 * @param replayed true when this call returned an existing row without re-appending events or
 *     re-dispatching the runner
 */
public record RetryRecoveryResult(
    String recoveryActionPublicId,
    String recoveryRetriedEventPublicId,
    String newRunnerExecutionPublicId,
    String correlationId,
    boolean replayed) {}
