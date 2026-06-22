package org.dradgo.application.review;

import java.time.OffsetDateTime;
import java.util.Objects;
import org.dradgo.domain.registry.ReviewOutcome;

/**
 * Story 3d-2 (AC2/AC3/AC4) — application-layer projection over a row of the {@code step_reviews}
 * table (V19 migration, 3d-1). The durable advisory verdict a reviewer runner invocation produced
 * over a {@code WaitingForReview} output artifact: its {@link ReviewOutcome}, the (redacted)
 * rationale, and the model-provenance pair so "reviewed by a different LLM" is verifiable.
 *
 * <p>Read-only. The companion {@code StepReviewWritePort} (mirroring {@code ApprovalWritePort}) is
 * the only writer; the {@code WorkflowInspectionService.getReviewerVerdict} read leg surfaces it
 * advisory-only beside the Decision Bar (it never gates the human approve/reject decision, ADR 0026
 * D2). Only non-archived rows reach this projection ({@code archived_at IS NULL}; retention is Epic
 * 5).
 *
 * @param publicId step-review public id ({@code rev_…})
 * @param workflowRunId owning workflow run public id ({@code run_…})
 * @param runnerExecutionId the reviewer execution that produced this verdict ({@code rex_…}, FR53
 *     inspectable)
 * @param reviewedArtifactId the reviewed output artifact's public id ({@code art_…})
 * @param reviewedArtifactVersion exact version pinned by the composite FK into {@code artifacts}
 * @param outcome the advisory verdict ({@code pass}/{@code concern}/{@code fail})
 * @param rationale nullable, post-redaction reviewer rationale (AC7 — secret-free before persist)
 * @param reviewerModelIdentity the model that produced the review (kind + image tag); nullable
 * @param producerModelIdentity the model that produced the reviewed artifact; nullable
 * @param createdAt server-stamped persistence timestamp
 */
public record StepReviewSnapshot(
    String publicId,
    String workflowRunId,
    String runnerExecutionId,
    String reviewedArtifactId,
    int reviewedArtifactVersion,
    ReviewOutcome outcome,
    String rationale,
    String reviewerModelIdentity,
    String producerModelIdentity,
    OffsetDateTime createdAt) {

  public StepReviewSnapshot {
    Objects.requireNonNull(publicId, "publicId");
    Objects.requireNonNull(workflowRunId, "workflowRunId");
    Objects.requireNonNull(runnerExecutionId, "runnerExecutionId");
    Objects.requireNonNull(reviewedArtifactId, "reviewedArtifactId");
    Objects.requireNonNull(outcome, "outcome");
    Objects.requireNonNull(createdAt, "createdAt");
    if (reviewedArtifactVersion <= 0) {
      throw new IllegalArgumentException("reviewedArtifactVersion must be positive");
    }
  }

  /**
   * Story 3d-2 (AC4, DD-6) — a same-model self-review: the reviewer model equals the producer
   * model. Surfaced as a panel warning (the reviewer is NOT refused for self-review — ADR 0026 D5).
   * Derived from the two persisted identities so the data model carries no redundant column.
   *
   * <p>Comparison is normalized and resilient to image-tag drift (code-review hardening):
   * identities are {@code kind:imageTag} strings, but one side can legitimately fall back to a bare
   * {@code kind} when its tag is unresolvable (see {@code ReviewResultHarvester#identityFor}). A
   * naive full-string equality would then suppress the AC4 warning for what is genuinely the same
   * model. We therefore compare the normalized {@code kind} prefix (case-insensitive, trimmed) —
   * the model family is what "reviewed by a different LLM" turns on; differing tags of the same
   * kind are still a self-review. When either identity is null/blank the provenance is unknown and
   * self-review is NOT asserted (false), since a same-model claim cannot be substantiated without
   * both identities.
   */
  public boolean selfReview() {
    String reviewerKind = modelKind(reviewerModelIdentity);
    String producerKind = modelKind(producerModelIdentity);
    return reviewerKind != null && reviewerKind.equals(producerKind);
  }

  /**
   * The normalized model-kind prefix of a {@code kind:imageTag} (or bare {@code kind}) identity, or
   * {@code null} when the identity is absent/blank (unknown provenance). Case-insensitive + trimmed
   * so {@code "Claude:latest"} and {@code "claude"} both normalize to {@code "claude"}.
   */
  private static String modelKind(String identity) {
    if (identity == null || identity.isBlank()) {
      return null;
    }
    String trimmed = identity.trim();
    int separator = trimmed.indexOf(':');
    String kind = separator >= 0 ? trimmed.substring(0, separator) : trimmed;
    kind = kind.trim();
    return kind.isEmpty() ? null : kind.toLowerCase(java.util.Locale.ROOT);
  }
}
