package org.dradgo.application.artifact.spi;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.dradgo.application.artifact.ArtifactOperationSnapshot;
import org.dradgo.domain.registry.ArtifactType;
import org.dradgo.domain.registry.FailureCategory;

public interface ArtifactOperationPort {

  Optional<ArtifactOperationSnapshot> findReplay(
      String workflowRunId, ArtifactType artifactType, String idempotencyKey, String operationType);

  /**
   * Story 3e-2 (review P1) — the operation already recorded for this idempotency key within the run
   * + artifactType, independent of {@code operation_type}, if any. The broker uses this to REUSE
   * the type a prior harvest chose rather than recomputing the SPEC graft decision (CREATE vs
   * UPDATE) from mutable lineage state — a recompute that flips {@code CREATE -> UPDATE} on a
   * re-harvest of the same runner result and, because the idempotency replay key includes {@code
   * operation_type}, misses the stored row and mints a spurious extra spec version.
   */
  Optional<ArtifactOperationSnapshot> findRecordedOperation(
      String workflowRunId, ArtifactType artifactType, String idempotencyKey);

  /**
   * Returns the single PENDING operation for the given artifact, if one exists.
   *
   * <p><strong>Invariant:</strong> at most one PENDING operation may exist per artifact at any
   * time. This is enforced by {@code uq_artifact_operations_pending_per_artifact} (V4 partial
   * unique index). If the index is absent or bypassed and multiple PENDING rows are found, the
   * adapter must surface an {@code INTERNAL_ERROR} rather than silently tiebreak by timestamp.
   */
  Optional<ArtifactOperationSnapshot> findPendingByArtifactId(String artifactId);

  ArtifactOperationSnapshot createPending(
      String operationPublicId,
      String workflowRunId,
      ArtifactType artifactType,
      String artifactId,
      String operationType,
      String idempotencyKey);

  ArtifactOperationSnapshot markComplete(String operationPublicId);

  ArtifactOperationSnapshot markFailed(
      String operationPublicId, FailureCategory failureCategory, String reason);

  /**
   * Finds pending operations whose {@code created_at} is older than {@code now - threshold}, with
   * both sides of the comparison evaluated in database time.
   *
   * <p>Prefer this over {@link #findPendingCreatedBefore(OffsetDateTime)} for reconciliation paths:
   * the JVM clock and the database clock can drift apart, and a JVM-derived threshold combined with
   * DB-side {@code created_at} masks or hallucinates orphans during skew. This method keeps both
   * sides on the same clock so the staleness window is honest.
   */
  List<ArtifactOperationSnapshot> findPendingOlderThan(Duration threshold);

  ArtifactOperationSnapshot markFailedOrphan(String operationPublicId, String reason);

  /**
   * Returns {@code true} if any {@code artifact_operations} row exists for the given workflow run
   * with {@code status} in {@code (failed, failed_orphan)}. Used by {@link
   * org.dradgo.application.recovery.RecoveryService#describeFailure(String)} for AC8 — when an
   * artifact-operation failure exists alongside a workflow Failed state, the safe next action is
   * {@code await_manual_reconciliation}, not blind {@code retry}.
   */
  boolean hasFailedOrFailedOrphanForRun(String workflowRunPublicId);
}
