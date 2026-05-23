package org.dradgo.application.approval;

import java.time.OffsetDateTime;
import java.util.Objects;
import org.dradgo.domain.registry.ActorType;

/**
 * Application-layer projection over a row of the {@code approvals} table (V1 migration). Used by
 * spec inspection ({@code WorkflowInspectionService.getCurrentApprovedSpec / getSpecHistory}) and
 * by spec-investigation context-bundle composition (prior-feedback source).
 *
 * <p>Read-only. The companion {@code ApprovalReadPort} surfaces only non-archived rows ({@code
 * archived_at IS NULL}); tombstoned approvals never reach this projection. Story 2.8 intentionally
 * ships <strong>no</strong> write surface; the writer arrives with {@code ApprovalService} (story
 * 2.9).
 *
 * @param publicId approval public id ({@code apr_…})
 * @param workflowRunId owning workflow run public id ({@code run_…})
 * @param artifactId approved/rejected artifact's public id ({@code art_…})
 * @param artifactVersion exact version pinned by the composite FK
 * @param contextBundleVersion bundle version the reviewer reviewed against
 * @param actorIdentity reviewer identity
 * @param actorType reviewer actor type
 * @param reviewerRole free-text reviewer role label (e.g. {@code product_owner})
 * @param decision {@code "approved"} or {@code "rejected"} (DB CHECK constraint enforces the union;
 *     surfaced as a String here so callers can switch on the value without an extra registry value
 *     type)
 * @param reason nullable reviewer-supplied reason text
 * @param rejectionTaxonomy non-null only when {@code decision == "rejected"}; one of {@code
 *     missing_scope}, {@code unclear_specification}, {@code misunderstood_implementation}
 * @param decidedAt the reviewer's decision timestamp (chronological ordering field)
 */
public record ApprovalSnapshot(
    String publicId,
    String workflowRunId,
    String artifactId,
    int artifactVersion,
    int contextBundleVersion,
    String actorIdentity,
    ActorType actorType,
    String reviewerRole,
    String decision,
    String reason,
    String rejectionTaxonomy,
    OffsetDateTime decidedAt) {

  public static final String DECISION_APPROVED = "approved";
  public static final String DECISION_REJECTED = "rejected";

  public ApprovalSnapshot {
    Objects.requireNonNull(publicId, "publicId");
    Objects.requireNonNull(workflowRunId, "workflowRunId");
    Objects.requireNonNull(artifactId, "artifactId");
    Objects.requireNonNull(actorIdentity, "actorIdentity");
    Objects.requireNonNull(actorType, "actorType");
    Objects.requireNonNull(reviewerRole, "reviewerRole");
    Objects.requireNonNull(decision, "decision");
    Objects.requireNonNull(decidedAt, "decidedAt");
    if (artifactVersion <= 0) {
      throw new IllegalArgumentException("artifactVersion must be positive");
    }
    if (contextBundleVersion <= 0) {
      throw new IllegalArgumentException("contextBundleVersion must be positive");
    }
    if (!DECISION_APPROVED.equals(decision) && !DECISION_REJECTED.equals(decision)) {
      throw new IllegalArgumentException(
          "decision must be one of {approved, rejected}, was: " + decision);
    }
    if (DECISION_APPROVED.equals(decision) && rejectionTaxonomy != null) {
      throw new IllegalArgumentException(
          "approved decisions must not carry a rejectionTaxonomy (DB CHECK invariant)");
    }
    if (DECISION_REJECTED.equals(decision) && rejectionTaxonomy == null) {
      throw new IllegalArgumentException(
          "rejected decisions must carry a non-null rejectionTaxonomy (DB CHECK invariant)");
    }
  }

  public boolean isApproved() {
    return DECISION_APPROVED.equals(decision);
  }

  public boolean isRejected() {
    return DECISION_REJECTED.equals(decision);
  }
}
