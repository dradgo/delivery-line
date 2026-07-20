package org.dradgo.application.runner.spi;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.dradgo.application.runner.ExecutionConstraints;
import org.dradgo.domain.registry.DataClassification;
import org.dradgo.domain.registry.FailureCategory;
import org.dradgo.domain.registry.RunnerExecutionStatus;
import org.dradgo.domain.registry.RunnerStage;

/**
 * Application-owned persistence boundary for {@code runner_executions} rows. Implementations live
 * under {@code adapters.persistence}. State-transition rules (pending → running →
 * completed|failed|timed_out|orphaned) are enforced inside the adapter, mirroring the
 * artifact-state-machine guard pattern (1.12 patches C4 / R4).
 */
public interface RunnerExecutionRecordPort {

  Optional<RunnerExecutionSnapshot> findByPublicId(String publicId);

  List<RunnerExecutionSnapshot> findByWorkflowRunPublicIdAndStatusIn(
      String workflowRunPublicId, List<RunnerExecutionStatus> statuses);

  /**
   * Story 3d-2 (Task 7) — the latest execution for the run at {@code stage} (highest context-bundle
   * version). Backs the reviewer-verdict read leg's state derivation: the latest {@link
   * RunnerStage#REVIEW} execution's status decides {@code pending}/{@code unavailable} when no
   * verdict row exists yet.
   */
  Optional<RunnerExecutionSnapshot> findLatestByWorkflowRunPublicIdAndStage(
      String workflowRunPublicId, RunnerStage stage);

  /**
   * Find pending/running rows whose {@code timeout_at < now()} server-side (no JVM clock). Bounded
   * by {@code limit}.
   */
  List<RunnerExecutionSnapshot> findStaleByStatusInAndTimeoutAtBefore(
      List<RunnerExecutionStatus> statuses, Duration scanWindow, int limit);

  /** Find pending/running rows for the broker recover-on-startup scan. Bounded by {@code limit}. */
  List<RunnerExecutionSnapshot> findActiveStatuses(List<RunnerExecutionStatus> statuses, int limit);

  /**
   * Compute the next monotonic {@code context_bundle_version} for the given (workflowRunId, stage)
   * pair: {@code MAX(context_bundle_version) + 1}, or {@code 1} if no row exists.
   */
  int nextContextBundleVersion(String workflowRunPublicId, RunnerStage stage);

  /**
   * Insert a new {@code pending} row. Must be called inside an active transaction; the row is
   * inserted with {@code status = pending}, {@code last_activity_at = now()}, {@code timeout_at =
   * now() + executionConstraints.timeout}, and the supplied bundle version.
   */
  RunnerExecutionSnapshot insertPending(
      String publicId,
      String workflowRunPublicId,
      RunnerStage stage,
      int contextBundleVersion,
      ExecutionConstraints executionConstraints);

  /**
   * Story 3.17a (AC2) — insert a new {@code queued} row for the RunnerExecutionQueue substrate.
   * Must be called inside an active transaction; the row is inserted with {@code status = queued},
   * {@code last_activity_at = now()}, {@code timeout_at = now() + timeout}, {@code
   * queue_attempt_count = 0}, no lease ({@code worker_id}/{@code dispatched_at} null), the supplied
   * {@code queuePriority} + {@code correlationId}, and the supplied bundle version. Built dormant:
   * no production code calls this in 3.17a (the worker pool + caller refactor land in 3.17b).
   */
  RunnerExecutionSnapshot insertQueued(
      String publicId,
      String workflowRunPublicId,
      RunnerStage stage,
      int contextBundleVersion,
      Duration timeout,
      int queuePriority,
      String correlationId,
      // Story 3.17b (V14 carriage) — the idempotency key + originating actor the relocated dispatch
      // body needs at worker-dispatch time. Nullable: the legacy synchronous dispatch path never
      // enqueues, and 3.17a's tests insert without carriage.
      String idempotencyKey,
      String actorIdentity,
      String actorType);

