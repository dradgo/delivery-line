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
   * Story 3.17a (AC4) — count the rows currently in {@code status = queued}. Backs the
   * RunnerExecutionQueue backpressure cap; called under the enqueue write transaction so the count
   * reflects rows committed (or inserted-and-pending) at decision time.
   */
  long countQueued();

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
}
