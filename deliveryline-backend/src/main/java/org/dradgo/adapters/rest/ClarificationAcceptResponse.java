package org.dradgo.adapters.rest;

import io.swagger.v3.oas.annotations.media.Schema;
import org.dradgo.application.workflow.WorkflowStateChangeResult;

/**
 * Response body for {@code POST
 * /api/v1/workflows/&#123;workflowRunId&#125;/clarifications/&#123;clarificationId&#125;/accept}
 * (story 3e-2 AC1).
 *
 * <p>Accepting a clarification does NOT mutate the workflow state (twin of the answer endpoint,
 * trap T6) — {@code currentState} renders the run's <em>unchanged</em> state (typically {@code
 * WaitingForSpecApproval}); only the clarification row moves {@code answered} → {@code accepted}.
 * On idempotent replay (or a re-accept of an already-{@code accepted} row) {@code
 * clarificationStatus} reflects the persisted {@code accepted} status.
 */
public record ClarificationAcceptResponse(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String workflowRunId,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String clarificationId,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            description =
                "Clarification row status after the accept commit. Expected \"accepted\" on the"
                    + " happy path; an idempotent replay reflects the persisted status.")
        String clarificationStatus,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String currentState,
    @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED) String correlationId) {

  public static ClarificationAcceptResponse from(
      WorkflowStateChangeResult result, String clarificationId) {
    return new ClarificationAcceptResponse(
        result.workflowRunId(),
        clarificationId,
        result.clarificationStatus(),
        result.currentState() == null ? null : result.currentState().value(),
        result.correlationId());
  }
}
