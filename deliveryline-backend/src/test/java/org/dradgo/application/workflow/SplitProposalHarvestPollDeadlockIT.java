package org.dradgo.application.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.dradgo.TestcontainersConfiguration;
import org.dradgo.application.runner.spi.RunnerExecutionRecordPort;
import org.dradgo.domain.id.PublicIdPrefixes;
import org.dradgo.domain.registry.DataClassification;
import org.dradgo.domain.registry.RunnerExecutionStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Regression IT for the 2026-07-01 split-proposal harvest self-deadlock.
 *
 * <p>The production poll path ({@code RunnerBroker.pollActiveExecutions} → {@code
 * perItemTransactionTemplate}) captures the completed container's raw output ({@code
 * RunnerExecutionService.recordRawOutput}, a pessimistic {@code FOR UPDATE} on the {@code
 * runner_executions} row) and THEN harvests the result. {@link SplitProposalHarvester} (and its
 * sibling {@code ReviewResultHarvester}) finalize the SAME row in a {@code REQUIRES_NEW}
 * transaction — a second pooled connection. If {@code recordRawOutput} held its row lock inside the
 * ambient poll tx (it was {@code @Transactional}/REQUIRED), the harvester's re-lock on the second
 * connection self-deadlocked on it and hung forever, wedging the single {@code @Scheduled} thread
 * so the timeout/stale reaper could never run.
 *
 * <p>This test reproduces that exact interleaving on a real Postgres: an ambient transaction that
 * captures raw output and then harvests a (degrading) split result MUST complete and mark the
 * reviewer execution {@code FAILED}, not block. It guards {@code
 * RunnerExecutionPersistenceAdapter.recordRawOutput} staying {@code PROPAGATION_REQUIRES_NEW}
 * (reverting it to REQUIRED makes this test time out). Only a real Postgres is needed (row-level
 * pessimistic locking); NOT a {@code docker-runner-it}.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles({"test", "linear-mock"})
@Tag("integration")
class SplitProposalHarvestPollDeadlockIT {

  @Autowired private SplitProposalHarvester harvester;
  @Autowired private RunnerExecutionRecordPort recordPort;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private JdbcTemplate jdbcTemplate;

  @AfterEach
  void tearDown() {
    jdbcTemplate.update("delete from split_proposals");
    jdbcTemplate.update("delete from runner_executions");
    jdbcTemplate.update("delete from workflow_events");
    jdbcTemplate.update("delete from workflow_runs");
  }

  @Test
  void harvestUnderAmbientCaptureLockCompletesInsteadOfSelfDeadlocking() {
    String runId = seedRun();
    String rex = seedRunningSplitReview(runId, "rex_splitdeadlk01");
    // An invalid split-proposal payload takes the earliest degrade branch (contract validation →
    // recordFailed) without needing a resolvable reviewed artifact/identity — the degrade's
    // REQUIRES_NEW recordFailed is exactly the re-lock that used to deadlock.
    byte[] invalidPayload = "{\"schemaVersion\":1}".getBytes(StandardCharsets.UTF_8);

    TransactionTemplate ambient = new TransactionTemplate(transactionManager);

    assertTimeoutPreemptively(
        Duration.ofSeconds(30),
        () ->
            ambient.executeWithoutResult(
                status -> {
                  // Ambient poll tx captures the completed container's raw output (pessimistic FOR
                  // UPDATE on the rex row) — pre-fix this lock was held across the harvest below.
                  recordPort.recordRawOutput(
                      rex, "logs/raw", DataClassification.SHAREABLE_REDACTED, 42L, 0);
                  // Harvest inside the SAME ambient tx → degrade → recordFailed on a REQUIRES_NEW
                  // second connection. Must not block on the capture lock.
                  harvester.harvest(rex, runId, invalidPayload);
                }),
        "split harvest self-deadlocked on the ambient capture lock");

    assertEquals(
        RunnerExecutionStatus.FAILED,
        recordPort.findByPublicId(rex).orElseThrow().status(),
        "the reviewer execution must be finalized FAILED by the degrade path");
  }

  private String seedRun() {
    String runId = PublicIdPrefixes.WORKFLOW_RUN.next();
    jdbcTemplate.update(
        "insert into workflow_runs (public_id, current_state) values (?, 'WaitingForSpecApproval')",
        runId);
    return runId;
  }

  private String seedRunningSplitReview(String runId, String rexPublicId) {
    jdbcTemplate.update(
        """
        insert into runner_executions
          (public_id, workflow_run_id, stage, status, context_bundle_version,
           last_activity_at, timeout_at, queue_priority, queue_attempt_count, created_at,
           worker_id, dispatched_at, idempotency_key)
        values (?, (select id from workflow_runs where public_id = ?), 'review', 'running', 1,
                now(), now() + interval '10 minutes', 100, 1, now(),
                'runner-worker-test-0', now(), ?)
        """,
        rexPublicId,
        runId,
        SplitProposalService.splitDispatchKey(runId, 1));
    return rexPublicId;
  }
}
