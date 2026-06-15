package org.dradgo.application.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.dradgo.TestcontainersConfiguration;
import org.dradgo.application.workflow.WorkflowInspectionService.RunnerQueueStatus;
import org.dradgo.application.workflow.WorkflowInspectionService.WorkerStatus;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.id.PublicIdPrefixes;
import org.dradgo.domain.registry.DomainErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Story 3.19 (AC1/AC3/AC9, Task 1) — Testcontainers Postgres coverage of {@link
 * WorkflowInspectionService#getRunnerQueueStatus(String)} over real {@code runner_executions} rows.
 * Drives the new native aggregate + leased-rows SQL on a real Postgres (mocks would not exercise
 * the {@code FILTER}/{@code make_interval} server-side math —
 * markescalationonce-boolean-returning-bug lesson). Seeds queued / leased-running / stale /
 * completed / batch-tagged rows and asserts every field, then the {@code ?batchId} scoping and the
 * malformed-prefix guard.
 *
 * <p>NOT {@code @Tag("docker-runner-it")} — only a real Postgres is needed, no runner images.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles({"test", "linear-mock"})
@Tag("integration")
class WorkflowInspectionRunnerQueueIT {

  // Test profile: stale window = staleThresholdMultiplier(2.0) × stageTimeout(600s) = 1200s.
  // "old" timestamps are comfortably past it; "recent" ones are well inside it.
  private static final String OLD = "now() - interval '40 minutes'";
  private static final String RECENT = "now() - interval '10 seconds'";

  @Autowired private WorkflowInspectionService inspectionService;
  @Autowired private JdbcTemplate jdbcTemplate;

  @AfterEach
  void tearDown() {
    // Shared Postgres with sibling contract tests; delete seeded rows (FK-safe order).
    jdbcTemplate.update("delete from runner_executions");
    jdbcTemplate.update("delete from workflow_events");
    jdbcTemplate.update("delete from batch_submissions");
    jdbcTemplate.update("delete from workflow_runs");
  }

  @Test
  void reportsEveryFieldAcrossQueuedLeasedStaleAndCompletedRows() {
    String runId = seedRun();
    // 2 recent queued + 1 stale queued (old created_at). queueDepth = 3; staleQueued = 1.
    insertQueued(runId, "rex_q0000001", RECENT, null);
    insertQueued(runId, "rex_q0000002", RECENT, null);
    insertQueued(runId, "rex_q0000003", OLD, null);
    // 1 recent leased-running (active) + 1 stale leased-running (active + staleDispatched).
    insertLeasedRunning(runId, "rex_r0000001", "worker-1", RECENT, "investigation", null);
    insertLeasedRunning(runId, "rex_r0000002", "worker-2", OLD, "execution", null);
    // 1 completed within the throughput window → recentThroughputPerMinute = 1.
    insertCompleted(runId, "rex_c0000001", RECENT, null);

    RunnerQueueStatus status = inspectionService.getRunnerQueueStatus(null);

    assertThat(status.poolSize()).isEqualTo(2); // test application.yml worker-pool.size
    assertThat(status.queueDepth()).isEqualTo(3L);
    assertThat(status.activeWorkers()).isEqualTo(2L);
    assertThat(status.inFlightExecutions()).isEqualTo(2L);
    assertThat(status.idleWorkers()).isEqualTo(0L); // max(0, 2 - 2)
    assertThat(status.staleQueuedCount()).isEqualTo(1L);
    assertThat(status.staleDispatchedCount()).isEqualTo(1L);
    assertThat(status.recentThroughputPerMinute()).isEqualTo(1L);
    assertThat(status.oldestQueuedAt()).isNotNull();
    assertThat(status.oldestQueuedAgeSeconds()).isGreaterThanOrEqualTo(2400L - 60L); // ~40 min

    assertThat(status.workers()).hasSize(2);
    // Ordered oldest-lease-first: the stale (OLD dispatched) worker leads.
    WorkerStatus first = status.workers().get(0);
    assertThat(first.workerId()).isEqualTo("worker-2");
    assertThat(first.state()).isEqualTo("busy");
    assertThat(first.currentRunnerExecutionId()).isEqualTo("rex_r0000002");
    assertThat(first.currentWorkflowRunId()).isEqualTo(runId);
    assertThat(first.currentStage()).isEqualTo("execution");
    assertThat(first.dispatchedAt()).isNotNull();
  }

  @Test
  void emptyQueueYieldsZeroedViewWithFullIdlePool() {
    RunnerQueueStatus status = inspectionService.getRunnerQueueStatus(null);

    assertThat(status.poolSize()).isEqualTo(2);
    assertThat(status.queueDepth()).isZero();
    assertThat(status.activeWorkers()).isZero();
    assertThat(status.idleWorkers()).isEqualTo(2L); // all idle
    assertThat(status.staleQueuedCount()).isZero();
    assertThat(status.staleDispatchedCount()).isZero();
    assertThat(status.recentThroughputPerMinute()).isZero();
    assertThat(status.oldestQueuedAt()).isNull();
    assertThat(status.oldestQueuedAgeSeconds()).isZero();
    assertThat(status.workers()).isEmpty();
  }

  @Test
  void batchFilterScopesCountsAndWorkersToThatBatch() {
    String runId = seedRun();
    // batch_submission_id is a free-text trace column on runner_executions (no FK — V15), so the
    // filter needs only the tagged rows, not a real batch_submissions row.
    String batchId = "bat_filter12345678";
    // 2 queued + 1 leased-running tagged to the batch; plus unrelated rows with no batch.
    insertQueued(runId, "rex_b0000001", RECENT, batchId);
    insertQueued(runId, "rex_b0000002", RECENT, batchId);
    insertLeasedRunning(runId, "rex_b0000003", "worker-9", RECENT, "investigation", batchId);
    insertQueued(runId, "rex_n0000001", RECENT, null); // not in the batch
    insertLeasedRunning(runId, "rex_n0000002", "worker-8", RECENT, "investigation", null);

    RunnerQueueStatus batchView = inspectionService.getRunnerQueueStatus(batchId);

    assertThat(batchView.queueDepth()).isEqualTo(2L); // only the batch's queued rows
    assertThat(batchView.activeWorkers()).isEqualTo(1L); // only the batch's leased row
    assertThat(batchView.workers()).hasSize(1);
    assertThat(batchView.workers().get(0).currentRunnerExecutionId()).isEqualTo("rex_b0000003");
    assertThat(batchView.poolSize()).isEqualTo(2); // poolSize stays global

    // The global view sees everything.
    RunnerQueueStatus globalView = inspectionService.getRunnerQueueStatus(null);
    assertThat(globalView.queueDepth()).isEqualTo(3L);
    assertThat(globalView.activeWorkers()).isEqualTo(2L);
  }

  @Test
  void malformedBatchIdRaisesInvalidIdPrefix() {
    assertThatThrownBy(() -> inspectionService.getRunnerQueueStatus("not-a-batch-id"))
        .isInstanceOf(DomainException.class)
        .extracting(e -> ((DomainException) e).errorCode())
        .isEqualTo(DomainErrorCode.INVALID_ID_PREFIX);
  }

  // ----- seed helpers -----

  private String seedRun() {
    String runId = PublicIdPrefixes.WORKFLOW_RUN.next();
    jdbcTemplate.update(
        "insert into workflow_runs (public_id, current_state) values (?, 'Inbox')", runId);
    return runId;
  }

  private void insertQueued(String runId, String publicId, String createdAtExpr, String batchId) {
    jdbcTemplate.update(
        """
        insert into runner_executions
          (public_id, workflow_run_id, stage, status, context_bundle_version,
           last_activity_at, timeout_at, queue_priority, queue_attempt_count, created_at,
           batch_submission_id)
        values (?, (select id from workflow_runs where public_id = ?),
                'investigation', 'queued', 1, now(), now() + interval '10 minutes', 100, 0,
        """
            + createdAtExpr
            + ", ?)",
        publicId,
        runId,
        batchId);
  }

  private void insertLeasedRunning(
      String runId,
      String publicId,
      String workerId,
      String dispatchedAtExpr,
      String stage,
      String batchId) {
    jdbcTemplate.update(
        """
        insert into runner_executions
          (public_id, workflow_run_id, stage, status, context_bundle_version,
           last_activity_at, timeout_at, queue_priority, queue_attempt_count, created_at,
           worker_id, dispatched_at, batch_submission_id)
        values (?, (select id from workflow_runs where public_id = ?),
                ?, 'running', 1, now(), now() + interval '10 minutes', 100, 1, now(),
                ?,
        """
            + dispatchedAtExpr
            + ", ?)",
        publicId,
        runId,
        stage,
        workerId,
        batchId);
  }

  private void insertCompleted(
      String runId, String publicId, String completedAtExpr, String batchId) {
    jdbcTemplate.update(
        """
        insert into runner_executions
          (public_id, workflow_run_id, stage, status, context_bundle_version,
           last_activity_at, timeout_at, queue_priority, queue_attempt_count, created_at,
           completed_at, batch_submission_id)
        values (?, (select id from workflow_runs where public_id = ?),
                'investigation', 'completed', 1, now(), now() + interval '10 minutes', 100, 0,
                now() - interval '5 minutes',
        """
            + completedAtExpr
            + ", ?)",
        publicId,
        runId,
        batchId);
  }
}