  /**
   * Story 3d-3 (AC5) — insert a new {@code awaiting_manual} row for a step dispatched under the
   * {@code manual} runner kind (parked, not enqueued). Must be called inside an active transaction;
   * the row is inserted with {@code status = awaiting_manual}, {@code last_activity_at = now()},
   * the supplied bundle version, and NO container/lease/worker/queue columns. {@code completed_at}
   * stays null (the status is NON-TERMINAL — it must stay out of {@code
   * ck_runner_executions_completed_correlation}). {@code timeout_at} is set to {@code
   * last_activity_at} (a parked run has no real timeout — ADR 0024; the sentinel only satisfies
   * {@code ck_runner_executions_timeout_after_activity}). Returns the inserted {@link
   * RunnerExecutionSnapshot}. On 3d-4 submission the row finalizes to {@code completed}.
   */
  RunnerExecutionSnapshot insertAwaitingManual(
      String publicId, String workflowRunPublicId, RunnerStage stage, int contextBundleVersion);

  default RunnerExecutionSnapshot insertAwaitingManual(
      String publicId,
      String workflowRunPublicId,
      RunnerStage stage,
      int contextBundleVersion,
      String idempotencyKey) {
    return insertAwaitingManual(publicId, workflowRunPublicId, stage, contextBundleVersion);
  }

  /** Allocates the context-bundle version while holding the workflow-run lock, then inserts. */
  default RunnerExecutionSnapshot insertAwaitingManualAllocated(
      String publicId, String workflowRunPublicId, RunnerStage stage, String idempotencyKey) {
    return insertAwaitingManual(
        publicId,
        workflowRunPublicId,
        stage,
        nextContextBundleVersion(workflowRunPublicId, stage),
        idempotencyKey);
  }

  /**
   * Story 3.17a (AC4) — count the rows currently in {@code status = queued}. Backs the
   * RunnerExecutionQueue backpressure cap; called under the enqueue write transaction so the count
   * reflects rows committed (or inserted-and-pending) at decision time.
   */
  long countQueued();

  /**
   * Story 3.19 (AC1/AC3/AC9) — read the scalar queue aggregate in ONE native SELECT so every count
   * reflects a single snapshot. {@code staleWindow} is the lease window ({@code
   * staleThresholdMultiplier × stageTimeout}); {@code throughputWindow} is the recent-completion
   * window for {@code recentThroughput} (60s). When {@code batchIdOrNull} is non-null every count
   * is scoped to {@code batch_submission_id = :batchId} (the 3.18 FK); otherwise it is global.
   * Stale cutoffs + the oldest-queued age are computed server-side (Postgres {@code now()}), no JVM
   * clock.
   */
  RunnerQueueCounts loadQueueCounts(
      Duration staleWindow, Duration throughputWindow, String batchIdOrNull);

  /**
   * Story 3.19 (AC1) — the leased running rows that back the per-worker {@code WorkerStatus} list:
   * {@code status='running' AND worker_id IS NOT NULL}, ordered oldest-lease-first. Each row
   * carries {@code worker_id}, {@code dispatched_at}, {@code stage} and the run/execution public
   * ids. When {@code batchIdOrNull} is non-null the rows are scoped to that batch. Bounded by
   * {@code limit} (leased running rows are bounded by the worker-pool size at any instant). The
   * pool keeps no in-memory roster — these DB rows are the authoritative busy-worker state
   * (Reconciliation 6).
   */
  List<RunnerExecutionSnapshot> findLeasedRunning(String batchIdOrNull, int limit);

  /**
   * Story 3.17a (AC2) — lease the next queued row to {@code workerId} using {@code FOR UPDATE SKIP
   * LOCKED} so concurrent workers never pick the same row. Atomically flips the highest-priority
   * (lowest {@code queue_priority}, then oldest {@code created_at}) queued row to {@code running},
   * stamps {@code worker_id}/{@code dispatched_at = now()}, and increments {@code
   * queue_attempt_count}. Returns the leased row, or empty when the queue holds no queued rows.
   * Must run inside an active transaction. Built dormant: exercised by tests only in 3.17a.
   */
  Optional<RunnerExecutionSnapshot> dequeueNext(String workerId);

  RunnerExecutionSnapshot transitionToRunning(String publicId, OffsetDateTime lastActivityAt);

  RunnerExecutionSnapshot touchActivity(
      String publicId, OffsetDateTime lastActivityAt, Duration staleTimeoutWindow);

  RunnerExecutionSnapshot markCompleted(String publicId, OffsetDateTime completedAt);

  RunnerExecutionSnapshot markFailed(
      String publicId, FailureCategory failureCategory, OffsetDateTime completedAt);

  RunnerExecutionSnapshot markTimedOut(String publicId, OffsetDateTime completedAt);

  RunnerExecutionSnapshot markOrphaned(String publicId, OffsetDateTime completedAt);

