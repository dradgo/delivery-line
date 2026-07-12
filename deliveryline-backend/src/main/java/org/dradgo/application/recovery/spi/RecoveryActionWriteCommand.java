package org.dradgo.application.recovery.spi;

import org.dradgo.domain.registry.ActorType;

/**
 * SPI write command for inserting a row into {@code recovery_actions} (story 1.18 Task 2).
 *
 * <p>Field set mirrors the V1 schema column set excluding server-defaults ({@code id}, {@code
 * public_id}, {@code created_at}) which the adapter generates / the database supplies.
 *
 * <p>Story 3.22 (AC3 / OQ-6): {@code reviewerRole} is now exposed additively for the developer
 * takeover path, which persists {@code reviewer_role='developer'} (the takeover invariant — Trap
 * T2: NOT a field on {@code TakeoverWorkflowCommand}). Story 1.18 retry leaves it {@code null} via
 * the 8-arg convenience constructor, preserving every existing call site.
 *
 * @param workflowRunPublicId required FK target ({@code run_…})
 * @param actionType one of {@code retry, rerun, resume, takeover, pause, reconcile,
 *     classify_failure}
 * @param triggeringEventPublicId nullable FK target ({@code evt_…}) — the event that caused the
 *     recovery
 * @param resultingEventPublicId nullable FK target ({@code evt_…}) — the event the recovery emitted
 * @param actorIdentity non-blank operator / agent identity
 * @param actorType one of {@link ActorType}
 * @param idempotencyKey caller-supplied idempotency key; uniqueness enforced by V1 CHECK
 * @param resultStatus one of {@code pending, succeeded, failed} — start with {@code pending} and
 *     flip via {@link RecoveryActionRecordPort#markSucceeded(String)} / {@link
 *     RecoveryActionRecordPort#markFailed(String)}. Exception: {@code classify_failure} inserts
 *     {@code succeeded} directly — its whole effect commits in one transaction, so there is no
 *     post-commit side-effect that a {@code pending} row could wait on (story 4.9 R16).
 * @param reviewerRole nullable {@code reviewer_role} — {@code 'developer'} for takeover; {@code
 *     null} for retry
 */
public record RecoveryActionWriteCommand(
    String workflowRunPublicId,
    String actionType,
    String triggeringEventPublicId,
    String resultingEventPublicId,
    String actorIdentity,
    ActorType actorType,
    String idempotencyKey,
    String resultStatus,
    String reviewerRole) {

  /** Story 1.18 convenience constructor — persists {@code reviewer_role = null} (retry path). */
  public RecoveryActionWriteCommand(
      String workflowRunPublicId,
      String actionType,
      String triggeringEventPublicId,
      String resultingEventPublicId,
      String actorIdentity,
      ActorType actorType,
      String idempotencyKey,
      String resultStatus) {
    this(
        workflowRunPublicId,
        actionType,
        triggeringEventPublicId,
        resultingEventPublicId,
        actorIdentity,
        actorType,
        idempotencyKey,
        resultStatus,
        null);
  }
}
