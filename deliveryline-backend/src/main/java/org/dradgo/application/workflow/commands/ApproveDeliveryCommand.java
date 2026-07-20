package org.dradgo.application.workflow.commands;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.dradgo.domain.registry.ActorType;

/**
 * Story 3h-4 (AC4, FR78) — operator {@code approve_delivery}: dismiss the pre-review delivery gate
 * and advance to {@code WaitingForReview}. In {@code approve} push mode it performs the push (+ PR
 * per {@code autoCreatePullRequest}) via the resumable delivery seam; in {@code manual} mode it
 * records the out-of-band delivery ({@code delivery.recordedManually}) WITHOUT touching git.
 * Carries no artifact/version binding (the gate is a run-level operator action, not an artifact
 * approval). Canonical fingerprint fields after the shared envelope: {@code workflowRunId} (only —
 * {@code reasonText} is intentionally NOT fingerprinted so a same-key retry with a different reason
 * is an idempotent replay).
 */
public record ApproveDeliveryCommand(
    @NotBlank @Size(max = 128) String workflowRunId,
    @NotBlank @Size(max = 128) String actorIdentity,
    @NotNull ActorType actorType,
    @NotBlank @Size(max = 256) String idempotencyKey,
    @Size(max = 128) String correlationId,
    @Size(max = 512) String reasonText)
    implements WorkflowCommand {}
