package org.dradgo.application.workflow.commands;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.dradgo.domain.registry.ActorType;

/**
 * Story 4.8 (AC2 / Reconciliation 9) — the {@link WorkflowCommandService#pauseWorkflow} command:
 * transition a run from a pausable source state to {@code Paused}, routing through the shared
 * idempotency envelope. Mirrors {@link TakeoverWorkflowCommand} — NOT {@link ResumeWorkflowCommand}
 * — because the target state is the constant {@code PAUSED}, so there is NO {@code targetState}
 * field (unlike resume's derived one).
 *
 * <p>{@code actorType}/{@code reviewerRole} are audit-only labels — RBAC is deferred; {@code
 * reviewerRole='workflow_owner'} is applied when {@code RecoveryService.pause} builds the {@code
 * recovery_actions} row, NOT here. {@code reasonText} is REQUIRED at the {@code
 * RecoveryService.pause} boundary (blank → {@code MISSING_REASON_TEXT}), a story-level guard — the
 * transition table does not mandate a reason for {@code → Paused}, so the record keeps the shared
 * optional shape.
 *
 * <p>Canonical fingerprint fields after the shared envelope are: {@code workflowRunId}, {@code
 * reasonText} (mirroring {@link RetryWorkflowCommand}/{@link TakeoverWorkflowCommand}).
 */
public record PauseWorkflowCommand(
    @NotBlank @Size(max = 128) String workflowRunId,
    @NotBlank @Size(max = 128) String actorIdentity,
    @NotNull ActorType actorType,
    @NotBlank @Size(max = 256) String idempotencyKey,
    @Size(max = 128) String correlationId,
    @Size(max = 512) String reasonText)
    implements WorkflowCommand {}
