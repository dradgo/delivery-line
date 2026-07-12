package org.dradgo.application.recovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.List;
import org.dradgo.TestcontainersConfiguration;
import org.dradgo.application.artifact.ActorContext;
import org.dradgo.application.recovery.spi.RecoveryActionRecordPort;
import org.dradgo.application.recovery.spi.RecoveryActionSnapshot;
import org.dradgo.application.runner.queue.RunnerExecutionQueue;
import org.dradgo.application.runner.spi.RunnerExecutionRecordPort;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.id.PublicIdPrefixes;
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.RunnerExecutionStatus;
import org.dradgo.domain.registry.WorkflowState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Story 4.8 (AC11) — Testcontainers Postgres coverage of {@link RecoveryService#pause} end-to-end
 * over real schema/state-machine/transition wiring (V43 CHECKs included). Seeds an {@code
 * Executing} run with a {@code queued} + a {@code running} runner row; drives the pause; asserts
 * the run is {@code Paused}, both rows flip to {@code cancelled_for_pause} (stamping {@code
 * completed_at} — the V43 biconditional), the counts split queued-vs-in-flight, the {@code
 * recovery.paused} event + the {@code recovery_actions} row (action_type=pause,
 * reviewer_role=workflow_owner) reach {@code succeeded}, AND — the load-bearing invariant — the
 * cancelled rows are invisible to both dispatch paths: {@code dequeueNext} (leases {@code
 * status='queued'}) and the broker's stale/timeout scans ({@code ACTIVE_STATUSES = {pending,
 * running}}). Then idempotent replay (single recovery row) and the non-pausable rejection.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles({"test", "linear-mock"})
@Tag("integration")
class RecoveryServicePauseIT {

  private static final String IDEMPOTENCY_KEY = "idem-pause-it-00000001";
  private static final String REASON = "operator investigating runner config drift";

  @Autowired private RecoveryService recoveryService;
  @Autowired private RecoveryActionRecordPort recoveryActionRecordPort;
  @Autowired private RunnerExecutionRecordPort runnerExecutionRecordPort;
  @Autowired private RunnerExecutionQueue runnerExecutionQueue;
  @Autowired private JdbcTemplate jdbcTemplate;

  @AfterEach
  void tearDown() {
    jdbcTemplate.update("delete from recovery_actions");
    jdbcTemplate.update("delete from idempotency_records");
    jdbcTemplate.update("delete from runner_executions");
    jdbcTemplate.update("delete from workflow_events");
    jdbcTemplate.update("delete from workflow_runs");
  }

  @Test
  void pauseTransitionsCancelsRunnersRecordsAuditAndHidesRowsFromBothDispatchPaths() {
    String runId = seedRunInState(WorkflowState.EXECUTING);
    String queuedRex = insertRunner(runId, "queued");
    String runningRex = insertRunner(runId, "running");
    // Age the rows so BOTH broker scans below WOULD pick an active row up — proving the
    // post-pause emptiness comes from the status flip, not from the window.
    jdbcTemplate.update(
        "update runner_executions set last_activity_at = now() - interval '1 hour',"
            + " timeout_at = now() - interval '1 hour'"
            + " where public_id in (?, ?)",
        queuedRex,
        runningRex);
    assertThat(
            runnerExecutionRecordPort.findStaleByStatusInAndLastActivityAtBefore(
                List.of(RunnerExecutionStatus.PENDING, RunnerExecutionStatus.RUNNING),
                Duration.ofMinutes(10),
                10))
        .anySatisfy(row -> assertThat(row.publicId()).isEqualTo(runningRex));
    // ...and the scanForTimeouts input query (findStaleByStatusInAndTimeoutAtBefore with
    // ACTIVE_STATUSES + Duration.ZERO — exactly what RunnerBroker.scanForTimeouts issues).
    assertThat(
            runnerExecutionRecordPort.findStaleByStatusInAndTimeoutAtBefore(
                List.of(RunnerExecutionStatus.PENDING, RunnerExecutionStatus.RUNNING),
                Duration.ZERO,
                10))
        .anySatisfy(row -> assertThat(row.publicId()).isEqualTo(runningRex));

    PauseRecoveryResult result = recoveryService.pause(runId, IDEMPOTENCY_KEY, actor(), REASON);

    assertThat(result.replayed()).isFalse();
    assertThat(result.priorState()).isEqualTo(WorkflowState.EXECUTING);
    assertThat(result.resultingState()).isEqualTo(WorkflowState.PAUSED);
    assertThat(result.cancelledQueuedCount()).isEqualTo(1);
    assertThat(result.cancelledInFlightCount()).isEqualTo(1);
    assertThat(result.recoveryActionPublicId()).startsWith("rcv_");
    assertThat(result.pausedEventPublicId()).startsWith("evt_");

    // Run is Paused; the transition event carries the TYPED priorState resume (4.5) reads.
    assertThat(currentState(runId)).isEqualTo("Paused");
    String typedPriorState =
        jdbcTemplate.queryForObject(
            "select prior_state from workflow_events where workflow_run_id ="
                + " (select id from workflow_runs where public_id = ?)"
                + " and event_type = 'workflow.stateChanged' and resulting_state = 'Paused'"
                + " order by id desc limit 1",
            String.class,
            runId);
    assertThat(typedPriorState).isEqualTo("Executing");

    // Both rows flipped to the terminal pause status, stamping completed_at (V43 biconditional).
    assertThat(runnerStatus(queuedRex)).isEqualTo("cancelled_for_pause");
    assertThat(runnerStatus(runningRex)).isEqualTo("cancelled_for_pause");
    Integer unstamped =
        jdbcTemplate.queryForObject(
            "select count(*) from runner_executions where public_id in (?, ?)"
                + " and completed_at is null",
            Integer.class,
            queuedRex,
            runningRex);
    assertThat(unstamped).isZero();

    // The authoritative stop-dispatch signal holds: the cancelled queued row is NOT leasable...
    assertThat(runnerExecutionQueue.dequeue("it-pause-worker")).isEmpty();
    // ...and the cancelled in-flight row is invisible to the broker's ACTIVE_STATUSES scans.
    assertThat(
            runnerExecutionRecordPort.findStaleByStatusInAndLastActivityAtBefore(
                List.of(RunnerExecutionStatus.PENDING, RunnerExecutionStatus.RUNNING),
                Duration.ofMinutes(10),
                10))
        .isEmpty();
    // AC11 — including the scanForTimeouts path directly: the cancelled rows (timeout_at long
    // past) are never returned to the timeout reaper.
    assertThat(
            runnerExecutionRecordPort.findStaleByStatusInAndTimeoutAtBefore(
                List.of(RunnerExecutionStatus.PENDING, RunnerExecutionStatus.RUNNING),
                Duration.ZERO,
                10))
        .isEmpty();

    // recovery.paused audit anchor appended with typed prior/resulting states.
    Integer pausedEvents =
        jdbcTemplate.queryForObject(
            "select count(*) from workflow_events where workflow_run_id ="
                + " (select id from workflow_runs where public_id = ?)"
                + " and event_type = 'recovery.paused'"
                + " and prior_state = 'Executing' and resulting_state = 'Paused'"
                + " and intervention_marker = true",
            Integer.class,
            runId);
    assertThat(pausedEvents).isEqualTo(1);

    // recovery_actions row: pre-reserved V1 'pause' slot, workflow_owner attribution, succeeded.
    RecoveryActionSnapshot action =
        recoveryActionRecordPort.findByIdempotencyKey(IDEMPOTENCY_KEY).orElseThrow();
    assertThat(action.actionType()).isEqualTo("pause");
    assertThat(action.reviewerRole()).isEqualTo("workflow_owner");
    assertThat(action.resultStatus()).isEqualTo("succeeded");
    assertThat(action.resultingEventPublicId()).isEqualTo(result.pausedEventPublicId());
  }

