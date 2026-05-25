package org.dradgo.application.clarification;

import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * Result returned by each {@link ClarificationLifecycleService} transition (story 2.12). Sibling of
 * {@link ClarificationResult} (story 2.11 answer-time writer) and {@link
 * org.dradgo.application.approval.ApprovalResult} (story 2.9 approval writer).
 *
 * <p>Intentionally NOT a member of any sealed result interface — the lifecycle transitions are
 * orchestrator-driven and not surfaced through {@code WorkflowCommandService.executeIdempotent} in
 * MVP (no REST endpoint per Trap T10). Future operator-action REST surface (Epic 4) may widen.
 *
 * @param clarificationId persisted clarification public id ({@code clr_…})
 * @param workflowRunId run public id ({@code run_…})
 * @param status clarification status after the write (one of {@link Clarification#STATUS_ACCEPTED},
 *     {@link Clarification#STATUS_INCORPORATED}, {@link Clarification#STATUS_SUPERSEDED}, {@link
 *     Clarification#STATUS_REJECTED_INVALID})
 * @param transitionedAt server-stamped transition timestamp
 */
public record ClarificationLifecycleResult(
    String clarificationId, String workflowRunId, String status, OffsetDateTime transitionedAt) {

  public ClarificationLifecycleResult {
    Objects.requireNonNull(clarificationId, "clarificationId");
    Objects.requireNonNull(workflowRunId, "workflowRunId");
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(transitionedAt, "transitionedAt");
    if (!Clarification.ALL_STATUSES.contains(status)) {
      throw new IllegalArgumentException(
          "status must be one of " + Clarification.ALL_STATUSES + ", was: " + status);
    }
  }
}
