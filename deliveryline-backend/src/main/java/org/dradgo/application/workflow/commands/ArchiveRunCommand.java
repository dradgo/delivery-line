package org.dradgo.application.workflow.commands;

import org.dradgo.domain.registry.ActorType;

/**
 * Story 3d-8 (FR67, AC3) — operator (or SYSTEM auto-scan) request to soft-hide a run.
 *
 * <p>Deliberately a plain record, NOT a sealed {@link WorkflowCommand} variant (the {@code
 * SubmitBatchCommand} precedent): archiving is orthogonal to the workflow lifecycle and never
 * routes through {@code WorkflowCommandService}'s state-transition idempotency engine. {@link
 * org.dradgo.application.workflow.WorkflowArchiveService} runs its own generic-{@code
 * IdempotencyService} reservation (fingerprint excludes {@code reason} so a same-key replay is
 * stable).
 *
 * @param workflowRunPublicId the {@code run_} public id of the run to hide
 * @param actorIdentity who is hiding the run (operator identity, or a SYSTEM label for
 *     auto-archive)
 * @param actorType the actor type (HUMAN for a manual hide, SYSTEM/AGENT for the auto-scan)
 * @param idempotencyKey required idempotency key (same-key replay is a no-op)
 * @param correlationId optional correlation id echoed into the audit event details
 * @param reason required who/when/why justification (ADR 0027) recorded on the event
 */
public record ArchiveRunCommand(
    String workflowRunPublicId,
    String actorIdentity,
    ActorType actorType,
    String idempotencyKey,
    String correlationId,
    String reason) {}
