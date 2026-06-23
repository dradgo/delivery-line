package org.dradgo.application.workflow;

import java.time.OffsetDateTime;
import org.dradgo.domain.registry.WorkflowState;

/**
 * Story 3d-8 (FR67, AC3/AC4) — result of an archive / un-archive command. {@code currentState} is
 * unchanged by the operation (soft-hide is orthogonal to the lifecycle); {@code archivedAt} carries
 * the new marker value (non-null after a hide, {@code null} after an un-hide) so the REST/CLI
 * client reflects the new state immediately.
 *
 * @param workflowRunId the run that was hidden / un-hidden
 * @param currentState the run's (unchanged) workflow state
 * @param archivedAt the soft-hide marker after the operation: non-null after archive, null after
 *     un-archive
 * @param correlationId the call's correlation id (echoed back), or {@code null}
 * @param replay {@code true} when this result was reconstructed from a prior idempotent attempt (no
 *     second event was appended)
 */
public record WorkflowArchiveResult(
    String workflowRunId,
    WorkflowState currentState,
    OffsetDateTime archivedAt,
    String correlationId,
    boolean replay) {}
