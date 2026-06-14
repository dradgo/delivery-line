package org.dradgo.application.runner.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import org.dradgo.TestcontainersConfiguration;
import org.dradgo.application.runner.RunnerBroker;
import org.dradgo.application.runner.spi.RunnerExecutionRecordPort;
import org.dradgo.application.runner.spi.RunnerExecutionSnapshot;
import org.dradgo.domain.id.PublicIdPrefixes;
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
 * Story 3.17b (AC5 / D6) — Testcontainers Postgres coverage of worker-crash lease reclamation. A
 * worker thread that dies mid-dispatch leaves a LEASED ({@code worker_id} set, {@code
 * dispatched_at} stamped) {@code running} row whose lease ages past the orphan threshold; {@code
 * scanForStaleExecutions}'s leased predicate routes it through the existing {@code recordOrphaned}
 * + {@code runner.orphaned} path. A worker-less {@code queued} row is NEVER reclaimed — it is
 * correctly waiting and stays enqueueable.
 *
 * <p>The worker pool is OFF here ({@code worker-pool.enabled=false}, the test default) so the scan,
 * not a live worker, drives the assertions deterministically. NOT {@code @Tag("docker-runner-it")}
 * — only a real Postgres is needed (Trap T7).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles({"test", "linear-mock"})
@Tag("integration")
class RunnerLeasedReclamationIT {

  @Autowired private RunnerBroker broker;
  @Autowired private RunnerExecutionRecordPort recordPort;
  @Autowired private JdbcTemplate jdbcTemplate;

  @AfterEach
  void tearDown() {
    jdbcTemplate.update("delete from runner_executions");
    jdbcTemplate.update("delete from workflow_events");
    jdbcTemplate.update("delete from workflow_runs");
  }

  @Test
  void scanReclaimsAStalledLeaseToOrphanedButLeavesQueuedRowsUntouched() {
    String runId = seedRun();
    // A leased-but-stalled RUNNING row: worker_id set, dispatched_at + last_activity_at well past
    // the
    // orphan threshold (INVESTIGATION timeout 600s x 2.0 = 1200s); timeout_at kept in the future so
    // the separate timeout scan does not claim it first — only the lease/last-activity path can.
    String leasedRex = seedLeasedRunningRow(runId, "rex_leasestale01", 3600);
    // A worker-LESS queued row, also "old", must stay queued (D6) — it is correctly waiting.
    String queuedRex = seedQueuedRow(runId, "rex_queuedwait01", 3600);

    int flips = broker.scanForStaleExecutions();

    assertTrue(flips >= 1, "the stalled lease must be reclaimed");
    assertEquals(
        RunnerExecutionStatus.ORPHANED,
        recordPort.findByPublicId(leasedRex).orElseThrow().status(),
        "a stalled leased row is reclaimed to orphaned");
    assertEquals(
        RunnerExecutionStatus.QUEUED,
        recordPort.findByPublicId(queuedRex).orElseThrow().status(),
        "a worker-less queued row is never reclaimed (D6)");
  }

  @Test
  void leasedStaleFinderReturnsTheStalledLeaseAndNotTheQueuedRow() {
    String runId = seedRun();
    String leasedRex = seedLeasedRunningRow(runId, "rex_leasestale02", 3600);
    seedQueuedRow(runId, "rex_queuedwait02", 3600);
    // A FRESH lease (just dispatched) must NOT be returned — it is a healthy in-flight execution.
    seedLeasedRunningRow(runId, "rex_leasefresh02", 5);

    List<RunnerExecutionSnapshot> stalled =
        recordPort.findLeasedStaleByStageAndDispatchedAtBefore(
            RunnerStage.INVESTIGATION, Duration.ofSeconds(1200), 50);

    assertEquals(1, stalled.size(), "only the stalled lease is returned");
    assertEquals(leasedRex, stalled.get(0).publicId());
  }

  private String seedRun() {
    String runId = PublicIdPrefixes.WORKFLOW_RUN.next();
    jdbcTemplate.update(
        "insert into workflow_runs (public_id, current_state) values (?, 'Executing')", runId);
    return runId;
  }

  private String seedLeasedRunningRow(String runId, String rexPublicId, long ageSeconds) {
    jdbcTemplate.update(
        """
        insert into runner_executions
          (public_id, workflow_run_id, stage, status, context_bundle_version,
           last_activity_at, timeout_at, queue_priority, queue_attempt_count, created_at,
           worker_id, dispatched_at)
        values (?, (select id from workflow_runs where public_id = ?), 'investigation', 'running', 1,
                now() - make_interval(secs => ?), now() + interval '10 minutes', 100, 1,
                now() - make_interval(secs => ?), 'runner-worker-dead-0', now() - make_interval(secs => ?))
        """,
        rexPublicId,
        runId,
        (double) ageSeconds,
        (double) ageSeconds,
        (double) ageSeconds);
    return rexPublicId;
  }

  private String seedQueuedRow(String runId, String rexPublicId, long ageSeconds) {
    jdbcTemplate.update(
        """
        insert into runner_executions
          (public_id, workflow_run_id, stage, status, context_bundle_version,
           last_activity_at, timeout_at, queue_priority, queue_attempt_count, created_at)
        values (?, (select id from workflow_runs where public_id = ?), 'investigation', 'queued', 1,
                now() - make_interval(secs => ?), now() + interval '10 minutes', 100, 0,
                now() - make_interval(secs => ?))
        """,
        rexPublicId,
        runId,
        (double) ageSeconds,
        (double) ageSeconds);
    return rexPublicId;
  }
}
