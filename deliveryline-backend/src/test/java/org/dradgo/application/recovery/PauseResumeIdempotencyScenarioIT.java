package org.dradgo.application.recovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import org.dradgo.TestcontainersConfiguration;
import org.dradgo.application.artifact.ActorContext;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.id.PublicIdPrefixes;
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.DomainErrorCode;
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
 * Story 4.25 (AC2d) — recovery-integration CI-tier SCENARIO: idempotency determinism of the pause
 * recovery path against the REAL {@code idempotency_records} + {@code recovery_actions} tables on
 * Testcontainers Postgres. Composes the existing {@link RecoveryService#pause}/{@link
 * RecoveryService#resume} primitives to prove the two governed idempotency contracts end-to-end:
 *
 * <ul>
 *   <li><b>same-key replay is deterministic</b> — a second {@code pause} under the SAME key REPLAYS
 *       (no second {@code recovery_actions} row, no second {@code recovery.paused} event, {@code
 *       replayed=true}, same recovery-action id);
 *   <li><b>same-key cross-action is a conflict</b> — a {@code resume} under the pause's key raises
 *       {@code IDEMPOTENCY_KEY_CONFLICT} (the replay match is SAME run + SAME {@code action_type} +
 *       {@code succeeded}; a different action_type collides), carrying {@code
 *       priorActionType='pause'} in the exception details.
 * </ul>
 *
 * <p>Per-scenario isolation (AC9): fresh {@link TempDir} {@code deliveryline.home} + per-class
 * Testcontainers Postgres. Tagged {@code @Tag("recovery-integration")}. See
 * [[transition-idempotencykey-is-not-a-dedupe-key]].
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles({"test", "linear-mock", "github-mock"})
@Tag("recovery-integration")
class PauseResumeIdempotencyScenarioIT {

  private static final String REASON = "operator investigating";
  private static final String KEY = "idem-scenario-idem-00000001";

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
  void sameKeyPauseReplaysDeterministicallyAndCrossActionResumeConflicts() {
    String runId = seedRunInState(WorkflowState.EXECUTING);
    insertQueuedRunner(runId);
    ActorContext actor = new ActorContext("alex", ActorType.HUMAN, "corr-idem-scenario");

    // --- same-key pause replay: deterministic, no second row/event ---
    PauseRecoveryResult first = recoveryService.pause(runId, KEY, actor, REASON);
    PauseRecoveryResult replay = recoveryService.pause(runId, KEY, actor, REASON);

    assertThat(first.replayed()).isFalse();
    assertThat(replay.replayed()).isTrue();
    assertThat(replay.recoveryActionPublicId()).isEqualTo(first.recoveryActionPublicId());
    // The replay cancels nothing a second time (the rows are already terminal).
    assertThat(replay.cancelledQueuedCount()).isZero();
    assertThat(replay.cancelledInFlightCount()).isZero();
    assertThat(pauseActionCount()).isEqualTo(1);
    assertThat(pausedEventCount()).isEqualTo(1);

    // --- same-key cross-action resume: conflict (different action_type under the pause's key) ---
    assertThatThrownBy(() -> recoveryService.resume(runId, KEY, actor, null))
        .isInstanceOfSatisfying(
            DomainException.class,
            e -> {
              assertThat(e.errorCode()).isEqualTo(DomainErrorCode.IDEMPOTENCY_KEY_CONFLICT);
              assertThat(e.details()).containsEntry("priorActionType", "pause");
            });
    // The run stayed Paused — the conflicting resume was rejected before any transition.
    assertThat(currentState(runId)).isEqualTo("Paused");
    assertThat(pauseActionCount()).isEqualTo(1);
  }

  private String seedRunInState(WorkflowState state) {
    String runId = PublicIdPrefixes.WORKFLOW_RUN.next();
    jdbcTemplate.update(
        "insert into workflow_runs (public_id, current_state) values (?, ?)", runId, state.value());
    return runId;
  }

  private void insertQueuedRunner(String runId) {
    jdbcTemplate.update(
        """
        insert into runner_executions
          (public_id, workflow_run_id, stage, status, context_bundle_version,
           last_activity_at, timeout_at, queue_priority, queue_attempt_count, created_at)
        values (?, (select id from workflow_runs where public_id = ?),
                'execution', 'queued', 1, now(), now() + interval '10 minutes', 100, 0, now())
        """,
        PublicIdPrefixes.RUNNER_EXECUTION.next(),
        runId);
  }

  private String currentState(String runId) {
    return jdbcTemplate.queryForObject(
        "select current_state from workflow_runs where public_id = ?", String.class, runId);
  }

  private int pauseActionCount() {
    Integer count =
        jdbcTemplate.queryForObject(
            "select count(*) from recovery_actions where action_type = 'pause'", Integer.class);
    return count == null ? 0 : count;
  }

  private int pausedEventCount() {
    Integer count =
        jdbcTemplate.queryForObject(
            "select count(*) from workflow_events where event_type = 'recovery.paused'",
            Integer.class);
    return count == null ? 0 : count;
  }
}
