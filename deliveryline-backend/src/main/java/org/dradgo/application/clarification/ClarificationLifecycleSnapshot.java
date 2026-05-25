package org.dradgo.application.clarification;

import java.time.OffsetDateTime;
import java.util.Objects;
import org.dradgo.domain.registry.ActorType;

/**
 * Application-layer projection over a {@code clarifications} row that includes story 2.12 V9
 * lifecycle metadata columns. Sibling of {@link Clarification} — the lean answer-time projection.
 *
 * <p>Trap T1: do NOT widen {@link Clarification} with the V9 fields. The lean projection is
 * preserved for read consumers (e.g. {@code ClarificationService.submitAnswer}) that only need the
 * core identity + answer state. {@code ClarificationLifecycleSnapshot} is the dedicated read
 * projection for the lifecycle view-layer consumer ({@code
 * WorkflowInspectionService.getClarificationStatus}).
 *
 * <p>Fields carrying lifecycle metadata (V9):
 *
 * <ul>
 *   <li>{@code acceptedAt} — set on {@code answered → accepted}.
 *   <li>{@code incorporatedAt} — set on {@code accepted → incorporated}.
 *   <li>{@code incorporationEventPublicId} — public id of the matching {@code
 *       clarification.incorporated} event row (derived by the adapter from {@code
 *       incorporation_event_id} → {@code workflow_events.public_id}).
 *   <li>{@code supersededByArtifactPublicId} / {@code supersededByArtifactVersion} — composite
 *       reference to the spec rebuild that did not acknowledge this clarification.
 *   <li>{@code noEffectReason} — controlled-vocabulary token (Trap T2; see {@code
 *       ClarificationLifecycleService} for the allowed values).
 * </ul>
 */
public record ClarificationLifecycleSnapshot(
    String publicId,
    String workflowRunId,
    String artifactId,
    int artifactVersion,
    String questionId,
    String questionText,
    String status,
    String answerText,
    String answeredByActor,
    ActorType answeredByActorType,
    OffsetDateTime answeredAt,
    OffsetDateTime acceptedAt,
    OffsetDateTime incorporatedAt,
    String incorporationEventPublicId,
    String incorporatedIntoArtifactPublicId,
    String supersededByArtifactPublicId,
    Integer supersededByArtifactVersion,
    String noEffectReason,
    OffsetDateTime createdAt) {

  public ClarificationLifecycleSnapshot {
    Objects.requireNonNull(publicId, "publicId");
    Objects.requireNonNull(workflowRunId, "workflowRunId");
    Objects.requireNonNull(artifactId, "artifactId");
    Objects.requireNonNull(questionId, "questionId");
    Objects.requireNonNull(questionText, "questionText");
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(createdAt, "createdAt");
    if (artifactVersion <= 0) {
      throw new IllegalArgumentException("artifactVersion must be positive: " + artifactVersion);
    }
    if (!Clarification.ALL_STATUSES.contains(status)) {
      throw new IllegalArgumentException(
          "status must be one of " + Clarification.ALL_STATUSES + ", was: " + status);
    }
  }
}
