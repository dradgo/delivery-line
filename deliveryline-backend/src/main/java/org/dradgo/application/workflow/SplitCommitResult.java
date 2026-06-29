package org.dradgo.application.workflow;

import java.util.List;

/**
 * Story 3f-5 (AC7/R7) — the aggregated outcome of a split commit. Mirrors {@code
 * BatchSubmissionResult}'s "report-via-result, not exception" shape: a zero-child commit returns
 * {@code parentDecomposed=false} + {@code outcome=ABORTED_NO_CHILDREN} (HTTP 200/207) rather than
 * throwing, leaving the parent and proposal untouched (R7).
 */
public record SplitCommitResult(
    String workflowRunId,
    String splitProposalId,
    boolean parentDecomposed,
    String outcome,
    List<String> childRunIds,
    List<SubtaskCommitOutcome> subtasks) {

  /** {@code outcome} when ≥1 child was created and the parent transitioned to {@code Split}. */
  public static final String OUTCOME_DECOMPOSED = "decomposed";

  /** {@code outcome} when every subtask failed: parent + proposal untouched (R7). */
  public static final String OUTCOME_ABORTED_NO_CHILDREN = "aborted_no_children";

  public SplitCommitResult {
    childRunIds = childRunIds == null ? List.of() : List.copyOf(childRunIds);
    subtasks = subtasks == null ? List.of() : List.copyOf(subtasks);
  }

  /**
   * One proposed subtask's commit outcome: {@code created} / {@code internal_only} / {@code
   * failed}.
   */
  public record SubtaskCommitOutcome(
      int ordinal, String status, String childRunId, String childTicketRef, String reason) {

    public static final String STATUS_CREATED = "created";
    public static final String STATUS_INTERNAL_ONLY = "internal_only";
    public static final String STATUS_FAILED = "failed";

    public static SubtaskCommitOutcome created(
        int ordinal, String childRunId, String childTicketRef) {
      return new SubtaskCommitOutcome(ordinal, STATUS_CREATED, childRunId, childTicketRef, null);
    }

    public static SubtaskCommitOutcome internalOnly(int ordinal, String childRunId) {
      return new SubtaskCommitOutcome(ordinal, STATUS_INTERNAL_ONLY, childRunId, null, null);
    }

    public static SubtaskCommitOutcome failed(int ordinal, String reason) {
      return new SubtaskCommitOutcome(ordinal, STATUS_FAILED, null, null, reason);
    }

    public boolean isCreatedOrInternal() {
      return STATUS_CREATED.equals(status) || STATUS_INTERNAL_ONLY.equals(status);
    }
  }
}
