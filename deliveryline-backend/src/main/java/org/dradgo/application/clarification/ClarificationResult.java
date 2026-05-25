package org.dradgo.application.clarification;

import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * Rich result type returned by {@link ClarificationService#submitAnswer} (story 2.11). Carries
 * everything the REST/CLI surface needs to render a "you answered question X of clarification Y on
 * version N of spec Z" success line — story 2.13 will eventually expose this directly through a new
 * REST DTO.
 *
 * <p>Intentionally NOT a member of the {@code DomainResult} sealed interface in {@code
 * org.dradgo.application.workflow} — widening it would force the {@code executeIdempotent} replay
 * loaders (typed to {@code DomainResult}) to understand {@code ClarificationResult} for zero gain.
 * The legacy {@code WorkflowStateChangeResult} contract is preserved by {@code
 * WorkflowCommandService.answerClarificationInternal}, which constructs a {@code
 * WorkflowStateChangeResult} from this record using the run's <em>current</em> state (answering a
 * clarification does NOT mutate the run state — story 2.12 owns that).
 *
 * @param clarificationId persisted clarification public id ({@code clr_…})
 * @param workflowRunId run public id ({@code run_…})
 * @param artifactId artifact public id the answer was bound to ({@code art_…})
 * @param artifactVersion exact spec version pinned by the composite FK
 * @param status clarification status after the write — typically {@link
 *     Clarification#STATUS_ANSWERED}, or {@link Clarification#STATUS_ACCEPTED} for the re-answer-
 *     in-accepted-state path (AC8)
 * @param answeredAt server-stamped answer timestamp
 * @param correlationId echoed from the originating command (nullable)
 */
public record ClarificationResult(
    String clarificationId,
    String workflowRunId,
    String artifactId,
    int artifactVersion,
    String status,
    OffsetDateTime answeredAt,
    String correlationId) {

  public ClarificationResult {
    Objects.requireNonNull(clarificationId, "clarificationId");
    Objects.requireNonNull(workflowRunId, "workflowRunId");
    Objects.requireNonNull(artifactId, "artifactId");
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(answeredAt, "answeredAt");
    if (artifactVersion <= 0) {
      throw new IllegalArgumentException("artifactVersion must be positive: " + artifactVersion);
    }
    if (!Clarification.ALL_STATUSES.contains(status)) {
      throw new IllegalArgumentException(
          "status must be one of " + Clarification.ALL_STATUSES + ", was: " + status);
    }
  }
}
