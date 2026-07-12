package org.dradgo.application.approval.spi;

import java.time.Instant;
import java.time.OffsetDateTime;
import org.dradgo.application.approval.ApprovalSnapshot;
import org.dradgo.domain.registry.ActorType;

/**
 * Write SPI over the {@code approvals} table (V1 schema). Sibling of {@link ApprovalReadPort} —
 * stories 2.9 (approve) and 2.10 (reject) feed the same row shape; the read port re-surfaces them
 * via {@link ApprovalSnapshot}.
 *
 * <p>Adapter implementations participate in the caller's transaction (REQUIRED propagation) so the
 * row insert, the {@code approval.approved} event append (story 2.9) and the {@link
 * org.dradgo.application.workflow.WorkflowTransitionService#transition} call all commit or roll
 * back together. The DB-level {@code uq_approvals_idempotency_key} UNIQUE constraint is a
 * defense-in-depth backstop — see {@link #insert} contract.
 */
public interface ApprovalWritePort {

  /**
   * Insert a single approval row, returning the persisted projection (as seen by the read port).
   *
   * @throws org.dradgo.domain.DomainException with code {@code IDEMPOTENCY_KEY_CONFLICT} when the
   *     DB-level {@code uq_approvals_idempotency_key} UNIQUE constraint fires (defense-in-depth —
   *     the {@code IdempotencyService} reservation path should have rejected first). The
   *     persistence exception is mapped without leaking implementation details.
   */
  ApprovalSnapshot insert(NewApproval newApproval);

  /**
   * Story 4.7 (AC7 / Reconciliation 2) — invalidate a currently-valid approval row (rerun-from-step
   * supersession). Sets {@code invalidated_at}/{@code invalidated_reason} only when the row is
   * still valid ({@code invalidated_at IS NULL}), so a second rerun on the same run is idempotent
   * at the DB level. Participates in the caller's transaction (REQUIRED — joins the RecoveryService
   * prep tx).
   *
   * @param approvalPublicId {@code apr_…} of the approval to invalidate
   * @param invalidatedReason controlled reason token (e.g. {@code superseded_by_rerun_from_step})
   * @param invalidatedAt server-stamped invalidation instant
   * @return {@code true} when a row was flipped (rows-affected == 1); {@code false} when the row
   *     was already invalidated or not found
   */
  boolean markInvalidated(String approvalPublicId, String invalidatedReason, Instant invalidatedAt);

  /**
   * Story 4.7 [Review D1] — restore (un-invalidate) an approval row, compensating a rerun-from-step
   * whose post-commit runner re-enqueue failed after the prep tx already invalidated the prior
   * approval. Clears {@code invalidated_at}/{@code invalidated_reason} only when the row is
   * currently invalidated ({@code invalidated_at IS NOT NULL}), so the compensation is idempotent
   * and never disturbs a still-valid approval. Participates in the caller's transaction (REQUIRED).
   *
   * @param approvalPublicId {@code apr_…} of the approval to restore
   * @return {@code true} when a row was restored (rows-affected == 1); {@code false} when the row
   *     was already valid or not found
   */
  boolean clearInvalidation(String approvalPublicId);

  /**
   * Caller-built insert payload. {@code publicId} is supplied externally (caller-generated via
   * {@link org.dradgo.domain.id.PublicIdPrefixes#APPROVAL}) so log lines remain deterministic
   * before the row exists.
   *
   * @param publicId {@code apr_…}
   * @param workflowRunPublicId {@code run_…}
   * @param artifactPublicId {@code art_…}
   * @param artifactVersion exact version pinned by the composite FK
   * @param contextBundleVersion version the reviewer reviewed against
   * @param actorIdentity reviewer identity (free-form string, max 128)
   * @param actorType reviewer actor type
   * @param reviewerRole asserted reviewer role (NotBlank, max 128)
   * @param decision {@code "approved"} or {@code "rejected"}; story 2.9 only ever supplies {@code
   *     "approved"} — story 2.10 will introduce the rejection writer
   * @param reason nullable reviewer-supplied free-text
   * @param rejectionTaxonomy MUST be null when {@code decision="approved"} (DB CHECK {@code
   *     ck_approvals_decision_taxonomy_paired})
   * @param decidedAt server-stamped decision timestamp
   * @param idempotencyKey carried through to the DB column for the defense-in-depth UNIQUE
   */
  record NewApproval(
      String publicId,
      String workflowRunPublicId,
      String artifactPublicId,
      int artifactVersion,
      int contextBundleVersion,
      String actorIdentity,
      ActorType actorType,
      String reviewerRole,
      String decision,
      String reason,
      String rejectionTaxonomy,
      OffsetDateTime decidedAt,
      String idempotencyKey) {}
}
