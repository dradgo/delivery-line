package org.dradgo.application.workflow;

import org.dradgo.domain.registry.ActorType;

/**
 * Story 3f-4 — command records for the three advisory split actions driven by {@code
 * SplitProposalService}. Each carries the fail-closed HUMAN actor identity + the Idempotency-Key +
 * correlation id resolved by the REST/CLI surface.
 */
public final class SplitProposalCommandSet {

  private SplitProposalCommandSet() {}

  /**
   * {@code request_split} — request an advisory proposal at the spec/review gate.
   *
   * <p>Story 3f-7 (AC5): {@code allowDeepSplit} is the operator override that bypasses the
   * recursive-split depth cap ({@code X-Allow-Deep-Split} header / {@code --allow-deep-split} CLI
   * flag). When {@code false} (the default) a request on a run already at or beyond {@code
   * complex-ticket-flow.max-split-depth} is refused with {@code SPLIT_DEPTH_LIMIT_EXCEEDED}; when
   * {@code true} the deep split proceeds and the override is recorded in the governed history.
   */
  public record RequestSplitCommand(
      String workflowRunId,
      String actorIdentity,
      ActorType actorType,
      String idempotencyKey,
      String correlationId,
      boolean allowDeepSplit) {

    /** Back-compatible factory for the no-override (default) path used by most call sites. */
    public RequestSplitCommand(
        String workflowRunId,
        String actorIdentity,
        ActorType actorType,
        String idempotencyKey,
        String correlationId) {
      this(workflowRunId, actorIdentity, actorType, idempotencyKey, correlationId, false);
    }
  }

  /** {@code repropose_split} — re-run the proposal with operator feedback. */
  public record ReproposeSplitCommand(
      String workflowRunId,
      String feedbackText,
      String actorIdentity,
      ActorType actorType,
      String idempotencyKey,
      String correlationId) {}

  /** {@code continue_as_single} — dismiss the open proposal. */
  public record DeclineSplitCommand(
      String workflowRunId,
      String actorIdentity,
      ActorType actorType,
      String idempotencyKey,
      String correlationId) {}
}
