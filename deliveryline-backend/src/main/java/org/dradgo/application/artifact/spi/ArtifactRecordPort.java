package org.dradgo.application.artifact.spi;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.dradgo.application.artifact.ActorContext;
import org.dradgo.application.artifact.ArtifactChecksum;
import org.dradgo.application.artifact.ArtifactDraftRequest;
import org.dradgo.application.artifact.ArtifactRecordSnapshot;
import org.dradgo.application.artifact.ArtifactVersionRequest;
import org.dradgo.domain.registry.FailureCategory;

public interface ArtifactRecordPort {

  Optional<ArtifactRecordSnapshot> findByPublicId(String artifactId);

  Optional<ArtifactRecordSnapshot> findLatestByWorkflowRunIdAndArtifactType(
      String workflowRunId, String artifactType);

  Optional<ArtifactRecordSnapshot> findLatestByParentArtifactId(String lineageMemberArtifactId);

  /**
   * All non-archived artifact rows for the given workflow run + artifact type, in ascending version
   * order. Used by story 2.8: spec-history (FR11) and spec-investigation context-bundle composition
   * (artifactReferences walk).
   */
  List<ArtifactRecordSnapshot> listByWorkflowRunIdAndArtifactType(
      String workflowRunId, String artifactType);

  /**
   * Resolve the runner-execution public id (if any) associated with the artifact's creation event.
   * Source: {@code workflow_events.details->>'runnerExecutionId'} on the artifact's {@code
   * linked_event_id}.
   *
   * <p>Returns {@link Optional#empty()} when the artifact does not exist, has no linked event, or
   * the linked event was emitted without a {@code runnerExecutionId} detail (legacy / manual
   * paths). Story 2.8 AC7: used by {@code WorkflowInspectionService.getContextBundleForArtifact} to
   * locate the scratch file holding the redacted bundle bytes.
   */
  Optional<String> findRunnerExecutionIdForArtifact(String artifactId);

  /**
   * Story 4.19 (AC2 / Reconciliation 7) — resolve the actor identity that produced the artifact,
   * read from the artifact's creation event ({@code workflow_events.actor_identity} on the
   * artifact's {@code linked_event_id}). Single-statement native query — no entity hydration.
   *
   * <p>Returns {@link Optional#empty()} when the artifact does not exist, has no linked event, or
   * the linked event carried no actor identity. Populates {@code ArtifactSummary.producedByActor}
   * for the Compare Mode delta; kept behind {@code ArtifactService} so {@code RevisionDeltaService}
   * never touches this port directly.
   */
  Optional<String> findProducingActorForArtifact(String artifactId);

  /**
   * Creates the first artifact record (version 1, status PENDING) for a new lineage within the
   * given workflow run and artifact type.
   *
   * <p>Acquires a pessimistic write lock on the workflow run row before inserting so that
   * concurrent drafts for the same {@code (workflow_run_id, artifact_type)} are serialized and the
   * monotonic version counter remains correct. The lock is released when the enclosing transaction
   * commits or rolls back.
   *
   * @throws org.dradgo.domain.DomainException with {@code WORKFLOW_RUN_TERMINAL} if the run is
   *     already in a terminal state at the time the lock is acquired.
   * @throws org.dradgo.domain.DomainException with {@code ARTIFACT_OPERATION_CONFLICT} if a
   *     concurrent insert collides on the version uniqueness constraint.
   */
  ArtifactRecordSnapshot createDraft(ArtifactDraftRequest request);

  /**
   * Acquires a PostgreSQL advisory transaction lock scoped to {@code (workflowRunId,
   * artifactType)}.
   *
   * <p>Uses {@code pg_advisory_xact_lock} so the lock is automatically released when the current
   * transaction ends. The lock key is derived deterministically from the two arguments so that any
   * caller holding a transaction that targets the same lineage blocks until the prior writer
   * commits or rolls back, preventing interleaved PENDING artifact rows in the same family.
   *
   * <p>Must be called inside an active transaction. Intended to be invoked from {@link
   * org.dradgo.application.artifact.ArtifactOperationService} immediately after the workflow-run
   * liveness check and before any artifact row is read or written.
   */
  void lockLineageForUpdate(String workflowRunId, String artifactType);

