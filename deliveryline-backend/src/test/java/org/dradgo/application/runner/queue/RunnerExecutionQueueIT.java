package org.dradgo.application.runner.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.dradgo.TestcontainersConfiguration;
import org.dradgo.application.runner.spi.RunnerExecutionRecordPort;
import org.dradgo.application.runner.spi.RunnerExecutionSnapshot;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.id.PublicIdPrefixes;
import org.dradgo.domain.registry.DomainErrorCode;
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

/**
 * Story 3.17a (AC8) — Testcontainers Postgres coverage of the {@link RunnerExecutionQueue}
 * substrate: enqueue→dequeue round-trip, {@code FOR UPDATE SKIP LOCKED} prevents two concurrent
 * dequeues from picking the same row, priority+age ordering, backpressure ({@code
 * RUNNER_QUEUE_FULL}), and correlationId persistence. The queue is dormant in production (no
 * production caller) — these tests exercise it directly, which is exactly why the substrate can
 * land proven under concurrency before story 3.17b wires the worker pool + callers.
 *
 * <p>NOT {@code @Tag("docker-runner-it")} — no runner images are needed, only a real Postgres (Trap
 * T7).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles({"test", "linear-mock"})
@Tag("integration")
class RunnerExecutionQueueIT {

  @Autowired private RunnerExecutionQueue queue;
  @Autowired private RunnerExecutionRecordPort recordPort;
  @Autowired private JdbcTemplate jdbcTemplate;

  @AfterEach
  void tearDown() {
    // This @SpringBootTest is not @Transactional and shares its Postgres with sibling contract
    // tests; delete the rows it seeded (FK-safe order) so leftover queued rows do not skew a
    // sibling's own counts/cleanup.
    jdbcTemplate.update("delete from runner_executions");
    jdbcTemplate.update("delete from workflow_events");
    jdbcTemplate.update("delete from workflow_runs");
  }

  @Test
  void enqueueThenDequeueLeasesTheRowAndPreservesCorrelationId() {
    String runId = seedRun();

    QueuedRunnerExecution queued =
        queue.enqueue(runId, RunnerStage.INVESTIGATION, "bundle-ref", "idem-1", "corr-42", 100);
    assertEquals(1L, queued.currentDepth());
    assertEquals("corr-42", queued.correlationId());
    assertEquals(
        RunnerExecutionStatus.QUEUED,
        recordPort.findByPublicId(queued.runnerExecutionPublicId()).orElseThrow().status());

    Optional<RunnerExecutionSnapshot> leased = queue.dequeue("worker-1");

    assertTrue(leased.isPresent(), "the single queued row must be leased");
    RunnerExecutionSnapshot snapshot = leased.get();
    assertEquals(queued.runnerExecutionPublicId(), snapshot.publicId());
    assertEquals(RunnerExecutionStatus.RUNNING, snapshot.status());
    assertEquals("worker-1", snapshot.workerId());
    assertEquals(1, snapshot.queueAttemptCount());
    assertNotNull(snapshot.dispatchedAt(), "dispatched_at must be stamped on lease");
    // AC5/AC8 — the originating correlationId persisted at enqueue is read back off the row.
    assertEquals("corr-42", snapshot.correlationId());
    assertEquals(
        "corr-42",
        jdbcTemplate.queryForObject(
            "select correlation_id from runner_executions where public_id = ?",
            String.class,
            snapshot.publicId()));

    // Queue is now empty — a second dequeue finds nothing.
    assertTrue(queue.dequeue("worker-1").isEmpty());
  }

  @Test
  void dequeueHonorsPriorityThenCreatedAtOrdering() {
    String runId = seedRun();
    // Enqueue out of priority order; lower queue_priority must dequeue first.
    String low =
        queue
            .enqueue(runId, RunnerStage.INVESTIGATION, "b", "i1", "c", 200)
            .runnerExecutionPublicId();
    String high =
        queue
            .enqueue(runId, RunnerStage.INVESTIGATION, "b", "i2", "c", 50)
            .runnerExecutionPublicId();
    String mid =
        queue
            .enqueue(runId, RunnerStage.INVESTIGATION, "b", "i3", "c", 100)
            .runnerExecutionPublicId();

    assertEquals(high, queue.dequeue("w").orElseThrow().publicId());
    assertEquals(mid, queue.dequeue("w").orElseThrow().publicId());
    assertEquals(low, queue.dequeue("w").orElseThrow().publicId());
  }

  @Test
  void twoConcurrentDequeuesNeverPickTheSameRow() throws Exception {
    String runId = seedRun();
    String a =
        queue
            .enqueue(runId, RunnerStage.INVESTIGATION, "b", "i1", "c", 100)
            .runnerExecutionPublicId();
    String b =
        queue
            .enqueue(runId, RunnerStage.INVESTIGATION, "b", "i2", "c", 100)
            .runnerExecutionPublicId();

    List<Optional<RunnerExecutionSnapshot>> results = runConcurrentDequeues(2);

    List<String> leased =
        results.stream().filter(Optional::isPresent).map(o -> o.get().publicId()).sorted().toList();
    assertEquals(2, leased.size(), "both racing workers must each lease a distinct row");
    assertEquals(List.of(a, b).stream().sorted().toList(), leased);
  }

  @Test
  void concurrentDequeuesOnASingleRowLeaseItExactlyOnce() throws Exception {
    String runId = seedRun();
    queue.enqueue(runId, RunnerStage.INVESTIGATION, "b", "i1", "c", 100);

    List<Optional<RunnerExecutionSnapshot>> results = runConcurrentDequeues(2);

    long leased = results.stream().filter(Optional::isPresent).count();
    assertEquals(1L, leased, "SKIP LOCKED — exactly one of two racing workers leases the only row");
  }

  @Test
  void enqueueAtCapacityRaisesRunnerQueueFullAndWritesNoRow() {
    String runId = seedRun();
    // queue-max-depth defaults to 100 (test application.yml mirrors production). Seed exactly the
    // cap via raw inserts (fast), then the next enqueue must be rejected without adding a row.
    seedQueuedRows(runId, 100);

    DomainException ex =
        assertThrows(
            DomainException.class,
            () -> queue.enqueue(runId, RunnerStage.INVESTIGATION, "b", "i", "c", 100));

    assertEquals(DomainErrorCode.RUNNER_QUEUE_FULL, ex.errorCode());
    assertEquals(100L, ex.details().get("currentDepth"));
    assertEquals(100, ex.details().get("maxDepth"));
    assertEquals(
        100L,
        jdbcTemplate.queryForObject(
            "select count(*) from runner_executions where status = 'queued'", Long.class),
        "no row may be written when the queue is full");
  }

  // ----- helpers -----

  private List<Optional<RunnerExecutionSnapshot>> runConcurrentDequeues(int workers)
      throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(workers);
    try {
      CyclicBarrier barrier = new CyclicBarrier(workers);
      List<Callable<Optional<RunnerExecutionSnapshot>>> tasks = new ArrayList<>();
      for (int i = 0; i < workers; i++) {
        String workerId = "worker-" + i;
        tasks.add(
            () -> {
              barrier.await(10, TimeUnit.SECONDS); // release both threads into dequeue together
              return queue.dequeue(workerId);
            });
      }
      List<Future<Optional<RunnerExecutionSnapshot>>> futures = pool.invokeAll(tasks);
      List<Optional<RunnerExecutionSnapshot>> results = new ArrayList<>();
      for (Future<Optional<RunnerExecutionSnapshot>> future : futures) {
        results.add(future.get(15, TimeUnit.SECONDS));
      }
      return results;
    } finally {
      pool.shutdownNow();
    }
  }

  private String seedRun() {
    String runId = PublicIdPrefixes.WORKFLOW_RUN.next();
    jdbcTemplate.update(
        "insert into workflow_runs (public_id, current_state) values (?, 'Inbox')", runId);
    return runId;
  }

  private void seedQueuedRows(String runId, int count) {
    jdbcTemplate.update(
        """
        insert into runner_executions
          (public_id, workflow_run_id, stage, status, context_bundle_version,
           last_activity_at, timeout_at, queue_priority, queue_attempt_count, created_at)
        select 'rex_cap' || lpad(g::text, 8, '0'),
               (select id from workflow_runs where public_id = ?),
               'investigation', 'queued', 1,
               now(), now() + interval '10 minutes', 100, 0, now()
          from generate_series(1, ?) g
        """,
        runId,
        count);
  }
}
