package org.dradgo.application.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.dradgo.TestcontainersConfiguration;
import org.dradgo.application.artifact.ActorContext;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.RunnerStage;
import org.dradgo.domain.registry.WorkflowState;
import org.dradgo.observability.testsupport.ItLoggingHarness;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Story 1.21 — Testcontainers contract test for {@link RecoveryService} (story 1.18 deferred).
 * Complements {@code RecoveryServiceUnitTest} (mocks, fast) and {@code
 * RecoveryServiceAppendOnlyTest} (data-layer NFR4 invariants) by gating the caller-facing contract
 * surface against the live Postgres schema:
 *
 * <ul>
 *   <li>Precondition errors raise stable {@link DomainErrorCode}s with the documented
 *       {@code details} keys.
 *   <li>{@link RecoveryService#describeFailure(String)} reads from the live {@code
 *       workflow_events} / {@code workflow_runs} tables and returns the correct {@code
 *       next_safe_action}.
 *   <li>A successful retry returns {@link RetryRecoveryResult} with {@code replayed=false} and
 *       the full id surface; a follow-up call with the SAME idempotency key returns {@code
 *       replayed=true} pointing at the original recovery_actions row — the idempotency contract
 *       the CLI depends on.
 * </ul>
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles({"test", "linear-mock"})
@Tag("contract")
class RecoveryServiceContractTest {

  private static final String CORRELATION_ID = "corr-recovery-contract-1";
  private static final String ACTOR_IDENTITY = "alex";

  @Autowired private JdbcTemplate jdbcTemplate;

  @Autowired private RecoveryService recoveryService;

  private final ItLoggingHarness logHarness = new ItLoggingHarness(getClass());

  // @BeforeEach defends against a prior test method that aborted before its @AfterEach hook ran.
  @BeforeEach
  void setup() {
    cleanDatabase();
    logHarness.attach(CORRELATION_ID, "run_contract-test", "idem-contract-harness-12345");
  }

  @AfterEach
  void teardown() {
    try {
      logHarness.detach();
    } finally {
      cleanDatabase();
    }
  }

  private void cleanDatabase() {
    jdbcTemplate.update("delete from recovery_actions");
    jdbcTemplate.update("delete from runner_executions");
    // artifact_operations + artifacts FK into workflow_events.linked_event_id, so they MUST be
    // cleared before workflow_events. The happy-path test seeds artifacts; this order keeps
    // cleanup correct for every method in the class.
    jdbcTemplate.update("delete from artifact_operations");
    jdbcTemplate.update("delete from artifacts");
    jdbcTemplate.update("delete from workflow_events");
    jdbcTemplate.update("delete from idempotency_records");
    jdbcTemplate.update("delete from workflow_runs");
  }

  @Test
  void retryRaisesRunNotFoundForUnknownWorkflowRun() {
    DomainException error =
        assertThrows(
            DomainException.class,
            () ->
                recoveryService.retry(
                    "run_missingretry",
                    "idem-retry-unknownrun-12345",
                    new ActorContext(ACTOR_IDENTITY, ActorType.HUMAN, CORRELATION_ID),
                    "retry from missing run"));

    assertEquals(DomainErrorCode.RUN_NOT_FOUND, error.errorCode());
    assertEquals("run_missingretry", error.details().get("runId"));
  }

  @Test
  void retryRaisesRetryNotApplicableWhenRunIsNotFailed() {
    String runId = insertRun("run_notfailed12", WorkflowState.EXECUTING);

    DomainException error =
        assertThrows(
            DomainException.class,
            () ->
                recoveryService.retry(
                    runId,
                    "idem-retry-notfailed-1234",
                    new ActorContext(ACTOR_IDENTITY, ActorType.HUMAN, CORRELATION_ID),
                    "retry from executing"));

    assertEquals(DomainErrorCode.RETRY_NOT_APPLICABLE, error.errorCode());
    assertEquals(runId, error.details().get("runId"));
    assertEquals(WorkflowState.EXECUTING.value(), error.details().get("currentState"));
  }

