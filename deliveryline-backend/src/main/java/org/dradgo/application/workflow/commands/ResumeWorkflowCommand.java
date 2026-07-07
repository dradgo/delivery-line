package org.dradgo.application.workflow.commands;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.WorkflowState;

/**
 * Story 4.5 (AC2 / Reconciliation 8) — the {@link WorkflowCommandService#resumeWorkflow} command:
 * transition a paused run back to its prior executing state ({@code targetState}), routing through
 * the shared idempotency envelope. Mirrors {@link TakeoverWorkflowCommand} plus one extra {@code
 * targetState} field carrying the resolved prior executing state (read from the {@code
 * WORKFLOW_STATE_CHANGED → Paused} event's typed {@code priorState()} by {@code
 * RecoveryService.resume}).
 *
 * <p>{@code actorType}/{@code reviewerRole} are audit-only labels — RBAC is deferred; {@code
 * reviewerRole='workflow_owner'} is applied when {@code RecoveryService.resume} builds the {@code
 * recovery_actions} row, NOT here.
 *
 * <p>Canonical fingerprint fields after the shared envelope are: {@code workflowRunId}, {@code
 * reasonText} (mirroring {@link RetryWorkflowCommand}/{@link TakeoverWorkflowCommand} — {@code
 * targetState} is deterministically derived per run, so it is not part of the semantic identity).
 */
public record ResumeWorkflowCommand(
    @NotBlank @Size(max = 128) String workflowRunId,
    @NotBlank @Size(max = 128) String actorIdentity,
    @NotNull ActorType actorType,
    @NotBlank @Size(max = 256) String idempotencyKey,
    @Size(max = 128) String correlationId,
    @Size(max = 512) String reasonText,
    @NotNull WorkflowState targetState)
    implements WorkflowCommand {}
