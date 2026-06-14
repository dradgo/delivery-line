package org.dradgo.application.runner.queue;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
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
 * Story 3.17b (AC2) — review D3: the LISTEN/NOTIFY idle wake-up p95 latency measurement IT the
 * original story deferred. Proves the {@code enqueue → afterCommit NOTIFY → RunnerQueueListener →
 * RunnerQueueSignal → worker dequeue} hop wakes an idle worker within the AC2 p95 &lt; 500ms
 * budget.
 *
 * <p><b>Isolating NOTIFY from the poll.</b> The backoff poll is pinned to 5s (initial = max) so a
 * worker that is NOT woken by a NOTIFY would only pick up the work after ~5s. Therefore a measured
 * latency well under 500ms can ONLY come from the LISTEN/NOTIFY path — if NOTIFY silently broke,
 * this test would fail (p95 ≈ 5s), which is exactly the regression AC2 guards against. The pool
 * runs 4 workers so an idle worker is always waiting when each single enqueue arrives.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles({"test", "linear-mock"})
@TestPropertySource(
    properties = {
      "deliveryline.runner.worker-pool.enabled=true",
      "deliveryline.runner.worker-pool.size=4",
      // Pin the poll wide so a <500ms wake-up can only be NOTIFY-driven, never poll-driven.
      "deliveryline.runner.worker-pool.backoff.initial=5s",
      "deliveryline.runner.worker-pool.backoff.max=5s"
    })
@Tag("integration")
class RunnerNotifyLatencyIT {

  private static final int WARMUP = 3;
  private static final int SAMPLES = 20;
  private static final Duration P95_BUDGET = Duration.ofMillis(500);
  private static final Duration PER_ENQUEUE_DEADLINE = Duration.ofSeconds(8);

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
  void idleWakeUpP95IsUnder500ms() throws Exception {
    List<Long> latenciesMillis = new ArrayList<>();
    int iterations = WARMUP + SAMPLES;
    for (int i = 0; i < iterations; i++) {
      String runId = seedInvestigatingRun();
      String idempotencyKey = "spec-dispatch:" + runId + ":0";

      long t0 = System.nanoTime();
      QueuedRunnerExecution queued =
          queue.enqueue(
              runId,
              RunnerStage.INVESTIGATION,
              idempotencyKey,
              new ActorContext("system", ActorType.SYSTEM, "corr-latency-" + i),
              100);
      long leasedAtNanos = awaitLeasedNanos(queued.runnerExecutionPublicId());
      long latencyMillis = (leasedAtNanos - t0) / 1_000_000L;

      if (i >= WARMUP) {
        latenciesMillis.add(latencyMillis);
      }
      // Let the worker that handled this row loop back to its await before the next enqueue.
      Thread.sleep(30);
    }

    Collections.sort(latenciesMillis);
    long p95 = latenciesMillis.get((int) Math.ceil(0.95 * latenciesMillis.size()) - 1);
    long max = latenciesMillis.get(latenciesMillis.size() - 1);
    assertTrue(
        p95 <= P95_BUDGET.toMillis(),
        () ->
            "AC2 idle wake-up p95 exceeded "
                + P95_BUDGET.toMillis()
                + "ms (p95="
                + p95
                + "ms, max="
                + max
                + "ms, samples="
                + latenciesMillis
                + ") — LISTEN/NOTIFY likely not waking idle workers");
  }

  /**
   * Spin (fine-grained) until the row leaves QUEUED — that flip happens at the worker's dequeue.
   */
  private long awaitLeasedNanos(String rexPublicId) throws InterruptedException {
    long deadline = System.nanoTime() + PER_ENQUEUE_DEADLINE.toNanos();
    while (System.nanoTime() < deadline) {
      RunnerExecutionSnapshot snapshot = recordPort.findByPublicId(rexPublicId).orElseThrow();
      if (snapshot.status() != RunnerExecutionStatus.QUEUED) {
        return System.nanoTime();
      }
      Thread.sleep(2);
    }
    throw new AssertionError(
        "worker never leased the enqueued row within "
            + PER_ENQUEUE_DEADLINE.toSeconds()
            + "s (rex="
            + rexPublicId
            + ")");
  }

  private String seedInvestigatingRun() {
    String runId = PublicIdPrefixes.WORKFLOW_RUN.next();
    jdbcTemplate.update(
        "insert into workflow_runs (public_id, current_state) values (?, 'Investigating')", runId);
    return runId;
  }
}
