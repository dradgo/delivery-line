package org.dradgo.application.workflow.commands;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.dradgo.domain.registry.ActorType;

/**
 * Story 3h-2 (AC5, FR76) — operator {@code approve_lint}: dismiss the pre-review lint gate and
 * resume the delivery tail (push + WaitingForReview + reviewer enqueue). Carries no
 * artifact/version binding (the gate is a run-level operator action, not an artifact approval).
 * Canonical fingerprint fields after the shared envelope: {@code workflowRunId}, {@code
 * reasonText}.
 */
public record ApproveLintCommand(
    @NotBlank @Size(max = 128) String workflowRunId,
    @NotBlank @Size(max = 128) String actorIdentity,
    @NotNull ActorType actorType,
    @NotBlank @Size(max = 256) String idempotencyKey,
    @Size(max = 128) String correlationId,
    @Size(max = 512) String reasonText)
    implements WorkflowCommand {}
