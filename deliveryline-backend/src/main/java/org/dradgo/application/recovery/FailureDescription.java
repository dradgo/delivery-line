package org.dradgo.application.recovery;

import java.time.OffsetDateTime;
import org.dradgo.domain.registry.WorkflowState;

/**
 * Application-shaped description of a workflow run's current failure posture (story 1.18 AC5/AC7).
 *
 * <p>Returned by {@link RecoveryService#describeFailure(String)} and consumed by {@link
 * org.dradgo.application.workflow.WorkflowInspectionService} to populate the CLI {@code status} and
 * REST inspection surfaces. All five {@code failed*} / {@code last*Activity*} fields are non-null
 * only when {@code currentState == FAILED}; on non-Failed runs they are all null and {@code
 * nextSafeAction} reflects the canonical non-Failed value (e.g. {@code await_outcome}, {@code
 * view_only}).
 *
 * @param workflowRunPublicId the run this description belongs to
 * @param currentState current workflow state
 * @param failedStage runner stage of the most recent FAILED runner_execution, or null
 * @param lastSuccessfulStage prior_state of the workflow.stateChanged → Failed event, or null
 * @param failureTimestamp created_at of the workflow.stateChanged → Failed event, or null
 * @param failureCategory normalized failure_category from the same event row, or null
 * @param lastActivityTimestamp last_activity_at of the most recent runner_execution row, or
 *     failureTimestamp when no runner_execution exists, or null
 * @param nextSafeAction one of {@code retry, await_manual_reconciliation, await_outcome, view_only}
 *     (string literal — see the helper table in story Dev Notes)
 * @param diagnosticReferenceCorrelationId the most recent {@code correlationId} recorded in {@code
 *     workflow_events.details} for this run, or null if none
 */
public record FailureDescription(
    String workflowRunPublicId,
    WorkflowState currentState,
    String failedStage,
    String lastSuccessfulStage,
    OffsetDateTime failureTimestamp,
    String failureCategory,
    OffsetDateTime lastActivityTimestamp,
    String nextSafeAction,
    String diagnosticReferenceCorrelationId) {}
