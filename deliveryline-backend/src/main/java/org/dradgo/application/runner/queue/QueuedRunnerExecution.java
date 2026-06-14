package org.dradgo.application.runner.queue;

import org.dradgo.domain.registry.RunnerStage;

/**
 * Story 3.17a (AC2) — result of a successful {@link RunnerExecutionQueue#enqueue} call: the minted
 * {@code rex_…} public id of the new {@code queued} row, the run it belongs to, its stage, the
 * dequeue priority + originating correlationId that were persisted, the resulting queue depth (rows
 * in {@code status='queued'} after this insert), and the public id of the appended {@code
 * runner.queued} event.
 *
 * <p>The queue is built dormant in 3.17a (no production caller); story 3.17b's worker pool dequeues
 * these rows.
 */
public record QueuedRunnerExecution(
    String runnerExecutionPublicId,
    String workflowRunPublicId,
    RunnerStage stage,
    int queuePriority,
    String correlationId,
    long currentDepth,
    String queuedEventPublicId) {}
