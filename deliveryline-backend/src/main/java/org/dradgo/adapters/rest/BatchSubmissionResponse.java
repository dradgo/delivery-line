package org.dradgo.adapters.rest;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.dradgo.application.workflow.BatchSubmissionResult;
import org.dradgo.application.workflow.TicketBatchResult;

/**
 * Story 3.18 — response body for {@code POST /api/v1/workflows/batch}. ALWAYS returned with HTTP
 * 200 regardless of per-ticket rejections; rejections are surfaced in the {@code tickets} array,
 * never as a non-2xx status (AC6).
 */
public record BatchSubmissionResponse(
    @Schema(requiredMode = RequiredMode.REQUIRED) String batchId,
    @Schema(requiredMode = RequiredMode.REQUIRED) String submittedAt,
    @Schema(requiredMode = RequiredMode.REQUIRED) String actorIdentity,
    @Schema(requiredMode = RequiredMode.REQUIRED) int total,
    @Schema(requiredMode = RequiredMode.REQUIRED) int queuedCount,
    @Schema(requiredMode = RequiredMode.REQUIRED) int rejectedCount,
    @Schema(requiredMode = RequiredMode.REQUIRED) List<TicketResult> tickets) {

  public static BatchSubmissionResponse from(BatchSubmissionResult result) {
    return new BatchSubmissionResponse(
        result.batchId(),
        DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(result.submittedAt()),
        result.actorIdentity(),
        result.total(),
        result.queuedCount(),
        result.rejectedCount(),
        result.tickets().stream().map(TicketResult::from).toList());
  }

  /** Per-ticket outcome in the batch response. */
  public record TicketResult(
      @Schema(requiredMode = RequiredMode.REQUIRED) String ticketRef,
      @Schema(requiredMode = RequiredMode.NOT_REQUIRED) String runId,
      @Schema(
              requiredMode = RequiredMode.REQUIRED,
              allowableValues = {"queued", "rejected"})
          String queueResult,
      @Schema(requiredMode = RequiredMode.NOT_REQUIRED) String rejectionReason,
      @Schema(requiredMode = RequiredMode.NOT_REQUIRED) String rejectionCode) {

    public static TicketResult from(TicketBatchResult ticket) {
      return new TicketResult(
          ticket.ticketRef(),
          ticket.runId(),
          ticket.queueResult(),
          ticket.rejectionReason(),
          ticket.rejectionCode());
    }
  }
}