  /**
   * Continues the current lineage leaf for the artifact family that contains the supplied member
   * id.
   *
   * <p>The caller may pass the root, the current leaf, or any superseded lineage member; the
   * adapter resolves the actual leaf from {@code (workflow_run_id, artifact_type)} and chains the
   * new version off that leaf. A stale (non-leaf) argument is silently followed to the leaf — this
   * is contract, not a bug. If the caller cares about the precise predecessor, query {@link
   * #findLatestByParentArtifactId(String)} first and reuse that id.
   */
  ArtifactRecordSnapshot createNextVersion(ArtifactVersionRequest request);

  ArtifactRecordSnapshot markAvailable(
      String artifactId, ArtifactChecksum checksum, String storageRef);

  ArtifactRecordSnapshot markFailed(
      String artifactId, FailureCategory failureCategory, String reason);

  ArtifactRecordSnapshot markLateOrStale(String artifactId);

  /**
   * Story 4.16 (AC2 / Reconciliation 8) — operator repair for a confirmed checksum-mismatch drift
   * on an {@code available} artifact: {@code AVAILABLE -> CORRUPTED}. The guard permits ONLY {@code
   * AVAILABLE} as the source (checksum-mismatch drift is detected on an available artifact); any
   * other current status raises {@code ARTIFACT_INVALID_STATE_TRANSITION}. {@code reason} is the
   * operator note recorded on {@code failure_reason}. This is the only genuinely-new artifact
   * status transition in story 4.16.
   */
  ArtifactRecordSnapshot markCorrupted(String artifactId, String reason);

  /**
   * Story 4.16 (AC2 / Reconciliation 8, OQ-1) — operator repair for a confirmed missing-payload
   * drift on an {@code available} artifact: {@code AVAILABLE -> FAILED} with a distinct failure
   * reason token. A dedicated adapter path (NOT {@link #markFailed} — its guard rejects {@code
   * AVAILABLE}); the guard permits ONLY {@code AVAILABLE} as the source, else {@code
   * ARTIFACT_INVALID_STATE_TRANSITION}. Reuses the {@code FAILED} status (no dedicated
   * "unavailable" status exists — {@code ARTIFACT_PAYLOAD_UNAVAILABLE} is a DomainErrorCode, not a
   * status); {@code reason} is recorded on {@code failure_reason}.
   */
  ArtifactRecordSnapshot markPayloadUnavailable(String artifactId, String reason);

  /**
   * Story 4.16a (AC3 / Reconciliation 4) — operator lineage recovery: re-parent an
   * orphaned/ambiguous artifact onto an operator-chosen lineage. {@code UPDATE artifacts SET
   * parent_artifact_id = <the chosen parent's internal id> WHERE public_id = <orphan>}. The orphan
   * keeps its existing (already unique) {@code (workflow_run_id, artifact_type, version)}, so
   * re-parenting alone attaches it as a proper child and PRESERVES the superseded/failed lineage
   * history unchanged (no version churn, no status change).
   *
   * <p>Guards: the chosen parent MUST belong to the same {@code (workflow_run_id, artifact_type)}
   * as the orphan, else {@code ARTIFACT_LINEAGE_MISMATCH} (400, reuse of story 4.19's code); and
   * the chosen parent MUST NOT be the orphan itself nor a descendant of the orphan (walking {@code
   * parent_artifact_id} up from the chosen parent must never reach the orphan), else {@code
   * ARTIFACT_INVALID_STATE_TRANSITION} (409 — a re-parent that would introduce a cycle). Either
   * artifact absent raises {@code ARTIFACT_RECORD_NOT_FOUND} (404). Serializes against concurrent
   * lineage writers via the same run-row lock {@code createNextVersion} uses.
   *
   * @return the re-parented orphan snapshot (its {@code parentArtifactId} now the chosen parent)
   */
  ArtifactRecordSnapshot reattachToLineage(String orphanArtifactId, String chosenParentArtifactId);

