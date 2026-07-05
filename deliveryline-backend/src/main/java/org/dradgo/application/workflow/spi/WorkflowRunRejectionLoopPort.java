package org.dradgo.application.workflow.spi;

/**
 * Persistence port for the spec-rejection loop counter + escalation marker that {@code
 * ApprovalService.rejectSpec} (story 2.10) mutates atomically inside the outer command transaction.
 *
 * <p>Single-purpose interface so the broader {@link WorkflowRunReadPort} stays focused on read
 * lookups for inspection; mutation lives here. The adapter implementation participates in the
 * caller's transaction (no {@code REQUIRES_NEW}) so the two operations roll back with the outer
 * approve/reject pipeline on any downstream failure.
 */
public interface WorkflowRunRejectionLoopPort {

  /**
   * Atomically increments {@code workflow_runs.spec_rejection_loop_count} for the given run and
   * returns the post-increment value.
   *
   * <p>The increment MUST return the value produced by <strong>this</strong> update, not a later
   * concurrent transaction's update. Implementations should therefore prefer a single SQL round
   * trip (for example PostgreSQL {@code UPDATE ... RETURNING}) over an update followed by a
   * separate re-read.
   *
   * @param workflowRunPublicId the run's public id (must exist)
   * @return the new counter value (always {@code >= 1})
   * @throws org.dradgo.domain.DomainException with {@code RUN_NOT_FOUND} if no row matches
   */
  int incrementAndReadLoopCount(String workflowRunPublicId);

  /**
   * Story 3.21 — atomically increments {@code workflow_runs.implementation_rejection_loop_count}
   * for the given run and returns the post-increment value. The implementation-stage twin of {@link
   * #incrementAndReadLoopCount(String)}; same single-round-trip {@code UPDATE ... RETURNING}
   * contract. The escalation marker is shared between the two loops (Decision D5), so {@link
   * #markEscalationOnce(String)} / {@link #isEscalationMarkerSet(String)} are reused unchanged.
   *
   * @param workflowRunPublicId the run's public id (must exist)
   * @return the new counter value (always {@code >= 1})
   * @throws org.dradgo.domain.DomainException with {@code RUN_NOT_FOUND} if no row matches
   */
  int incrementAndReadImplementationLoopCount(String workflowRunPublicId);

  /**
   * Story 3f-4 — atomically increments {@code workflow_runs.split_proposal_loop_count} for the
   * given run and returns the post-increment value. The re-propose twin of {@link
   * #incrementAndReadLoopCount(String)}; same single-round-trip {@code UPDATE ... RETURNING}
   * contract. Its primary purpose is a DISTINCT dispatch idempotency key per re-propose attempt
   * ({@code split-proposal:<run>:<loopCount>}); the escalation marker is SHARED with the spec /
   * implementation loops, so {@link #markEscalationOnce(String)} / {@link
   * #isEscalationMarkerSet(String)} are reused unchanged.
   *
   * @param workflowRunPublicId the run's public id (must exist)
   * @return the new counter value (always {@code >= 1})
   * @throws org.dradgo.domain.DomainException with {@code RUN_NOT_FOUND} if no row matches
   */
  int incrementAndReadSplitProposalLoopCount(String workflowRunPublicId);

  /**
   * Story 3h-1 (AC4) — atomically increments {@code workflow_runs.build_fix_loop_count} for the
   * given run and returns the post-increment value. The build-validation twin of {@link
   * #incrementAndReadImplementationLoopCount(String)}; same single-round-trip {@code UPDATE ...
   * RETURNING} contract. Its purposes are a DISTINCT dispatch idempotency key per fix attempt
   * ({@code build-fix:<run>:<count>}) AND the cap check against {@code build-fix-max-loops}; the
   * escalation marker is SHARED with the spec / implementation / split loops (Decision D5), so
   * {@link #markEscalationOnce(String)} / {@link #isEscalationMarkerSet(String)} are reused
   * unchanged.
   *
   * @param workflowRunPublicId the run's public id (must exist)
   * @return the new counter value (always {@code >= 1})
   * @throws org.dradgo.domain.DomainException with {@code RUN_NOT_FOUND} if no row matches
   */
  int incrementAndReadBuildFixLoopCount(String workflowRunPublicId);

  /**
   * Atomically flips {@code workflow_runs.escalation_marker_set} from {@code false} to {@code true}
   * using a {@code WHERE escalation_marker_set = false} guard so subsequent calls are no-ops.
   *
   * @param workflowRunPublicId the run's public id
   * @return {@code 1} if this call just-flipped the marker; {@code 0} if it was already set
   * @throws org.dradgo.domain.DomainException with {@code RUN_NOT_FOUND} if no row matches
   */
  int markEscalationOnce(String workflowRunPublicId);

  /**
   * Reads the current {@code escalation_marker_set} for the given run. Used by {@code
   * ApprovalService.rejectSpec} to short-circuit the threshold-check branch when the marker is
   * already set (avoiding a wasted {@link #markEscalationOnce(String)} UPDATE).
   *
   * @param workflowRunPublicId the run's public id
   * @return {@code true} if the marker is set, {@code false} otherwise (including when the run does
   *     not exist — callers are expected to have validated existence earlier in the pipeline)
   */
  boolean isEscalationMarkerSet(String workflowRunPublicId);
}