  @Test
  void retryRaisesRetryNotApplicableWhenFailureEventIsMissing() {
    String runId = insertRun("run_nofailevt12", WorkflowState.FAILED);

    DomainException error =
        assertThrows(
            DomainException.class,
            () ->
                recoveryService.retry(
                    runId,
                    "idem-retry-nofailevt-12345",
                    new ActorContext(ACTOR_IDENTITY, ActorType.HUMAN, CORRELATION_ID),
                    "retry without failure event"));

    assertEquals(DomainErrorCode.RETRY_NOT_APPLICABLE, error.errorCode());
    assertEquals("no_failure_event_to_link", error.details().get("reason"));
  }

  @Test
  void describeFailureRaisesRunNotFoundForUnknownWorkflowRun() {
    DomainException error =
        assertThrows(
            DomainException.class, () -> recoveryService.describeFailure("run_missingdesc"));

    assertEquals(DomainErrorCode.RUN_NOT_FOUND, error.errorCode());
  }

  @Test
  void describeFailureReturnsViewOnlyForTerminalRun() {
    String runId = insertRun("run_completed12", WorkflowState.COMPLETED);

    FailureDescription description = recoveryService.describeFailure(runId);

    assertNotNull(description);
    assertEquals(runId, description.workflowRunPublicId());
    assertEquals(WorkflowState.COMPLETED, description.currentState());
    assertEquals(RecoveryService.NEXT_SAFE_ACTION_VIEW_ONLY, description.nextSafeAction());
  }

  @Test
  void describeFailureReturnsAwaitOutcomeForNonTerminalNonFailedRun() {
    String runId = insertRun("run_executing12", WorkflowState.EXECUTING);

    FailureDescription description = recoveryService.describeFailure(runId);

    assertEquals(WorkflowState.EXECUTING, description.currentState());
    assertEquals(RecoveryService.NEXT_SAFE_ACTION_AWAIT_OUTCOME, description.nextSafeAction());
  }

  @Test
  void describeFailureReturnsAwaitManualReconciliationWhenFailedRunHasNoLinkedFailureEvent() {
    String runId = insertRun("run_orphanfail2", WorkflowState.FAILED);

    FailureDescription description = recoveryService.describeFailure(runId);

    assertEquals(WorkflowState.FAILED, description.currentState());
    // No failure event + no failed runner_execution => retry would reject; CLI must recommend
    // await_manual_reconciliation rather than retry to avoid a guaranteed error.
    assertEquals(
        RecoveryService.NEXT_SAFE_ACTION_AWAIT_MANUAL_RECONCILIATION, description.nextSafeAction());
  }

  @Test
  void successfulRetryFollowedBySameKeyReturnsReplayedTrueWithSameRecoveryActionId() {
    // Story 1.21 D3 complement to AppendOnlyTest's data-layer happy-path test: from the consumer
    // surface, asserts that calling retry() a SECOND time with the same idempotency key returns
    // `replayed=true` and reuses the prior recoveryActionPublicId — i.e. the durable audit row is
    // not duplicated and the broker is not re-dispatched. This is the idempotency contract spec
    // 1.18 promised the CLI depends on.
    String runId = "run_contractretr";
    seedFailedRunWithFailureEventAndRunnerExecution(runId, RunnerStage.EXECUTION);
    String idempotencyKey = "idem-retry-contract-happy-1";

    RetryRecoveryResult first =
        recoveryService.retry(
            runId,
            idempotencyKey,
            new ActorContext(ACTOR_IDENTITY, ActorType.HUMAN, CORRELATION_ID),
            "first retry");
    assertFalse(first.replayed(), "first retry must NOT be a replay");
    assertNotNull(first.recoveryActionPublicId());

    RetryRecoveryResult second =
        recoveryService.retry(
            runId,
            idempotencyKey,
            new ActorContext(ACTOR_IDENTITY, ActorType.HUMAN, CORRELATION_ID),
            "second retry with same key");
    assertTrue(second.replayed(), "second retry with same idempotency key MUST be a replay");
    assertEquals(
        first.recoveryActionPublicId(),
        second.recoveryActionPublicId(),
        "replay must surface the prior recovery_actions public id");
    // On replay, retry() returns the prior row's resulting_event_public_id (per
    // RecoveryActionSnapshot mapping) — not a fresh evt_… id and not null.
    assertNotNull(
        second.recoveryRetriedEventPublicId(),
        "replay must carry the prior recovery.retried event id (not null per RetryRecoveryResult javadoc)");

    Integer recoveryRows =
        jdbcTemplate.queryForObject(
            "select count(*) from recovery_actions where idempotency_key=?",
            Integer.class,
            idempotencyKey);
    assertEquals(
        Integer.valueOf(1),
        recoveryRows,
        "replay must NOT create a duplicate recovery_actions row");
  }

