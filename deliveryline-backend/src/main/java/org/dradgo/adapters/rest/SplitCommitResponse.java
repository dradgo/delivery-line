package org.dradgo.adapters.rest;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.dradgo.application.workflow.SplitCommitResult;

/**
 * Story 3f-5 (AC7) — the result of committing a split proposal via {@code POST
 * /{workflowRunId}/split/approve}. Reports each subtask's outcome ({@code created} / {@code
 * internal_only} / {@code failed}), whether the parent decomposed, and the created child run ids. A
 * zero-child commit returns {@code parentDecomposed=false} + {@code outcome=aborted_no_children}
 * with HTTP 200 (the parent + proposal are untouched, R7).
 */
@Schema(name = "SplitCommitResponse")
public record SplitCommitResponse(
    @Schema(example = "run_abc123") String workflowRunId,
    @Schema(example = "splprop_abc123") String splitProposalId,
    @Schema(description = "True when ≥1 child was created and the parent transitioned to Split.")
        boolean parentDecomposed,
    @Schema(
            description = "Aggregate outcome.",
            allowableValues = {"decomposed", "aborted_no_children"},
            example = "decomposed")
        String outcome,
    @Schema(description = "The created child run ids, in subtask ordinal order.")
        List<String> childRunIds,
    List<SubtaskOutcomePayload> subtasks) {

  static SplitCommitResponse from(SplitCommitResult result) {
    return new SplitCommitResponse(
        result.workflowRunId(),
        result.splitProposalId(),
        result.parentDecomposed(),
        result.outcome(),
        result.childRunIds(),
        result.subtasks().stream().map(SubtaskOutcomePayload::from).toList());
  }

  /** One subtask's commit outcome. */
  @Schema(name = "SplitSubtaskOutcomePayload")
  public record SubtaskOutcomePayload(
      @Schema(example = "1") int ordinal,
      @Schema(
              description = "Per-subtask outcome.",
              allowableValues = {"created", "internal_only", "failed"},
              example = "created")
          String status,
      @Schema(example = "run_child123") String childRunId,
      @Schema(example = "LIN-456") String childTicketRef,
      @Schema(description = "Failure cause class (only when status=failed).") String reason) {

    static SubtaskOutcomePayload from(SplitCommitResult.SubtaskCommitOutcome o) {
      return new SubtaskOutcomePayload(
          o.ordinal(), o.status(), o.childRunId(), o.childTicketRef(), o.reason());
    }
  }
}
