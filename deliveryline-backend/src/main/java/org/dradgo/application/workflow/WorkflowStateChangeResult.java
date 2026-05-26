package org.dradgo.application.workflow;

import org.dradgo.domain.registry.WorkflowState;

/**
 * Generic state-change result returned by every workflow command that does not need a richer
 * domain-specific shape (approve/reject/retry/takeover/answer-clarification).
 *
 * <p>Story 2.13 extended the record with an optional {@code clarificationStatus} field so the
 * answer-clarification endpoint can surface the post-mutation clarification status (e.g. {@code
 * "answered"}) without forcing a second SPI hop (Trap T7 option a). Callers that have no
 * clarification context use the 3-arg convenience constructor or pass {@code null} explicitly —
 * existing callers are unaffected.
 */
public record WorkflowStateChangeResult(
    String workflowRunId,
    WorkflowState currentState,
    String correlationId,
    String clarificationStatus)
    implements DomainResult {

  public WorkflowStateChangeResult(
      String workflowRunId, WorkflowState currentState, String correlationId) {
    this(workflowRunId, currentState, correlationId, null);
  }
}
