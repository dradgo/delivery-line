package org.dradgo.application.workflow.spi;

import java.time.OffsetDateTime;
import java.util.List;
import org.dradgo.application.workflow.TicketBatchResult;
import org.dradgo.domain.registry.ActorType;

/**
 * Story 3.18 — write SPI over the {@code batch_submissions} table (V15 schema). Implemented under
 * {@code adapters.persistence}.
 *
 * <p>The adapter participates in its OWN transaction ({@code REQUIRED}) — distinct from the
 * per-ticket {@code WorkflowCommandService.submit} transactions — because {@code
 * WorkflowBatchSubmissionService.submitBatch} is deliberately NOT {@code @Transactional}
 * (best-effort semantics, Reconciliation 4 / Decision D-TX). The DB-level {@code
 * uq_batch_submissions_idempotency_key} UNIQUE constraint is mapped to {@code
 * IDEMPOTENCY_KEY_CONFLICT} as defense-in-depth behind the {@code IdempotencyService} reservation.
 */
public interface BatchSubmissionWritePort {

  /**
   * Insert one {@code batch_submissions} row (with the per-ticket outcomes serialized into {@code
   * result_json}) and return the server-stamped {@code created_at} so the caller can surface it as
   * the batch {@code submittedAt} (identical on replay).
   *
   * @throws org.dradgo.domain.DomainException with code {@code IDEMPOTENCY_KEY_CONFLICT} when the
   *     {@code uq_batch_submissions_idempotency_key} UNIQUE constraint fires.
   */
  OffsetDateTime insert(NewBatchSubmission newBatchSubmission);

  /**
   * Trace a queued execution back to its batch: stamp {@code runner_executions.batch_submission_id
   * = batchPublicId} for every row of the given workflow run. Best-effort and idempotent — affects
   * 0 rows when the run never dispatched a runner execution (e.g. spec-stage auto-dispatch disabled
   * in the test profile). Never throws on a 0-row update. Serves story 3.19's per-batch queue
   * filtering.
   *
   * @return the number of {@code runner_executions} rows stamped
   */
  int stampBatchSubmissionId(String workflowRunPublicId, String batchPublicId);

  /**
   * Caller-built insert payload. {@code publicId} is supplied externally (caller-generated via
   * {@link org.dradgo.domain.id.PublicIdPrefixes#BATCH_SUBMISSION}) so it can be stamped onto the
   * per-ticket runner executions before the batch row is persisted, and so log lines stay
   * deterministic.
   */
  record NewBatchSubmission(
      String publicId,
      String idempotencyKey,
      String actorIdentity,
      ActorType actorType,
      int total,
      int queuedCount,
      int rejectedCount,
      List<TicketBatchResult> tickets) {}
}
