package org.dradgo.application.workflow.commands;

import org.dradgo.domain.registry.ActorType;

/**
 * Story 3d-8 (FR67, AC4) — operator request to reverse a soft-hide (un-archive). Symmetric twin of
 * {@link ArchiveRunCommand}; see it for the design rationale. {@code reason} is optional on un-hide
 * (re-surfacing a run rarely needs justification), but still recorded on the {@code
 * workflow.unarchived} event when supplied.
 *
 * @param workflowRunPublicId the {@code run_} public id of the run to un-hide
 * @param actorIdentity who is un-hiding the run
 * @param actorType the actor type
 * @param idempotencyKey required idempotency key (same-key replay is a no-op)
 * @param correlationId optional correlation id echoed into the audit event details
 * @param reason optional justification recorded on the event when present
 */
public record UnarchiveRunCommand(
    String workflowRunPublicId,
    String actorIdentity,
    ActorType actorType,
    String idempotencyKey,
    String correlationId,
    String reason) {}
