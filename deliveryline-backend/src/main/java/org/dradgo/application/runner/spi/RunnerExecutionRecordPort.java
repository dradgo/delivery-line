package org.dradgo.application.runner.spi;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.dradgo.application.runner.ExecutionConstraints;
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

  RunnerExecutionSnapshot transitionToRunning(String publicId, OffsetDateTime lastActivityAt);

  RunnerExecutionSnapshot touchActivity(
      String publicId, OffsetDateTime lastActivityAt, Duration staleTimeoutWindow);

  RunnerExecutionSnapshot markCompleted(String publicId, OffsetDateTime completedAt);

  RunnerExecutionSnapshot markFailed(
      String publicId, FailureCategory failureCategory, OffsetDateTime completedAt);

  RunnerExecutionSnapshot markTimedOut(String publicId, OffsetDateTime completedAt);

  RunnerExecutionSnapshot markOrphaned(String publicId, OffsetDateTime completedAt);
}
