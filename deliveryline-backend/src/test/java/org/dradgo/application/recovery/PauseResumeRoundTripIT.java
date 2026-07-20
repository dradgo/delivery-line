package org.dradgo.application.recovery;

import static org.assertj.core.api.Assertions.assertThat;

import org.dradgo.TestcontainersConfiguration;
import org.dradgo.application.artifact.ActorContext;
import org.dradgo.domain.id.PublicIdPrefixes;
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.WorkflowState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Story 4.8 (AC11) — THE ROUND-TRIP that proves Reconciliation 2 (the pause symmetry invariant)
 * against the REAL transition table, transition service, and guards on real Postgres: for EACH of
 * the 8 pausable source states, {@code pause → assert Paused (typed priorState recorded) → resume →
 * assert back at the source state}. This is the test 4.5's deferred review finding demanded — every
 * recorded {@code priorState} is a legal {@code Paused →} target by construction, so resume never
 * surfaces a raw {@code ILLEGAL_TRANSITION}.
 *
 * <p>It also exercises 4.5's previously-unreachable resume re-dispatch branches for {@code
 * EXECUTING} ({@code redispatchAfterRetry}) and {@code INVESTIGATING} ({@code retrySpecGeneration},
 * coded defensively in 4.5 and NEVER executed until pause made an {@code Investigating → Paused}
 * edge exist). Auto-dispatch is off in the shared test profile, so both branches no-op returning
 * {@code null} — the branch executes without error and the bare transition stays observable ({@code
 * newRunnerExecutionPublicId == null}); the branch-selection-per-priorState contract is pinned at
 * unit level in {@code RecoveryServiceResumeTest}. The six gate/failed states carry no runner work
 * — resume just transitions back.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles({"test", "linear-mock"})
@Tag("integration")
class PauseResumeRoundTripIT {

  private static final String REASON = "round-trip investigation";

  @Autowired private RecoveryService recoveryService;
  @Autowired private JdbcTemplate jdbcTemplate;

  @AfterEach
  void tearDown() {
    jdbcTemplate.update("delete from recovery_actions");
    jdbcTemplate.update("delete from idempotency_records");
    jdbcTemplate.update("delete from runner_executions");
    jdbcTemplate.update("delete from workflow_events");
    jdbcTemplate.update("delete from workflow_runs");
  }

  @ParameterizedTest
  @EnumSource(
      value = WorkflowState.class,
      names = {
        "INVESTIGATING",
        "WAITING_FOR_SPEC_APPROVAL",
        "EXECUTING",
        "WAITING_FOR_REVIEW",
        "WAITING_FOR_MANUAL_EXECUTION",
        "WAITING_FOR_LINT_APPROVAL",
        "WAITING_FOR_DELIVERY",
        "FAILED"
      })
  void pauseThenResumeRoundTripsEveryPausableState(WorkflowState source) {
    String runId = seedRunInState(source);
    // Key suffix uses the PascalCase value — the idempotency-key pattern is [A-Za-z0-9-]{16,128}.
    String pauseKey = "idem-roundtrip-pause-" + source.value();
    String resumeKey = "idem-roundtrip-resume-" + source.value();
    ActorContext actor = new ActorContext("alex", ActorType.HUMAN, "corr-roundtrip");

    // --- pause ---
    PauseRecoveryResult paused = recoveryService.pause(runId, pauseKey, actor, REASON);
    assertThat(paused.replayed()).isFalse();
    assertThat(paused.priorState()).isEqualTo(source);
    assertThat(paused.resultingState()).isEqualTo(WorkflowState.PAUSED);
    assertThat(currentState(runId)).isEqualTo("Paused");

    // The transition stamped the TYPED priorState resume reads (Reconciliation 6 — no detail key).
    String typedPriorState =
        jdbcTemplate.queryForObject(
            "select prior_state from workflow_events where workflow_run_id ="
                + " (select id from workflow_runs where public_id = ?)"
                + " and event_type = 'workflow.stateChanged' and resulting_state = 'Paused'"
                + " order by id desc limit 1",
            String.class,
            runId);
    assertThat(typedPriorState).isEqualTo(source.value());

    // --- resume — transitions BACK to the recorded source state, never ILLEGAL_TRANSITION ---
    ResumeRecoveryResult resumed = recoveryService.resume(runId, resumeKey, actor, null);
    assertThat(resumed.replayed()).isFalse();
    assertThat(resumed.resultingState()).isEqualTo(source);
    assertThat(currentState(runId)).isEqualTo(source.value());
    // Auto-dispatch is off in the test profile: the EXECUTING/INVESTIGATING re-dispatch branches
    // no-op returning null (the branch ran without error); gate/failed states dispatch nothing.
    assertThat(resumed.newRunnerExecutionPublicId()).isNull();
  }

