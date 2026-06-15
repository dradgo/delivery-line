package org.dradgo.application.recovery.spi;

import java.time.OffsetDateTime;
import org.dradgo.domain.registry.ActorType;

/**
 * Application-shaped snapshot of a {@code recovery_actions} row (story 1.18 Task 2).
 *
 * <p>Carries both the {@code public_id} ({@code rcv_…}) and the database internal id so the service
 * layer can log / correlate either form. Returned by every mutator on {@link
 * RecoveryActionRecordPort} so callers see the post-write state without a follow-up read.
 *
 * @param publicId {@code rcv_…} public id assigned by the adapter
 * @param internalId database primary key
 * @param workflowRunPublicId FK target
 * @param actionType action_type column ({@code retry} for story 1.18)
 * @param triggeringEventPublicId nullable triggering event public id
 * @param resultingEventPublicId nullable resulting event public id
 * @param actorIdentity actor identity
 * @param actorType actor type
 * @param idempotencyKey idempotency key
 * @param resultStatus one of {@code pending, succeeded, failed}
 * @param createdAt row creation timestamp (UTC)
 * @param reviewerRole nullable {@code reviewer_role} — {@code 'developer'} for takeover (story
 *     3.22), {@code null} for retry
 */
public record RecoveryActionSnapshot(
    String publicId,
    Long internalId,
    String workflowRunPublicId,
    String actionType,
    String triggeringEventPublicId,
    String resultingEventPublicId,
    String actorIdentity,
    ActorType actorType,
    String idempotencyKey,
    String resultStatus,
    OffsetDateTime createdAt,
    String reviewerRole) {

  /**
   * Story 1.18 convenience constructor — {@code reviewer_role = null} (retry path / test fixtures).
   */
  public RecoveryActionSnapshot(
      String publicId,
      Long internalId,
      String workflowRunPublicId,
      String actionType,
      String triggeringEventPublicId,
      String resultingEventPublicId,
      String actorIdentity,
      ActorType actorType,
      String idempotencyKey,
      String resultStatus,
      OffsetDateTime createdAt) {
    this(
        publicId,
        internalId,
        workflowRunPublicId,
        actionType,
        triggeringEventPublicId,
        resultingEventPublicId,
        actorIdentity,
        actorType,
        idempotencyKey,
        resultStatus,
        createdAt,
        null);
  }
}
