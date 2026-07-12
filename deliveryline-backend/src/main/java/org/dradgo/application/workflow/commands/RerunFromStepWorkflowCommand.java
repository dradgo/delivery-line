package org.dradgo.application.workflow.commands;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.EnumSet;
import java.util.Set;
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.WorkflowState;

/**
 * Story 4.7 (AC2 / Reconciliation 4/9) — the {@link WorkflowCommandService#rerunFromStepWorkflow}
 * command: transition a run to a caller-chosen safe step boundary ({@code targetState}), routing
 * through the shared idempotency envelope. Mirrors {@link ResumeWorkflowCommand} plus one semantic
 * difference: the {@code targetState} is <strong>operator-chosen</strong> (mapped from the {@code
 * SafeRerunStep} the operator supplied), not deterministically derived per run — so it IS part of
 * the fingerprint identity (a different step is a different action).
 *
 * <p>{@code actorType}/{@code reviewerRole} are audit-only labels — RBAC is deferred; {@code
 * reviewerRole='workflow_owner'} is applied when {@code RecoveryService.rerunFromStep} builds the
 * {@code recovery_actions} row, NOT here.
 *
 * <p>The record shape alone would permit any {@link WorkflowState}; the compact constructor
 * restricts {@code targetState} to the two {@code SafeRerunStep}-backed values ({@code
 * INVESTIGATING} / {@code EXECUTING}) as defense-in-depth, since {@code RecoveryService} already
 * parses the operator input through the {@code SafeRerunStep} registry enum first.
 */
public record RerunFromStepWorkflowCommand(
    @NotBlank @Size(max = 128) String workflowRunId,
    @NotBlank @Size(max = 128) String actorIdentity,
    @NotNull ActorType actorType,
    @NotBlank @Size(max = 256) String idempotencyKey,
    @Size(max = 128) String correlationId,
    @Size(max = 512) String reasonText,
    @NotNull WorkflowState targetState)
    implements WorkflowCommand {

  private static final Set<WorkflowState> ALLOWED_TARGET_STATES =
      EnumSet.of(WorkflowState.INVESTIGATING, WorkflowState.EXECUTING);

  public RerunFromStepWorkflowCommand {
    if (targetState != null && !ALLOWED_TARGET_STATES.contains(targetState)) {
      throw new IllegalArgumentException(
          "rerun-from-step targetState must be one of "
              + ALLOWED_TARGET_STATES
              + ", was: "
              + targetState);
    }
  }
}
