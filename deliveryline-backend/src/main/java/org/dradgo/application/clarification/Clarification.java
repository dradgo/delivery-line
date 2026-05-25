package org.dradgo.application.clarification;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.Set;
import org.dradgo.domain.registry.ActorType;

/**
 * Application-layer projection over a row of the {@code clarifications} table (V8 migration, story
 * 2.11). Mirrors {@link org.dradgo.application.approval.ApprovalSnapshot} — read-only, surfaced by
 * {@link org.dradgo.application.clarification.spi.ClarificationReadPort} which filters {@code
 * archived_at IS NULL} so tombstoned rows never reach this projection.
 *
 * <p>The compact constructor mirrors the DB CHECK invariants ({@code ck_clarifications_status} and
 * {@code ck_clarifications_answered_fields_paired}) as in-record assertions: any caller that builds
 * a {@code Clarification} with a malformed status, or with the answer fields not paired against the
 * status, fails fast (trap T9 defense-in-depth).
 *
 * @param publicId clarification public id ({@code clr_…})
 * @param workflowRunId owning workflow run public id ({@code run_…})
 * @param artifactId artifact ({@code art_…}) the question is asked against
 * @param artifactVersion exact spec version pinned by the composite FK
 * @param questionId question identifier (max 128 chars, format-checked in the DDL)
 * @param questionText snapshot copy of the question (preserved even if the source spec is
 *     superseded — FR10)
 * @param status one of {@link #ALL_STATUSES} (surfaced as a String for the same reason {@code
 *     ApprovalSnapshot.decision} is a String — caller-friendly switch without a registry-value
 *     type)
 * @param answerText reviewer answer (nullable while {@code status == open})
 * @param answeredByActor reviewer identity (nullable while {@code status == open})
 * @param answeredByActorType reviewer actor type (nullable while {@code status == open})
 * @param answeredAt server-stamped answer timestamp (nullable while {@code status == open})
 * @param createdAt row creation timestamp
 */
public record Clarification(
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
    OffsetDateTime createdAt) {

  public static final String STATUS_OPEN = "open";
  public static final String STATUS_ANSWERED = "answered";
  public static final String STATUS_ACCEPTED = "accepted";
  public static final String STATUS_INCORPORATED = "incorporated";
  public static final String STATUS_SUPERSEDED = "superseded";
  public static final String STATUS_REJECTED_INVALID = "rejected_invalid";

  public static final Set<String> ALL_STATUSES =
      Set.of(
          STATUS_OPEN,
          STATUS_ANSWERED,
          STATUS_ACCEPTED,
          STATUS_INCORPORATED,
          STATUS_SUPERSEDED,
          STATUS_REJECTED_INVALID);

  public Clarification {
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
    if (!ALL_STATUSES.contains(status)) {
      throw new IllegalArgumentException(
          "status must be one of " + ALL_STATUSES + ", was: " + status);
    }
    // Trap T9 / AC1 paired-fields invariant — mirror of ck_clarifications_answered_fields_paired:
    // (status = 'open') ⇔ (answerText is null AND answeredByActor is null AND answeredAt is null).
    boolean isOpen = STATUS_OPEN.equals(status);
    boolean fieldsAbsent = answerText == null && answeredByActor == null && answeredAt == null;
    if (isOpen != fieldsAbsent) {
      throw new IllegalArgumentException(
          "Clarification answer-fields paired invariant violated: status="
              + status
              + ", answerText="
              + (answerText == null ? "null" : "present")
              + ", answeredByActor="
              + (answeredByActor == null ? "null" : "present")
              + ", answeredAt="
              + (answeredAt == null ? "null" : "present"));
    }
  }

  public boolean isOpen() {
    return STATUS_OPEN.equals(status);
  }

  public boolean isAnswered() {
    return STATUS_ANSWERED.equals(status);
  }

  public boolean isTerminal() {
    return STATUS_INCORPORATED.equals(status)
        || STATUS_SUPERSEDED.equals(status)
        || STATUS_REJECTED_INVALID.equals(status);
  }
}