  @Test
  void recoveryActionsTableIsReachableAndCleanBetweenTests() {
    // Smoke check that the cleanup hook + schema actually works under Testcontainers. Without
    // this, a leftover row from a prior method would silently invalidate the other tests.
    Integer recoveryActionCount =
        jdbcTemplate.queryForObject("select count(*) from recovery_actions", Integer.class);
    assertNotNull(recoveryActionCount);
    assertEquals(0, recoveryActionCount);
  }

  private String insertRun(String publicId, WorkflowState state) {
    jdbcTemplate.update(
        "insert into workflow_runs (public_id, current_state) values (?, ?)",
        publicId,
        state.value());
    return publicId;
  }

  // Seeds a properly-shaped failed run + linked workflow.stateChanged → Failed event + a failed
  // runner_executions row + an `available` spec artifact (needed for ContextBundleService's
  // artifactReferences minItems: 1 schema requirement) so retry() can both pass precondition
  // guards AND successfully build/validate the context bundle for broker dispatch.
  private void seedFailedRunWithFailureEventAndRunnerExecution(
      String runPublicId, RunnerStage stage) {
    insertRun(runPublicId, WorkflowState.FAILED);
    Long workflowRunId =
        jdbcTemplate.queryForObject(
            "select id from workflow_runs where public_id=?", Long.class, runPublicId);
    assertNotNull(workflowRunId);
    jdbcTemplate.update(
        """
        insert into workflow_events
          (public_id, workflow_run_id, event_type, actor_identity, actor_type,
           intervention_marker, details, prior_state, resulting_state)
        values ('evt_contractfail', ?, 'workflow.stateChanged', 'alex', 'human',
                false, '{}'::jsonb, 'Executing', 'Failed')
        """,
        workflowRunId);
    Long failureEventDbId =
        jdbcTemplate.queryForObject(
            "select id from workflow_events where public_id='evt_contractfail'", Long.class);
    assertNotNull(failureEventDbId);
    jdbcTemplate.update(
        """
        insert into runner_executions
          (public_id, workflow_run_id, stage, status, context_bundle_version,
           last_activity_at, timeout_at, failure_category, completed_at, created_at)
        values ('rex_contractfail', ?, ?, 'failed', 1,
                now() - interval '5 minutes', now() - interval '4 minutes',
                'runner_crash', now() - interval '4 minutes', now() - interval '5 minutes')
        """,
        workflowRunId,
        stage.value());
    jdbcTemplate.update(
        """
        insert into artifacts
          (public_id, workflow_run_id, artifact_type, version, classification,
           storage_ref, status, linked_event_id)
        values ('art_contractspec', ?, 'spec', 1, 'shareable-redacted',
                'scratch/test/spec.md', 'available', ?)
        """,
        workflowRunId,
        failureEventDbId);
  }
}
