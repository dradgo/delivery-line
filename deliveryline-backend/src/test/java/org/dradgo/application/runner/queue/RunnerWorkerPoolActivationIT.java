package org.dradgo.application.runner.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
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
 * Story 3.17b (AC1/AC2/AC7) — Testcontainers Postgres coverage of the ACTIVATED queue: with the
 * worker pool enabled, an {@code enqueue} is leased + dispatched by a worker thread (the relocated
 * {@code RunnerBroker.executeQueuedDispatch} body) within the AC2 idle latency budget, driven by
 * the {@code LISTEN/NOTIFY} wake-up (with the backoff poll as the always-correct fallback, Decision
 * D8).
 *
 * <p>Opts the worker pool ON via {@code @TestPropertySource} (the shared test profile keeps it OFF,
 * Trap T8). The runners.mock adapter ack-completes the dispatch; the {@code @Scheduled} poller
 * stays off (test profile), so the row settles at {@code running} — proving the ENQUEUE→DISPATCH
 * leg the worker owns, which is exactly this story's scope (the result harvest is unchanged,
 * R2/Trap T2).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles({"test", "linear-mock"})
@TestPropertySource(properties = "deliveryline.runner.worker-pool.enabled=true")
@Tag("integration")
class RunnerWorkerPoolActivationIT {

  private static final Duration PICKUP_BUDGET = Duration.ofSeconds(15);

  @Autowired private RunnerExecutionQueue queue;
  @Autowired private RunnerExecutionRecordPort recordPort;
  @Autowired private RunnerWorkerPool workerPool;
  @Autowired private JdbcTemplate jdbcTemplate;

  @AfterEach
  void tearDown() {
    // Drain the worker pool BEFORE deleting rows so an in-flight dispatch (the worker keeps running
    // after the test asserts the lease) cannot race the teardown deletes.
    workerPool.stop();
    jdbcTemplate.update("delete from runner_executions");
    jdbcTemplate.update("delete from artifacts");
    jdbcTemplate.update("delete from workflow_events");
    jdbcTemplate.update("delete from workflow_runs");
  }

  @Test
  void enqueuedWorkIsLeasedAndDispatchedByAWorkerThread() throws Exception {
    String runId = seedInvestigatingRun();

    QueuedRunnerExecution queued =
        queue.enqueue(
            runId,
            RunnerStage.INVESTIGATION,
            "spec-dispatch:" + runId + ":0",
            new ActorContext("system", ActorType.SYSTEM, "corr-activation"),
            100);

    // The worker pool (enabled via @TestPropertySource) wakes on the enqueue NOTIFY and runs the
    // relocated dispatch body off the leased row — the row leaves QUEUED and is stamped with a
    // lease.
    RunnerExecutionSnapshot dispatched =
        awaitNotQueued(queued.runnerExecutionPublicId(), PICKUP_BUDGET);

    assertTrue(
        dispatched.status() != RunnerExecutionStatus.QUEUED,
        () -> "worker never leased the row; status=" + dispatched.status());
    assertNotNull(dispatched.workerId(), "the lease must stamp worker_id");
    assertTrue(
        dispatched.workerId().startsWith("runner-worker-"),
        () -> "unexpected worker id: " + dispatched.workerId());
    assertNotNull(dispatched.dispatchedAt(), "the lease must stamp dispatched_at");
    assertEquals(
        1, dispatched.queueAttemptCount(), "the dequeue lease increments the attempt count");
    // The originating correlationId carried at enqueue survived onto the row (AC5/AC8).
    assertEquals("corr-activation", dispatched.correlationId());
  }

  private RunnerExecutionSnapshot awaitNotQueued(String rexPublicId, Duration budget)
      throws InterruptedException {
    long deadline = System.nanoTime() + budget.toNanos();
    RunnerExecutionSnapshot last = recordPort.findByPublicId(rexPublicId).orElseThrow();
    while (last.status() == RunnerExecutionStatus.QUEUED && System.nanoTime() < deadline) {
      Thread.sleep(100);
      last = recordPort.findByPublicId(rexPublicId).orElseThrow();
    }
    return last;
  }

  private String seedInvestigatingRun() {
    String runId = PublicIdPrefixes.WORKFLOW_RUN.next();
    jdbcTemplate.update(
        "insert into workflow_runs (public_id, current_state) values (?, 'Investigating')", runId);
    return runId;
  }
}
