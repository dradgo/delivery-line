package org.dradgo.application.workflow;

import org.dradgo.domain.registry.ActorType;

/**
 * Story 3f-5 (AC2) — command to commit a run's current open split proposal. Carries the fail-closed
 * HUMAN actor identity, the top-level {@code Idempotency-Key}, and the correlation id resolved by
 * the REST/CLI surface. The canonical executor is {@link SplitCommitService#commit}. Mirrors the
 * 3f-4 {@code SplitProposalCommandSet} command shape.
 */
public record ApproveSplitCommand(
    String workflowRunId,
    String actorIdentity,
    ActorType actorType,
    String idempotencyKey,
    String correlationId) {}
