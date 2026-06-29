package org.dradgo.application.workflow.spi;

import java.util.Optional;
import org.dradgo.application.workflow.SplitProposalView;

/**
 * Story 3f-4 — read side of the split_proposals table. Returns decoded {@link SplitProposalView}s
 * (the proposal_json parsed into subtasks/dependencies). Read views live in {@code
 * application.workflow} (NOT here in {@code .spi}) so the REST DTO maps them without tripping the
 * REST-stays-thin ArchUnit pin (story 3f-3 lesson).
 */
public interface SplitProposalReadPort {

  /** True when the run has a proposal in {@code open} status — threads into the action matrix. */
  boolean hasOpenForRun(String workflowRunId);

  /** The run's current {@code open} proposal, if any. */
  Optional<SplitProposalView> findOpenForRun(String workflowRunId);

  /**
   * The run's most recent proposal regardless of status (open first, else the latest superseded /
   * dismissed / approved by created_at). Backs GET /split-proposal so the panel can show the last
   * state even after a decline.
   */
  Optional<SplitProposalView> findLatestForRun(String workflowRunId);

  /**
   * Story 3f-4 (AC4/R3) — the public id ({@code splfb_…}) of the redacted re-propose feedback row
   * stored for the given reviewer execution, if any. The context-bundle compose materializes it as
   * a {@code {referenceId, kind:'split.feedback'}} priorFeedbackReferences entry (by reference,
   * never inlined). Empty for an initial request (no feedback).
   */
  Optional<String> findFeedbackReferenceId(String runnerExecutionId);

  /**
   * The run's current {@code workflow_runs.split_proposal_loop_count} (0 when never re-proposed).
   */
  int currentSplitProposalLoopCount(String workflowRunId);
}