  /**
   * Story 3.22 (AC5) — developer takeover: flip a {@code {queued, pending, running}} row to the
   * terminal {@code cancelled_for_takeover} status (state-machine-guarded), stamping {@code
   * completed_at = cancelledAt}. This is the authoritative "stop dispatch" signal — the row becomes
   * invisible to the worker pool's {@code dequeueNext} (which leases {@code status='queued'}) and
   * the broker's {@code ACTIVE_STATUSES = {pending, running}}. The live container (for a previously
   * {@code running} row) is stopped best-effort, post-commit, via the runner adapter — never here.
   */
  Optional<RunnerTakeoverCancellation> markCancelledForTakeover(
      String publicId, OffsetDateTime cancelledAt);

  /**
   * Story 4.8 (AC4) — manual pause: flip a {@code {queued, pending, running}} row to the terminal
   * {@code cancelled_for_pause} status (state-machine-guarded), stamping {@code completed_at =
   * cancelledAt}. The reversible sibling of {@link #markCancelledForTakeover}: same
   * already-terminal-returns-{@code Optional.empty()} idempotent skip, same "the DB flip is the
   * authoritative stop-dispatch signal" contract. Unlike takeover, an {@code awaiting_manual}
   * parked row is never flipped (the pause scan set excludes it — pause is reversible and a
   * cancelled manual park has no re-park path on resume), and the live container (for a previously
   * {@code running} row) is stopped best-effort POST-commit via the runner adapter — never here.
   */
  Optional<RunnerExecutionCancellation> markCancelledForPause(
      String publicId, OffsetDateTime cancelledAt);

  /**
   * Story 3.2 AC5: marks the row as {@code archived_at = archivedAt} once its on-disk workspace has
   * been deleted by {@code RunnerWorkspaceCleanupJob}. The row stays in its terminal status; only
   * the archive marker advances. Throws {@link org.dradgo.domain.DomainException} when the row is
   * missing.
   */
  RunnerExecutionSnapshot markArchived(String publicId, OffsetDateTime archivedAt);

  /**
   * Story 3.2 AC3 sub-bullet (d) — gates re-emission of {@code runner.heartbeatStale} to at most
   * once per stale window. Sets the column when the event is appended and is implicitly cleared on
   * any subsequent activity / terminal transition.
   */
  RunnerExecutionSnapshot markHeartbeatStaleEmitted(String publicId, OffsetDateTime emittedAt);

  /**
   * Story 3.2 AC3: find pending/running rows whose {@code last_activity_at < now() - staleWindow}.
   * Used by {@link org.dradgo.application.runner.RunnerBroker#scanForStaleExecutions} as the input
   * for both the heartbeat-stale (1x stage timeout) and orphan (2x stage timeout) sub-passes.
   */
  List<RunnerExecutionSnapshot> findStaleByStatusInAndLastActivityAtBefore(
      List<RunnerExecutionStatus> statuses, Duration staleWindow, int limit);

  /**
   * Story 3.2a AC2: stage-scoped variant of {@link
   * #findStaleByStatusInAndLastActivityAtBefore(List, Duration, int)}. The {@code stage} predicate
   * is pushed into the query so the {@code limit} applies <em>per stage</em>. This closes the
   * stage-starvation bug (3.2 review HIGH edge#2 / blind#1 / auditor F8): the non-scoped query was
   * {@code LIMIT batchSize} across all stages, then rows of the wrong stage were discarded
   * in-memory, so a backlog of one stage could starve every other stage on each scan tick.
   */
  List<RunnerExecutionSnapshot> findStaleByStatusInAndStageAndLastActivityAtBefore(
      List<RunnerExecutionStatus> statuses, RunnerStage stage, Duration staleWindow, int limit);

  /**
   * Story 3.17b (AC5 / D6) — worker-crash lease reclamation. Finds {@code running} rows for {@code
   * stage} that a worker leased ({@code worker_id} not null, {@code dispatched_at} stamped) but
   * whose lease has aged past {@code leaseWindow} (= {@code staleThresholdMultiplier × stage
   * timeout}) AND whose {@code last_activity_at} is also past it (no heartbeat advanced it). The
   * dual predicate spares a healthy long-running execution (it heartbeats) and targets only a
   * stalled lease left by a dead worker thread. {@code queued} rows (no {@code worker_id}) are
   * never returned — they are correctly waiting and stay enqueueable. Bounded by {@code limit}.
   */
  List<RunnerExecutionSnapshot> findLeasedStaleByStageAndDispatchedAtBefore(
      RunnerStage stage, Duration leaseWindow, int limit);

