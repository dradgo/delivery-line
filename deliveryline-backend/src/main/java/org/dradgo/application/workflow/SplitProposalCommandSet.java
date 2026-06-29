package org.dradgo.application.workflow;

import org.dradgo.domain.registry.ActorType;

/**
 * Story 3f-4 — command records for the three advisory split actions driven by {@code
 * SplitProposalService}. Each carries the fail-closed HUMAN actor identity + the Idempotency-Key +
 * correlation id resolved by the REST/CLI surface.
 */
public final class SplitProposalCommandSet {

  private SplitProposalCommandSet() {}

  /** {@code request_split} — request an advisory proposal at the spec/review gate. */
  public record RequestSplitCommand(
      String workflowRunId,
      String actorIdentity,
      ActorType actorType,
      String idempotencyKey,
      String correlationId) {}

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
