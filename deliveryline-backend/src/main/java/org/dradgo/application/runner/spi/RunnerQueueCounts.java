package org.dradgo.application.runner.spi;

import java.time.OffsetDateTime;

/**
 * Story 3.19 (AC1/AC3/AC9) — scalar aggregate of the {@code runner_executions} queue state, read in
 * ONE native SELECT (Postgres {@code FILTER} clauses) so all counts reflect a single consistent
 * snapshot. Backs {@code WorkflowInspectionService.getRunnerQueueStatus}; the per-worker {@code
 * WorkerStatus} list is loaded separately (the leased running rows).
 *
 * <ul>
 *   <li>{@code queueDepth} — rows in {@code status='queued'}.
 *   <li>{@code activeWorkers} — leased running rows ({@code status='running' AND worker_id IS NOT
 *       NULL}); equals {@code inFlightExecutions}. Idle workers are derived in the service from
 *       {@code poolSize − activeWorkers} (the pool keeps no per-worker roster — Reconciliation 6).
 *   <li>{@code staleQueuedCount} — queued rows whose {@code created_at} is older than the lease
 *       window ({@code staleThresholdMultiplier × stageTimeout}).
 *   <li>{@code staleDispatchedCount} — leased running rows whose {@code dispatched_at} is older
 *       than the same window (a worker that took the row but never completed — same threshold the
 *       broker uses to flag an orphan).
 *   <li>{@code recentThroughput} — rows that reached {@code status='completed'} within the recent
 *       throughput window (default 60s); surfaced as {@code recentThroughputPerMinute}.
 *   <li>{@code oldestQueuedAt} — {@code min(created_at)} over queued rows, or null when none
 *       queued.
 *   <li>{@code oldestQueuedAgeSeconds} — server-side {@code now() − min(created_at)} in whole
 *       seconds (computed in SQL so no JVM clock is consulted), 0 when none queued.
 * </ul>
 */
public record RunnerQueueCounts(
    long queueDepth,
    long activeWorkers,
    long staleQueuedCount,
    long staleDispatchedCount,
    long recentThroughput,
    OffsetDateTime oldestQueuedAt,
    long oldestQueuedAgeSeconds) {

  /** A fully-zeroed snapshot (no queued/running/completed rows in scope). */
  public static RunnerQueueCounts empty() {
    return new RunnerQueueCounts(0L, 0L, 0L, 0L, 0L, null, 0L);
  }
}