  /**
   * Story 3.2 AC5: find rows past the workspace-retention horizon whose workspace has not been
   * archived yet. SQL guard: {@code status IN (completed, failed, timed_out, orphaned)} so live
   * rows (pending/running) are never returned even if {@code completed_at} drifted (Trap T16).
   */
  List<RunnerExecutionSnapshot> findCompletedBeforeAndNotArchived(OffsetDateTime cutoff, int limit);

  /**
   * Story 3.6 AC3/AC6 / Trap T10 — persist the durable redacted-log capture reference + metrics
   * onto the row. This is a METADATA-ONLY update: it never changes {@code status} (no state-machine
   * guard) and tolerates an already-terminal row (the row is always terminal by the time logs are
   * captured at container exit). Throws {@link org.dradgo.domain.DomainException} only when the row
   * is missing.
   */
  RunnerExecutionSnapshot recordRawOutput(
      String publicId,
      String reference,
      DataClassification classification,
      long byteSize,
      int redactionCount);

  /**
   * Story 3g-3 (FR74) — persist the agent's per-execution token counts onto the row. Like {@link
   * #recordRawOutput} this is a METADATA-ONLY update: it never changes {@code status} (no
   * state-machine guard) and is status-agnostic — capture runs during result ingest BEFORE the row
   * transitions to a terminal state, so it tolerates a still-{@code running} row as well as an
   * already-terminal one. Throws {@link org.dradgo.domain.DomainException} only when the row is
   * missing. Each count is nullable and persisted independently as reported — the caller has
   * already sanitized/dropped any absent or malformed value to {@code null}, and never synthesizes
   * {@code totalTokens} from input+output.
   */
  RunnerExecutionSnapshot recordTokenUsage(
      String publicId, Integer inputTokens, Integer outputTokens, Integer totalTokens);

  /**
   * Story 3h-2 (AC6, FR76) — persist the severity-classified lint findings (an already-serialized,
   * already-redacted JSON string) onto the LINT execution row's {@code lint_findings} jsonb column.
   * Like {@link #recordRawOutput} / {@link #recordTokenUsage} this is a METADATA-ONLY update: it
   * never changes {@code status} (no state-machine guard) and tolerates a still-{@code running} row
   * (findings are recorded during the LINT capture, before the row is finalized). A {@code null}
   * payload clears the column. Throws {@link org.dradgo.domain.DomainException} only when the row
   * is missing.
   */
  RunnerExecutionSnapshot recordLintFindings(String publicId, String lintFindingsJson);

  /**
   * Story 3d-2 (code-review D1) — pin the reviewed artifact onto a {@link RunnerStage#REVIEW}
   * execution at enqueue. The advisory reviewer's reviewed artifact is resolved ONCE (at enqueue,
   * before any newer artifact can land) and stored here so both the dispatch-time compose and the
   * result-time harvest reuse the SAME artifact instead of independently re-deriving it (which
   * could drift the verdict's composite FK to an artifact the reviewer never saw). Metadata-only
   * update (no state-machine guard). Default no-op so non-persistence fakes need not implement it.
   *
   * @param publicId the reviewer execution ({@code rex_…})
   * @param reviewedArtifactPublicId the reviewed artifact's public id ({@code art_…})
   * @param reviewedArtifactVersion the reviewed artifact's exact version
   * @param reviewedArtifactType the reviewed artifact's wire type (e.g. {@code prOutput})
   */
  default void pinReviewedArtifact(
      String publicId,
      String reviewedArtifactPublicId,
      int reviewedArtifactVersion,
      String reviewedArtifactType) {}

  /**
   * Story 3d-2 (code-review D1) — read the reviewed-artifact pin a reviewer execution carries, or
   * empty when none was stored (non-REVIEW row, a reviewer enqueued before V22, or an enqueue-time
   * resolve failure). The harvest reuses the pin when present and falls back to re-deriving when
   * absent. Default empty so non-persistence fakes need not implement it.
   */
  default Optional<ReviewedArtifactPin> findReviewedArtifactPin(String publicId) {
    return Optional.empty();
  }

  /** The reviewed-artifact pin a {@link RunnerStage#REVIEW} execution carries (story 3d-2 D1). */
  record ReviewedArtifactPin(String artifactPublicId, int version, String artifactType) {}
}