  @Test
  void pauseReplaysOnSameKeyWithoutASecondRowOrSecondCancellation() {
    String runId = seedRunInState(WorkflowState.EXECUTING);
    insertRunner(runId, "queued");

    PauseRecoveryResult first = recoveryService.pause(runId, IDEMPOTENCY_KEY, actor(), REASON);
    PauseRecoveryResult replay = recoveryService.pause(runId, IDEMPOTENCY_KEY, actor(), REASON);

    assertThat(first.replayed()).isFalse();
    assertThat(replay.replayed()).isTrue();
    assertThat(replay.recoveryActionPublicId()).isEqualTo(first.recoveryActionPublicId());
    assertThat(replay.priorState()).isEqualTo(WorkflowState.EXECUTING);
    assertThat(replay.cancelledQueuedCount()).isZero();
    assertThat(replay.cancelledInFlightCount()).isZero();

    Integer actionRows =
        jdbcTemplate.queryForObject(
            "select count(*) from recovery_actions where action_type = 'pause'", Integer.class);
    assertThat(actionRows).isEqualTo(1);
    Integer pausedEvents =
        jdbcTemplate.queryForObject(
            "select count(*) from workflow_events where event_type = 'recovery.paused'",
            Integer.class);
    assertThat(pausedEvents).isEqualTo(1);
  }

  @Test
  void pauseFromNonPausableStateIsRejectedWithoutSideEffects() {
    String runId = seedRunInState(WorkflowState.WAITING_FOR_DEPENDENCIES);
    String key = "idem-pause-it-rejected1";

    DomainException error =
        assertThrows(
            DomainException.class, () -> recoveryService.pause(runId, key, actor(), REASON));

    assertThat(error.errorCode()).isEqualTo(DomainErrorCode.PAUSE_NOT_APPLICABLE);
    assertThat(recoveryActionRecordPort.findByIdempotencyKey(key)).isEmpty();
    assertThat(currentState(runId)).isEqualTo("WaitingForDependencies");
  }

  // ----- seed helpers (mirror DeveloperTakeoverServiceIT) -----

  private static ActorContext actor() {
    return new ActorContext("alex", ActorType.HUMAN, "corr-pause-it");
  }

  private String seedRunInState(WorkflowState state) {
    String runId = PublicIdPrefixes.WORKFLOW_RUN.next();
    jdbcTemplate.update(
        "insert into workflow_runs (public_id, current_state) values (?, ?)", runId, state.value());
    return runId;
  }

  private String insertRunner(String runId, String status) {
    String publicId = PublicIdPrefixes.RUNNER_EXECUTION.next();
    jdbcTemplate.update(
        """
        insert into runner_executions
          (public_id, workflow_run_id, stage, status, context_bundle_version,
           last_activity_at, timeout_at, queue_priority, queue_attempt_count, created_at)
        values (?, (select id from workflow_runs where public_id = ?),
                'execution', ?, 1, now(), now() + interval '10 minutes', 100, 0, now())
        """,
        publicId,
        runId,
        status);
    return publicId;
  }

  private String currentState(String runId) {
    return jdbcTemplate.queryForObject(
        "select current_state from workflow_runs where public_id = ?", String.class, runId);
  }

  private String runnerStatus(String publicId) {
    return jdbcTemplate.queryForObject(
        "select status from runner_executions where public_id = ?", String.class, publicId);
  }
}
