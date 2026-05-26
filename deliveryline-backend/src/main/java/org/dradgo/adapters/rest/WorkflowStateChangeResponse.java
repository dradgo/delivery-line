package org.dradgo.adapters.rest;

import io.swagger.v3.oas.annotations.media.Schema;
import org.dradgo.application.workflow.WorkflowStateChangeResult;

/**
 * Response body for the approve-spec and reject-spec endpoints. Story 2.13 round-4 P-R4-7: the
 * structural fields are marked {@code requiredMode=REQUIRED} so generated TS clients can rely on
 * them being present (matching the {@link ClarificationAnswerResponse} contract strength). {@code
 * correlationId} stays optional — it is null in test slices that don't register {@code
 * CorrelationIdFilter}.
 */
public record WorkflowStateChangeResponse(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String workflowRunId,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String currentState,
    @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED) String correlationId) {

  public static WorkflowStateChangeResponse from(WorkflowStateChangeResult result) {
    String currentState = result.currentState() == null ? null : result.currentState().value();
    return new WorkflowStateChangeResponse(
        result.workflowRunId(), currentState, result.correlationId());
  }
}
