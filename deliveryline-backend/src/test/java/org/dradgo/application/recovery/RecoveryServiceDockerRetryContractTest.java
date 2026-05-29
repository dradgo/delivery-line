package org.dradgo.application.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import org.dradgo.TestcontainersConfiguration;
import org.dradgo.adapters.runner.docker.DockerEngineGateway;
import org.dradgo.application.artifact.ActorContext;
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.WorkflowState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Story 3.2a AC10 (story-3.2 AC10 n) + Trap T12/T13 — under {@code runners.docker} (with a mocked
 * {@link DockerEngineGateway} so no real container is created), a {@code retry} against a {@code
 * timed_out} / {@code orphaned} runner row: (a) dispatches a NEW {@code rex_*} row, (b) leaves the
 * original row's status + fields unchanged, and (c) surfaces the {@code runner.dispatched} event id
 * on {@link RetryRecoveryResult#runnerDispatchedEventPublicId()} (NOT the legacy {@code
 * runner.started}, which the docker path does not emit).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles({"test", "linear-mock", "runners.docker"})
@Tag("integration")
class RecoveryServiceDockerRetryContractTest {

  private static final String ACTOR_IDENTITY = "alex";
  private static final String CORRELATION_ID = "corr-docker-retry-1";

  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private RecoveryService recoveryService;

  @MockitoBean private DockerEngineGateway dockerEngineGateway;

  @AfterEach
  void cleanDatabase() {
    jdbcTemplate.update("delete from recovery_actions");
    jdbcTemplate.update("delete from runner_executions");
    jdbcTemplate.update("delete from artifact_operations");
    jdbcTemplate.update("delete from artifacts");
    jdbcTemplate.update("delete from workflow_events");
    jdbcTemplate.update("delete from idempotency_records");
    jdbcTemplate.update("delete from workflow_runs");
  }

  @Test
  void retryFromTimedOutRowDispatchesFreshRunnerAndAnchorsRunnerDispatchedEventId() {
    assertDockerRetryAnchors("run_dockerretry1", "rex_timedout0001", "timed_out", "runner_timeout");
  }

  @Test
  void retryFromOrphanedRowDispatchesFreshRunnerAndAnchorsRunnerDispatchedEventId() {
    assertDockerRetryAnchors("run_dockerretry2", "rex_orphaned00001", "orphaned", "orphan");
  }

  private void assertDockerRetryAnchors(
      String runId, String seededRex, String runnerStatus, String failureCategory) {
    when(dockerEngineGateway.createContainer(any())).thenReturn("container_test_abc123");
    // startContainer is a void mock no-op; the dispatch path needs nothing else from the engine.

    seedFailedRunWithTerminalRunner(
        runId, "evt_failure000001", seededRex, runnerStatus, failureCategory);

    RetryRecoveryResult result =
        recoveryService.retry(
            runId,
            "idem-docker-retry-" + seededRex,
            new ActorContext(ACTOR_IDENTITY, ActorType.HUMAN, CORRELATION_ID),
            "docker retry");

    // (a) a NEW runner_executions row with a distinct rex_* id was dispatched.
    assertFalse(result.replayed(), "fresh docker retry must not report replayed");
    assertNotNull(result.newRunnerExecutionPublicId());
    assertTrue(result.newRunnerExecutionPublicId().startsWith("rex_"));
    assertNotEquals(seededRex, result.newRunnerExecutionPublicId());

    // (b) original row unchanged (status + failure_category preserved verbatim — append-only).
    assertEquals(
        runnerStatus,
        jdbcTemplate.queryForObject(
            "select status from runner_executions where public_id=?", String.class, seededRex));
    assertEquals(
        failureCategory,
        jdbcTemplate.queryForObject(
            "select failure_category from runner_executions where public_id=?",
            String.class,
            seededRex));

    // (c) the retry anchor carries the runner.dispatched event id (docker path), and that event row
    // exists; the legacy runner.started event was NOT emitted on the docker path.
    String anchor = result.runnerDispatchedEventPublicId();
    assertNotNull(anchor, "docker retry must surface the runner.dispatched event id");
    assertTrue(anchor.startsWith("evt_"));
    assertEquals(
        anchor,
        jdbcTemplate.queryForObject(
            "select public_id from workflow_events where public_id=? and event_type='runner.dispatched'",
            String.class,
            anchor));
    Integer startedEvents =
        jdbcTemplate.queryForObject(
            "select count(*) from workflow_events where event_type='runner.started'"
                + " and workflow_run_id=(select id from workflow_runs where public_id=?)",
            Integer.class,
            runId);
    assertEquals(0, startedEvents, "docker path must NOT emit the legacy runner.started event");
    // The recovery.retried anchor stays distinct from the runner.dispatched anchor.
    assertNotEquals(
        result.recoveryRetriedEventPublicId(),
        anchor,
        "runner.dispatched anchor must be distinct from the recovery.retried event id");
  }

  // Seeds Failed run + failure event + a terminal runner_execution + an available spec artifact
  // (ContextBundleService requires >=1 available artifact, else the bundle fails contract
  // validation before dispatch). Mirrors RecoveryServiceAppendOnlyTest's seed recipe.
  private void seedFailedRunWithTerminalRunner(
      String runId,
      String failureEventId,
      String seededRex,
      String runnerStatus,
      String failureCategory) {
    jdbcTemplate.update(
        "insert into workflow_runs (public_id, current_state) values (?, ?)",
        runId,
        WorkflowState.FAILED.value());
    Long workflowRunId =
        jdbcTemplate.queryForObject(
            "select id from workflow_runs where public_id=?", Long.class, runId);
    jdbcTemplate.update(
        """
        insert into workflow_events
          (public_id, workflow_run_id, event_type, actor_identity, actor_type,
           intervention_marker, details, prior_state, resulting_state)
        values (?, ?, 'workflow.stateChanged', 'alex', 'human', false, '{}'::jsonb, 'Executing', 'Failed')
        """,
        failureEventId,
        workflowRunId);
    Long failureEventDbId =
        jdbcTemplate.queryForObject(
            "select id from workflow_events where public_id=?", Long.class, failureEventId);
    jdbcTemplate.update(
        """
        insert into runner_executions
          (public_id, workflow_run_id, stage, status, context_bundle_version,
           last_activity_at, timeout_at, failure_category, completed_at, created_at)
        values (?, ?, 'execution', ?, 1,
                now() - interval '5 minutes', now() - interval '4 minutes',
                ?, now() - interval '4 minutes', now() - interval '5 minutes')
        """,
        seededRex,
        workflowRunId,
        runnerStatus,
        failureCategory);
    jdbcTemplate.update(
        """
        insert into artifacts
          (public_id, workflow_run_id, artifact_type, version, classification,
           storage_ref, status, linked_event_id)
        values (?, ?, 'spec', 1, 'shareable-redacted', 'scratch/test/spec.md', 'available', ?)
        """,
        "art_seedspec" + (workflowRunId % 100000),
        workflowRunId,
        failureEventDbId);
  }
}
