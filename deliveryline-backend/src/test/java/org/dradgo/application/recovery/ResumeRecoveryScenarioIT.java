package org.dradgo.application.recovery;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.dradgo.TestcontainersConfiguration;
import org.dradgo.application.artifact.ActorContext;
import org.dradgo.domain.id.PublicIdPrefixes;
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.WorkflowState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Story 4.25 (AC2a) — recovery-integration CI-tier SCENARIO: the resume recovery path end-to-end
 * over the REAL transition table, guards and runner queue on Testcontainers Postgres. Composes the
 * existing {@link RecoveryService#pause}/{@link RecoveryService#resume} primitives (stories 4.8 /
 * 4.5) into the named fault-recovery scenario the epic AC2(a) demands: an {@code Executing} run
 * with in-flight + queued runner work is paused (asserting the run parks at {@code Paused} and the
 * cancelled-runner counts split queued-vs-in-flight), then resumed (asserting the run returns to
 * its typed prior state and the cancelled rows are gone from the dispatch paths).
 *
 * <p>Adds nothing new — the injection primitive here is simply seeding runner rows + driving the
 * services synchronously. Auto-dispatch is OFF in the shared test profile, so resume's re-dispatch
 * branch no-ops returning {@code null} (OQ-2: this PR tier asserts re-enqueue-absence + prior-state
 * return, the LIGHT depth; full "continues to completion" needs a live mock runner and is a nightly
 * candidate). Template: {@link PauseResumeRoundTripIT} + {@link RecoveryServicePauseIT}.
 *
 * <p>Per-scenario isolation (AC9): a fresh {@link TempDir} {@code deliveryline.home} + a per-class
 * Testcontainers Postgres (fresh Flyway schema). Tagged {@code @Tag("recovery-integration")} so it
 * runs ONLY in the dedicated CI tier ({@code -Precovery-integration}), never double-run by
 * backend-contract-tests.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles({"test", "linear-mock", "github-mock"})
@Tag("recovery-integration")
class ResumeRecoveryScenarioIT {

  private static final String REASON = "operator investigating runner drift";

  @TempDir static Path deliverylineHome;

  @DynamicPropertySource
  static void deliverylineHome(DynamicPropertyRegistry registry) {
    registry.add("deliveryline.home", () -> deliverylineHome.toString());
  }

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

  @Test
  void executingRunWithRunnerWorkPausesWithSplitCountsThenResumesToPriorState() {
    String runId = seedRunInState(WorkflowState.EXECUTING);
    insertRunner(runId, "queued");
    insertRunner(runId, "running");
    ActorContext actor = new ActorContext("alex", ActorType.HUMAN, "corr-resume-scenario");

    // --- pause: run parks at Paused, in-flight + queued runner work is cancelled, counts split ---
    PauseRecoveryResult paused =
        recoveryService.pause(runId, "idem-scenario-pause-0001", actor, REASON);
    assertThat(paused.replayed()).isFalse();
    assertThat(paused.priorState()).isEqualTo(WorkflowState.EXECUTING);
    assertThat(paused.resultingState()).isEqualTo(WorkflowState.PAUSED);
    assertThat(paused.cancelledQueuedCount()).isEqualTo(1);
    assertThat(paused.cancelledInFlightCount()).isEqualTo(1);
    assertThat(currentState(runId)).isEqualTo("Paused");
    // Both runner rows flipped to the terminal cancelled-for-pause status (V43 biconditional).
    assertThat(activeRunnerCount(runId)).isZero();

    // --- resume: run returns to its typed prior Executing state; no re-enqueue (auto-dispatch off,
    // OQ-2 light depth) so the run continues from the same runner-less Executing state.
    ResumeRecoveryResult resumed =
        recoveryService.resume(runId, "idem-scenario-resume-0001", actor, null);
    assertThat(resumed.replayed()).isFalse();
    assertThat(resumed.resultingState()).isEqualTo(WorkflowState.EXECUTING);
    assertThat(resumed.newRunnerExecutionPublicId()).isNull();
    assertThat(currentState(runId)).isEqualTo("Executing");

    // recovery_actions carries the audit anchor for BOTH governed actions.
    assertThat(recoveryActionCount(runId, "pause")).isEqualTo(1);
    assertThat(recoveryActionCount(runId, "resume")).isEqualTo(1);
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

  private int activeRunnerCount(String runId) {
    Integer count =
        jdbcTemplate.queryForObject(
            "select count(*) from runner_executions where status in ('queued', 'pending', 'running')"
                + " and workflow_run_id = (select id from workflow_runs where public_id = ?)",
            Integer.class,
            runId);
    return count == null ? 0 : count;
  }

  private int recoveryActionCount(String runId, String actionType) {
    Integer count =
        jdbcTemplate.queryForObject(
            "select count(*) from recovery_actions where action_type = ?"
                + " and workflow_run_id = (select id from workflow_runs where public_id = ?)",
            Integer.class,
            actionType,
            runId);
    return count == null ? 0 : count;
  }
}
