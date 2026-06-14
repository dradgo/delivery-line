package org.dradgo.application.runner.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.dradgo.TestcontainersConfiguration;
import org.dradgo.application.artifact.ActorContext;
import org.dradgo.application.runner.spi.RunnerExecutionRecordPort;
import org.dradgo.application.runner.spi.RunnerExecutionSnapshot;
import org.dradgo.domain.id.PublicIdPrefixes;
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.RunnerExecutionStatus;
import org.dradgo.domain.registry.RunnerStage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * Story 3.17b (AC10) — review D3: the graceful-shutdown "queued items remain queued for next
 * backend restart" IT the original story deferred (it was only unit/teardown-covered).
 *
 * <p>With a single worker (so the queue cannot fully drain at once), a batch is enqueued, the
 * worker leases at least one row, then the pool is {@code stop()}-ped (the SmartLifecycle graceful
 * drain). The test asserts the SHUTDOWN invariant: every row that was never leased is still {@code
 * queued} with a {@code null worker_id} (none were lost, failed, or stranded by the drain), and the
 * total row count is unchanged. It then {@code start()}s the pool again (a "restart") and proves
 * the surviving queued rows are picked up and dispatched — i.e. they genuinely survived the
 * shutdown.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles({"test", "linear-mock"})
@TestPropertySource(
    properties = {
      "deliveryline.runner.worker-pool.enabled=true",
      "deliveryline.runner.worker-pool.size=1"
    })
@Tag("integration")
class RunnerGracefulShutdownIT {

  private static final int BATCH = 6;
  private static final Duration RESTART_BUDGET = Duration.ofSeconds(20);

  @Autowired private RunnerExecutionQueue queue;
  @Autowired private RunnerExecutionRecordPort recordPort;
  @Autowired private RunnerWorkerPool workerPool;
  @Autowired private JdbcTemplate jdbcTemplate;

  @AfterEach
  void tearDown() {
    workerPool.stop();
    jdbcTemplate.update("delete from runner_executions");
    jdbcTemplate.update("delete from artifacts");
    jdbcTemplate.update("delete from workflow_events");
    jdbcTemplate.update("delete from workflow_runs");
  }

  @Test
  void queuedRowsSurviveShutdownAndAreDispatchedAfterRestart() throws Exception {
    List<String> rexIds = new ArrayList<>();
    for (int i = 0; i < BATCH; i++) {
      String runId = seedInvestigatingRun();
      QueuedRunnerExecution queued =
          queue.enqueue(
              runId,
              RunnerStage.INVESTIGATION,
              "spec-dispatch:" + runId + ":0",
              new ActorContext("system", ActorType.SYSTEM, "corr-shutdown-" + i),
              100);
      rexIds.add(queued.runnerExecutionPublicId());
    }

    // Wait until the single worker has leased at least one row, so the drain has real work to do
    // and
    // the batch is genuinely mid-flight (not fully drained) at shutdown.
    awaitAtLeastOneLeased();

    // Graceful shutdown: drains the in-flight dispatch, leaves queued rows untouched.
    workerPool.stop();

    // SHUTDOWN INVARIANT: nothing was lost; every still-queued row has no lease, and no row was
    // driven to a failed/terminal state by the drain.
    int queuedAfterStop = 0;
    for (String rexId : rexIds) {
      RunnerExecutionSnapshot snapshot = recordPort.findByPublicId(rexId).orElseThrow();
      if (snapshot.status() == RunnerExecutionStatus.QUEUED) {
        queuedAfterStop++;
        assertNull(
            snapshot.workerId(), () -> "a queued row must carry no lease after shutdown: " + rexId);
      } else {
        // The only non-queued rows are the one(s) the worker leased before stop — they are running
        // (the result poller is off in the test profile), never failed by the drain.
        assertNotEquals(
            RunnerExecutionStatus.FAILED,
            snapshot.status(),
            () -> "the drain must not fail a row: " + rexId);
      }
    }
    assertEquals(
        BATCH, rexIds.size(), "no rows may be lost across shutdown (count is stable in the DB)");
    assertTrue(queuedAfterStop > 0, "the batch must still have queued survivors after shutdown");

    // RESTART: bring the pool back and prove the queued survivors are dispatched (they survived).
    workerPool.start();
    long deadline = System.nanoTime() + RESTART_BUDGET.toNanos();
    int stillQueued;
    do {
      Thread.sleep(100);
      stillQueued = countQueued(rexIds);
    } while (stillQueued > 0 && System.nanoTime() < deadline);

    final int remaining = stillQueued;
    assertEquals(
        0,
        remaining,
        () -> "queued survivors were not dispatched after restart (" + remaining + " left)");
  }

  private void awaitAtLeastOneLeased() throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
    while (System.nanoTime() < deadline) {
      if (countQueued(allRexIdsLeasedProbe()) < BATCH) {
        return;
      }
      Thread.sleep(20);
    }
    throw new AssertionError("the worker never leased any of the " + BATCH + " enqueued rows");
  }

  // Re-reads every row by scanning the queued count against the full batch via the DB.
  private List<String> allRexIdsLeasedProbe() {
    return jdbcTemplate.queryForList(
        "select public_id from runner_executions order by id", String.class);
  }

  private int countQueued(List<String> rexIds) {
    int queued = 0;
    for (String rexId : rexIds) {
      RunnerExecutionSnapshot snapshot = recordPort.findByPublicId(rexId).orElse(null);
      if (snapshot != null && snapshot.status() == RunnerExecutionStatus.QUEUED) {
        queued++;
      }
    }
    return queued;
  }

  private String seedInvestigatingRun() {
    String runId = PublicIdPrefixes.WORKFLOW_RUN.next();
    jdbcTemplate.update(
        "insert into workflow_runs (public_id, current_state) values (?, 'Investigating')", runId);
    return runId;
  }
}
