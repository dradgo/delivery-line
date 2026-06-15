package org.dradgo.application.workflow;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * Story 3.18 — per-ticket outcome inside a {@link BatchSubmissionResult}.
 *
 * <p>{@code queueResult} is {@link #QUEUED} for a ticket whose single-submit succeeded (it now sits
 * on the runner execution queue) or {@link #REJECTED} for a ticket whose submit raised a {@code
 * DomainException}. A rejected ticket carries the originating {@code rejectionCode} (the {@code
 * DomainErrorCode.value()}) and {@code rejectionReason} (the exception message); {@code runId} is
 * populated only for queued tickets. Rejected tickets have no {@code runner_executions} row, which
 * is why the full per-ticket list is persisted (in {@code batch_submissions.result_json}) for
 * faithful idempotent replay (Reconciliation 5 / Decision D-PERSIST, result_json variant).
 */
public record TicketBatchResult(
    String ticketRef,
    String runId,
    String queueResult,
    String rejectionReason,
    String rejectionCode) {

  public static final String QUEUED = "queued";
  public static final String REJECTED = "rejected";

  public static TicketBatchResult queued(String ticketRef, String runId) {
    return new TicketBatchResult(ticketRef, runId, QUEUED, null, null);
  }

  public static TicketBatchResult rejected(
      String ticketRef, String rejectionCode, String rejectionReason) {
    return new TicketBatchResult(ticketRef, null, REJECTED, rejectionReason, rejectionCode);
  }

  @JsonIgnore
  public boolean isQueued() {
    return QUEUED.equals(queueResult);
  }
}
