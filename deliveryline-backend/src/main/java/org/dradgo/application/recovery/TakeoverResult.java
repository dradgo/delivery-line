package org.dradgo.application.recovery;

import org.dradgo.domain.registry.WorkflowState;

/**
 * Result of {@link DeveloperTakeoverService#takeoverWorkflow}. The takeover twin of {@link
 * RetryRecoveryResult}: instead of a dispatched runner it reports how many in-flight + queued
 * runner executions were cancelled, the resulting {@code TakenOver} state, and the preserved GitHub
 * PR reference (FR33 handoff trail).
 *
 * <p>Story 3.22 ships SERVICE-ONLY (OQ-1) — REST/CLI surfacing of {@code cancelledInFlightCount} /
 * {@code cancelledQueuedCount} / {@code preservedPrReference} is deferred to story 3.25. They are
 * populated now so 3.25's response wiring is a pure adapter change.
 *
 * @param workflowRunId the run that was taken over ({@code run_…})
 * @param recoveryActionPublicId the {@code recovery_actions} row id ({@code rcv_…})
 * @param resultingState always {@link WorkflowState#TAKEN_OVER}
 * @param resultingEventPublicId the {@code workflow.stateChanged → TakenOver} event id (the audit
 *     anchor; also the recovery row's {@code resulting_event_id}). Null only on a degraded replay
 *     whose linked event was archived.
 * @param cancelledInFlightCount number of {@code pending|running} runner rows flipped to {@code
 *     cancelled_for_takeover}; {@code null} on replay (the cancels already happened on the original
 *     call and are not recomputed)
 * @param cancelledQueuedCount number of {@code queued} runner rows flipped to {@code
 *     cancelled_for_takeover}; {@code null} on replay
 * @param preservedPrReference the active {@code github_pr} link's external ref, left untouched by
 *     takeover (FR33); {@code null} when the run carries no GitHub PR link or on replay
 * @param correlationId the CURRENT call's correlation id (mirrors {@link RetryRecoveryResult}'s
 *     replay correlation-echo contract)
 * @param replayed {@code true} when this result reconstructs a prior succeeded takeover (idempotent
 *     replay), {@code false} for a fresh takeover
 */
public record TakeoverResult(
    String workflowRunId,
    String recoveryActionPublicId,
    WorkflowState resultingState,
    String resultingEventPublicId,
    Integer cancelledInFlightCount,
    Integer cancelledQueuedCount,
    String preservedPrReference,
    String correlationId,
    boolean replayed) {}
