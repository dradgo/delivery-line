package org.dradgo.application.workflow.spi;

import org.dradgo.application.workflow.NewSplitProposal;

/**
 * Story 3f-4 — write side of the split_proposals table. The caller ({@code SplitProposalHarvester}
 * / {@code SplitProposalService}) is {@code @Transactional}; the adapter only executes statements.
 *
 * <p>The "one open proposal per run" partial unique index means a re-propose must {@code
 * supersedeOpenForRun} BEFORE {@code insertOpen} within the same transaction
 * (supersede-then-insert) to avoid a two-open-row conflict
 * ([[caught-idempotency-conflict-poisons-shared-tx]]). Statements run immediately on the JDBC
 * connection (no JPA flush trap).
 */
public interface SplitProposalWritePort {

  /** Insert a new proposal in {@code open} status. */
  void insertOpen(NewSplitProposal proposal);

  /**
   * Mark the run's current {@code open} proposal {@code superseded}. Returns the number of rows
   * updated (0 or 1 given the partial unique index).
   */
  int supersedeOpenForRun(String workflowRunId);

  /**
   * Mark the run's current {@code open} proposal {@code dismissed} ("continue as one ticket").
   * Returns the number of rows updated (0 or 1).
   */
  int dismissOpenForRun(String workflowRunId);

  /**
   * Story 3f-4 (AC4/R3) — persist the redacted re-propose operator feedback, keyed by the reviewer
   * execution that carries the dispatch, so the context-bundle compose can materialize it
   * by-reference (kind {@code split.feedback}). The {@code feedbackText} is ALREADY redacted by the
   * caller before persistence/egress.
   */
  void insertFeedback(String publicId, String runnerExecutionId, String feedbackText);
}
