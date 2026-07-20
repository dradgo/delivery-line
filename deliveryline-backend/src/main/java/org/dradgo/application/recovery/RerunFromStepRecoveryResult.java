package org.dradgo.application.recovery;

import java.util.List;
import org.dradgo.domain.registry.WorkflowState;

/**
 * Result returned by {@link RecoveryService#rerunFromStep(String, String, String,
 * org.dradgo.application.artifact.ActorContext, String)} (story 4.7 AC1/AC8).
 *
 * <p>The {@code replayed} flag distinguishes the fresh-rerun path (a new {@code recovery_actions}
 * row + new {@code recovery.rerunFromStep} event + a → targetStep transition + prior-approval
 * invalidation + runner re-enqueue) from the idempotent-replay path (no new rows / no new
 * transition / no re-enqueue). On replay, {@code recoveryActionPublicId} carries the prior row's
 * id, {@code rerunEventPublicId} the prior {@code resulting_event_id}, the two id lists are re-read
 * from the stored event details, and {@code newRunnerExecutionPublicId} is left null (no new
 * dispatch). {@code newRunnerExecutionPublicId} is also null on a fresh rerun when auto-dispatch is
 * off (the shared test profile) — the bare transition stays observable without a runner.
 *
 * @param recoveryActionPublicId rcv_… id of the recovery_actions row (always present)
 * @param rerunEventPublicId evt_… id of the recovery.rerunFromStep event on a fresh rerun; on
 *     replay it carries the prior row's {@code resulting_event_id} (non-null)
 * @param supersededArtifactIds the active-leaf artifact ids at/beyond the target step, recorded for
 *     audit (never null — empty when the run has no artifacts at/beyond the step)
 * @param invalidatedApprovalIds the approval id(s) invalidated by this rerun (never null — empty
 *     when the run had no current approval at the invalidated stage)
 * @param newRunnerExecutionPublicId rex_… id of the re-enqueued runner_executions row (null on
 *     replay AND null when auto-dispatch is off)
 * @param resultingState the safe step boundary the run was rerun into (Investigating or Executing)
 * @param correlationId sanitized correlationId carried through the operation
 * @param replayed true when this call returned an existing row without re-appending events,
 *     re-transitioning, invalidating an approval, or re-enqueuing the runner
 */
public record RerunFromStepRecoveryResult(
    String recoveryActionPublicId,
    String rerunEventPublicId,
    List<String> supersededArtifactIds,
    List<String> invalidatedApprovalIds,
    String newRunnerExecutionPublicId,
    WorkflowState resultingState,
    String correlationId,
    boolean replayed) {}