  /**
   * Story 4.16a (AC4 / Reconciliation 4) — operator lineage recovery: mark an ambiguous artifact's
   * lineage terminal so {@code hasActiveLineage} returns false and replay cannot silently revive
   * it. Reuses the {@code FAILED} status (a terminal, {@code hasActiveLineage}-excluding status —
   * story 4.16a OQ-2; no dedicated "terminated"/"abandoned" status is introduced). Because the
   * ambiguous head may be {@code available} / {@code pending} / {@code late_or_stale} / {@code
   * corrupted}, this dedicated guard permits a BROADER source set than {@link #markFailed} (which
   * permits only {@code pending}/{@code late_or_stale}); the only rejected current status is {@code
   * failed} (already terminal), else {@code ARTIFACT_INVALID_STATE_TRANSITION} (409). {@code
   * reason} is recorded on {@code failure_reason} (paired with {@code failure_category = orphan} —
   * the {@code ck_artifacts_failure_reason_paired} CHECK). Absent artifact raises {@code
   * ARTIFACT_RECORD_NOT_FOUND} (404). Closing any dangling pending operation is the caller's job
   * (via {@code ArtifactOperationPort.markFailedOrphan}).
   *
   * @return the terminated artifact snapshot ({@code status = failed})
   */
  ArtifactRecordSnapshot markLineageTerminated(String artifactId, String reason);

  /**
   * Story 4.16a (AC5 / Reconciliation 3/4) — operator lineage recovery: create a fresh, disjoint
   * lineage branch ("explicit fork") rooted under the SAME {@code (workflow_run_id, artifact_type)}
   * as the supplied source lineage member. Inserts a new head with {@code version = max(version)+1}
   * for that {@code (run, type)} (NOT version 1 — that is how two disjoint lineages coexist without
   * a {@code uq_artifacts_workflow_run_id_artifact_type_version} collision), {@code
   * parent_artifact_id = NULL}, status {@code PENDING}, and — critically — the DORMANT V5 {@code
   * lineage_recovery} discriminator set to {@code true} (the first and only writer of that column).
   * Emits an {@code artifact.draftCreated} linked event for the new head (every artifact row
   * requires a {@code linked_event_id}); the recovery rationale + source reference ride the
   * separate {@code artifact.lineageReconciled} audit event the service appends. Serializes on the
   * run row (mirrors {@code createNextVersion}); a terminal run raises {@code
   * WORKFLOW_RUN_TERMINAL} (409). Absent source raises {@code ARTIFACT_RECORD_NOT_FOUND} (404).
   *
   * @param sourceLineageMemberArtifactId any member of the source lineage (resolves run/type/class)
   * @param actor the resolved operator actor context stamped on the fork's creation event
   * @param reason the operator note recorded on the fork's creation event
   * @return the new fork head snapshot ({@code version = max+1}, {@code parentArtifactId = null},
   *     {@code lineageRecovery = true}, {@code status = pending})
   */
  ArtifactRecordSnapshot createLineageRecoveryFork(
      String sourceLineageMemberArtifactId, ActorContext actor, String reason);

  /**
   * Story 4.15 (AC1) — bounded, KEYSET-PAGED scan seam for the artifact-drift-detection sweep.
   * Returns up to {@code batchLimit} {@code available}-status artifacts whose {@code created_at} is
   * older than {@code now() - minAge} AND that sort strictly after the {@code (afterCreatedAt,
   * afterPublicId)} cursor, ordered oldest-first by {@code (created_at, public_id)}. Pass {@code
   * (null, null)} to start from the oldest.
   *
   * <p>The cursor exists because detection is status-neutral (story 4.15 review D1): a clean {@code
   * available} artifact never leaves the {@code available} set, so an uncursored oldest-N scan
   * would re-read the same oldest {@code batchLimit} rows every tick and never reach drift on newer
   * artifacts. The caller advances the cursor to the last returned row each tick and resets it to
   * {@code (null, null)} on a partial batch (tail reached) so the next cycle rescans from the
   * oldest.
   *
   * <p>The staleness comparison runs DB-side (both sides on the database clock) like {@link
   * ArtifactOperationPort#findPendingOlderThan(Duration)}, so JVM/DB clock skew never masks or
   * hallucinates a candidate. Backed by the V45 partial index {@code
   * idx_artifacts_status_created_at WHERE status='available'}.
   */
  List<ArtifactRecordSnapshot> findAvailableCreatedBefore(
      Duration minAge, int batchLimit, OffsetDateTime afterCreatedAt, String afterPublicId);
}
