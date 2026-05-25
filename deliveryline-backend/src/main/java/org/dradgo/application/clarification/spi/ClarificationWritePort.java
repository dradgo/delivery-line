package org.dradgo.application.clarification.spi;

import java.time.OffsetDateTime;
import org.dradgo.application.clarification.Clarification;
import org.dradgo.domain.registry.ActorType;

/**
 * Write SPI over the {@code clarifications} table (story 2.11 + story 2.12). Sibling of {@link
 * ClarificationReadPort}. Adapter implementations participate in the caller's transaction (REQUIRED
 * propagation) so the row insert / update and the matching {@code clarification.*} event append
 * commit or roll back together.
 *
 * <p>The DB-level {@code uq_clarifications_idempotency_key} UNIQUE constraint is a defense-in-depth
 * backstop — the persistence adapter maps a hit on {@code insertOpen} to {@code
 * IDEMPOTENCY_KEY_CONFLICT}, mirroring the {@link
 * org.dradgo.application.approval.spi.ApprovalWritePort#insert} contract.
 *
 * <p>Story 2.11 trap T3: only {@code insertOpen} + {@code recordAnswer} are exposed at the adapter
 * level for the answer-time path. Story 2.12 adds the four lifecycle transitions ({@code
 * markAccepted} / {@code markIncorporated} / {@code markSuperseded} / {@code markRejectedInvalid})
 * driven by {@code ClarificationLifecycleService}; the adapter stays dumb-persistence — status
 * guards live in the application layer.
 */
public interface ClarificationWritePort {

  /**
   * Insert a brand-new {@code status = 'open'} clarification row. Used by test fixtures (seed open
   * rows so {@code submitAnswer} has something to answer) and, in Epic 3, by the spec-runner
   * question-marker handler.
   *
   * @throws org.dradgo.domain.DomainException with code {@code IDEMPOTENCY_KEY_CONFLICT} when the
   *     DB-level {@code uq_clarifications_idempotency_key} UNIQUE constraint fires.
   */
  Clarification insertOpen(NewClarification newClarification);

  /**
   * Update an existing {@code open} or {@code answered} (or {@code accepted}, per AC8 re-answer
   * semantics) clarification row to record the latest answer. Story 2.11 trap T9 — the adapter
   * writes all three answer fields ({@code answer_text}, {@code answered_by_actor}, {@code
   * answered_at}) in one UPDATE so the {@code ck_clarifications_answered_fields_paired} CHECK is
   * never violated.
   *
   * <p>The application-layer guard for {@code CLARIFICATION_TERMINAL_STATE} lives in {@link
   * org.dradgo.application.clarification.ClarificationService} (Task 7); the adapter stays
   * dumb-persistence.
   */
  Clarification recordAnswer(RecordAnswer recordAnswer);

  /**
   * Story 2.12: transition an {@code answered} clarification to {@code accepted}, stamping the
   * {@code accepted_at} V9 column. Status guard ({@code STATUS_ANSWERED → STATUS_ACCEPTED}) is
   * enforced by {@link org.dradgo.application.clarification.ClarificationLifecycleService} — the
   * adapter is dumb-persistence and relies on {@code ck_clarifications_status_fields_paired} (V9)
   * as defense-in-depth.
   */
  Clarification markAccepted(MarkAccepted markAccepted);

  /**
   * Story 2.12: transition an {@code accepted} clarification to {@code incorporated}, stamping
   * {@code incorporated_at}, {@code incorporation_event_id} (resolved via {@link
   * org.dradgo.adapters.persistence.repository.WorkflowEventRepository#findIdByPublicId}), and the
   * status. Trap T13 — the lifecycle service appends the {@code clarification.incorporated} event
   * BEFORE invoking this method so the FK target row exists when the UPDATE flushes.
   */
  Clarification markIncorporated(MarkIncorporated markIncorporated);

  /**
   * Story 2.12: transition an {@code accepted} clarification to {@code superseded}, stamping the
   * supersession pair ({@code superseded_by_artifact_id} + {@code superseded_by_artifact_version})
   * and the controlled-vocabulary {@code no_effect_reason}.
   */
  Clarification markSuperseded(MarkSuperseded markSuperseded);

  /**
   * Story 2.12: transition an {@code answered} clarification to {@code rejected_invalid}, stamping
   * the controlled-vocabulary {@code no_effect_reason}.
   */
  Clarification markRejectedInvalid(MarkRejectedInvalid markRejectedInvalid);

  /**
   * Insert payload for a fresh open clarification. {@code publicId} is supplied externally (caller-
   * generated via {@link org.dradgo.domain.id.PublicIdPrefixes#CLARIFICATION}) so log lines remain
   * deterministic before the row exists.
   */
  record NewClarification(
      String publicId,
      String workflowRunPublicId,
      String artifactPublicId,
      int artifactVersion,
      String questionId,
      String questionText,
      String idempotencyKey) {}

  /** Update payload for {@link #recordAnswer}. */
  record RecordAnswer(
      String clarificationPublicId,
      String answerText,
      String answeredByActor,
      ActorType answeredByActorType,
      OffsetDateTime answeredAt) {}

  /**
   * Update payload for {@link #markAccepted}. Trap T8 — outer transaction is the boundary; the
   * adapter is dumb-persistence.
   */
  record MarkAccepted(String clarificationPublicId, OffsetDateTime acceptedAt) {}

  /**
   * Update payload for {@link #markIncorporated}. The {@code incorporation_event_id} is set to the
   * FK of the matching {@code workflow_events} row written by the lifecycle service (resolved by
   * {@code incorporationEventPublicId} in the adapter). Mirrors the foreign-key-by-publicId pattern
   * in {@code ApprovalEntityMapper}.
   */
  record MarkIncorporated(
      String clarificationPublicId,
      String incorporatedIntoArtifactPublicId,
      int incorporatedIntoArtifactVersion,
      String incorporationEventPublicId,
      OffsetDateTime incorporatedAt) {}

  /** Update payload for {@link #markSuperseded}. */
  record MarkSuperseded(
      String clarificationPublicId,
      String supersededByArtifactPublicId,
      int supersededByArtifactVersion,
      String noEffectReason,
      OffsetDateTime decidedAt) {}

  /** Update payload for {@link #markRejectedInvalid}. */
  record MarkRejectedInvalid(
      String clarificationPublicId, String noEffectReason, OffsetDateTime decidedAt) {}
}