  @Test
  void pausePreservesTheAwaitingManualParkAndResumeFindsItIntact() {
    // Story 4.8 (Reconciliation 5) — the awaiting_manual park survives the round-trip on REAL
    // Postgres: pause scans only {queued, pending, running} (PAUSE_CANCEL_SCAN_STATUSES), so the
    // parked row is NOT flipped to cancelled_for_pause (a cancelled park has no re-park path on
    // resume) and completed_at stays null (non-terminal — V43 biconditional). Resume from
    // priorState=WaitingForManualExecution dispatches nothing and finds the park exactly as pause
    // left it.
    String runId = seedRunInState(WorkflowState.WAITING_FOR_MANUAL_EXECUTION);
    String parkedRex = insertAwaitingManualRunner(runId);
    ActorContext actor = new ActorContext("alex", ActorType.HUMAN, "corr-roundtrip");

    // --- pause — the park is untouched, and nothing was cancelled ---
    PauseRecoveryResult paused =
        recoveryService.pause(runId, "idem-roundtrip-pause-manual-park", actor, REASON);
    assertThat(paused.replayed()).isFalse();
    assertThat(paused.priorState()).isEqualTo(WorkflowState.WAITING_FOR_MANUAL_EXECUTION);
    assertThat(paused.resultingState()).isEqualTo(WorkflowState.PAUSED);
    assertThat(paused.cancelledQueuedCount()).isZero();
    assertThat(paused.cancelledInFlightCount()).isZero();
    assertThat(currentState(runId)).isEqualTo("Paused");
    assertThat(runnerStatus(parkedRex)).isEqualTo("awaiting_manual");
    assertThat(runnerCompletedAt(parkedRex)).isNull();

    // --- resume — back at WaitingForManualExecution with the SAME single park intact ---
    ResumeRecoveryResult resumed =
        recoveryService.resume(runId, "idem-roundtrip-resume-manual-park", actor, null);
    assertThat(resumed.replayed()).isFalse();
    assertThat(resumed.resultingState()).isEqualTo(WorkflowState.WAITING_FOR_MANUAL_EXECUTION);
    assertThat(resumed.newRunnerExecutionPublicId()).isNull();
    assertThat(currentState(runId)).isEqualTo("WaitingForManualExecution");
    assertThat(runnerStatus(parkedRex)).isEqualTo("awaiting_manual");
    assertThat(runnerCompletedAt(parkedRex)).isNull();
    // No second row was minted anywhere in the round-trip — the original park is the run's only
    // runner execution.
    Integer runnerRows =
        jdbcTemplate.queryForObject(
            "select count(*) from runner_executions where workflow_run_id ="
                + " (select id from workflow_runs where public_id = ?)",
            Integer.class,
            runId);
    assertThat(runnerRows).isEqualTo(1);
  }

  private String seedRunInState(WorkflowState state) {
    String runId = PublicIdPrefixes.WORKFLOW_RUN.next();
    jdbcTemplate.update(
        "insert into workflow_runs (public_id, current_state) values (?, ?)", runId, state.value());
    return runId;
  }

  private String currentState(String runId) {
    return jdbcTemplate.queryForObject(
        "select current_state from workflow_runs where public_id = ?", String.class, runId);
  }

  // Mirrors RecoveryServicePauseIT.insertRunner, parked variant: status awaiting_manual,
  // completed_at null (non-terminal), no container/lease/worker columns — the 3d-3 park shape.
  private String insertAwaitingManualRunner(String runId) {
    String publicId = PublicIdPrefixes.RUNNER_EXECUTION.next();
    jdbcTemplate.update(
        """
        insert into runner_executions
          (public_id, workflow_run_id, stage, status, context_bundle_version,
           last_activity_at, timeout_at, queue_priority, queue_attempt_count, created_at)
        values (?, (select id from workflow_runs where public_id = ?),
                'execution', 'awaiting_manual', 1, now(), now() + interval '10 minutes',
                100, 0, now())
        """,
        publicId,
        runId);
    return publicId;
  }

  private String runnerStatus(String publicId) {
    return jdbcTemplate.queryForObject(
        "select status from runner_executions where public_id = ?", String.class, publicId);
  }

  private java.time.OffsetDateTime runnerCompletedAt(String publicId) {
    return jdbcTemplate.queryForObject(
        "select completed_at from runner_executions where public_id = ?",
        java.time.OffsetDateTime.class,
        publicId);
  }
}
