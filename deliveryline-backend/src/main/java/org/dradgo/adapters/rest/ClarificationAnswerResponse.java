package org.dradgo.adapters.rest;

import io.swagger.v3.oas.annotations.media.Schema;
import org.dradgo.application.workflow.WorkflowStateChangeResult;

/**
 * Response body for {@code POST
 * /api/v1/workflows/&#123;workflowRunId&#125;/clarifications/&#123;clarificationId&#125;/answer}.
 *
 * <p>Answering a clarification does NOT mutate the workflow state (story 2.13 trap T6) — {@code
 * currentState} renders the run's <em>unchanged</em> state (typically {@code
 * WaitingForSpecApproval}); only the clarification row moves from {@code open} → {@code answered}.
 * On idempotent replay the {@code clarificationStatus} reflects the persisted row's status
 * (typically still {@code "answered"} or, for the re-answer-in-accepted-state AC8 path, {@code
 * "accepted"}).
 *
 * <p>Story 2.13 review P11: marks the structural fields as {@code requiredMode=REQUIRED} in the
 * OpenAPI snapshot so generated TS clients don't have to defensively handle null on every field.
 * {@code correlationId} stays optional — it is null in test slices that don't register {@code
 * CorrelationIdFilter}.
 */
public record ClarificationAnswerResponse(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String workflowRunId,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String clarificationId,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            description =
                "Clarification row status after the answer commit. Typical values follow the"
                    + " clarification lifecycle (open / answered / accepted / incorporated /"
                    + " superseded / rejected_invalid). Story 2.13 round-4 P-R4-16: idempotent"
                    + " replays of pre-2.13 (\"legacy 2-segment\") result-refs whose underlying"
                    + " clarification row has been hard-deleted before the replay arrived surface"
                    + " the sentinel value \"unknown\" so the previously-200 response stays 200 and"
                    + " the idempotent-replay contract is preserved. TS clients should default-case"
                    + " unknown status values rather than narrowing exhaustively.")
        String clarificationStatus,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String currentState,
    @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED) String correlationId) {

  public static ClarificationAnswerResponse from(
      WorkflowStateChangeResult result, String clarificationId) {
    return new ClarificationAnswerResponse(
        result.workflowRunId(),
        clarificationId,
        result.clarificationStatus(),
        result.currentState() == null ? null : result.currentState().value(),
        result.correlationId());
  }
}
