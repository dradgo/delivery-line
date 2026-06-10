package org.dradgo.application.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.dradgo.TestcontainersConfiguration;
import org.dradgo.application.runner.RunnerBroker;
import org.dradgo.domain.registry.WorkflowState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * Story 3.12 (AC11) — end-to-end pr-output-stage orchestration over the real wiring (Testcontainers
 * Postgres + mock runner {@code happy-pr-output}). The pr-output twin of {@link
 * ImplementationPlanOrchestrationIT}.
 *
 * <p>The live plan-approval trigger ({@code acceptImplementation}) is story 3.20 (Decision D1), so
 * this IT drives the seam DIRECTLY: it seeds an APPROVED implementation-plan artifact so {@code
 * ContextBundleService.deriveExecutionSubStage} yields {@link
 * org.dradgo.application.runner.ExecutionSubStage#PR_OUTPUT}, places the run in {@code Executing}
 * (the state the live trigger would have transitioned it to), then invokes {@link
 * WorkflowOrchestrationService#dispatchImplementation} and drives the async pr-output result
 * deterministically via {@link RunnerBroker#pollActiveExecutions()} (Trap T6 — never sleep on the
 * scheduler), asserting the run auto-advanced {@code Executing -> WaitingForReview} with reason
 * {@code pr_output_ready}.
 *
 * <p>No repo workspace is wired here (no {@code github-mock} profile), so {@code captureAndPush}
 * returns empty and the happy path exercises AC9 ref-format validation on the mock's (well-formed)
 * reported refs without a drift check or enrichment. The drift ({@code RUNNER_PR_REF_DRIFT}),
 * ref-format-failure ({@code RUNNER_OUTPUT_VALIDATION_FAILED}), and enrichment shapes are covered
 * deterministically by {@code RunnerBrokerUnitTest}; the dispatch key / sub-stage-aware in-flight
 * no-op / idempotent-replay shapes by {@code WorkflowOrchestrationServiceTest}.
 *
 * <p>Opts into the implementation-stage auto-dispatch master switch + overrides the EXECUTION mock
 * scenario to {@code happy-pr-output} via {@link TestPropertySource} (the shared test profile keeps
 * auto-dispatch OFF and the EXECUTION scenario at {@code happy-implementation-plan}, Trap T7).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles({"test", "linear-mock"})
@TestPropertySource(
    properties = {
      "deliveryline.runner.implementation-stage.auto-dispatch=true",
      "deliveryline.runner.mock.default-scenario.execution=happy-pr-output"
    })
class PrOutputOrchestrationIT {

  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private WorkflowOrchestrationService orchestrationService;
  @Autowired private RunnerBroker runnerBroker;

  @AfterEach
  void cleanDatabase() {
    jdbcTemplate.update("delete from idempotency_records");
    jdbcTemplate.update("delete from integration_links");
    jdbcTemplate.update("delete from clarifications");
    jdbcTemplate.update("delete from approvals");
    jdbcTemplate.update("delete from artifact_operations");
    jdbcTemplate.update("delete from artifacts");
    jdbcTemplate.update("delete from runner_executions");
    jdbcTemplate.update("delete from workflow_events");
    jdbcTemplate.update("delete from workflow_runs");
  }

  @Test
  void dispatchImplementationThenWaitingForReviewOnPoll() {
    // Precondition: a run already Executing with an approved implementation-plan
    // (deriveExecutionSubStage -> PR_OUTPUT). The live acceptImplementation trigger is story 3.20.
    String runId = insertRun("run_proit0000happy", WorkflowState.EXECUTING);
    seedApprovedImplementationPlan(runId, "art_proit_happy_plan");

    // AC1 (Trap T1): dispatchImplementation dispatches the pr-output runner WITHOUT transitioning —
    // the run is already Executing.
    orchestrationService.dispatchImplementation(runId, "corr-proit-happy");

    assertEquals(WorkflowState.EXECUTING.value(), currentState(runId));
    assertEquals(1, executionCount(runId));
    assertEquals("pending", executionStatus(runId));

    // Drive the async EXECUTION result deterministically (Trap T6) — the mock happy-pr-output
    // result
    // is harvested.
    runnerBroker.pollActiveExecutions();

    // AC5: the pr-output artifact is ingested and the run auto-advanced to WaitingForReview.
    assertEquals(WorkflowState.WAITING_FOR_REVIEW.value(), currentState(runId));
    assertEquals("completed", executionStatus(runId));
    // Decision D1: the prOutput artifact lineage was ingested but stays `pending` (markAvailable
    // has
    // no production caller; the auto-advance fires on successful INGEST).
    assertTrue(
        prOutputArtifactCount(runId) >= 1, "expected at least one ingested prOutput artifact");
  }

  @Test
  void reDispatchImplementationWhileInFlightIsAnIdempotentNoOp() {
    // AC6 / Task 2: a duplicate pr-output dispatch for the same run while the EXECUTION execution
    // is
    // still pending replays — no second runner execution.
    String runId = insertRun("run_proit0000idem", WorkflowState.EXECUTING);
    seedApprovedImplementationPlan(runId, "art_proit_idem_plan");

    orchestrationService.dispatchImplementation(runId, "corr-proit-idem");
    assertEquals(1, executionCount(runId));

    orchestrationService.dispatchImplementation(runId, "corr-proit-idem");

    assertEquals(1, executionCount(runId));
    assertEquals(WorkflowState.EXECUTING.value(), currentState(runId));
  }

  @Test
  void retryImplementationDispatchesAFreshRunnerExecution() {
    // AC8: a retry (no in-flight EXECUTION execution) re-dispatches with a fresh runnerExecutionId
    // +
    // advanced context-bundle version, preserving the prior execution for audit.
    String runId = insertRun("run_proit0000retry", WorkflowState.EXECUTING);
    seedApprovedImplementationPlan(runId, "art_proit_retry_plan");

    orchestrationService.dispatchImplementation(runId, "corr-proit-retry");
    runnerBroker.pollActiveExecutions();
    assertEquals(WorkflowState.WAITING_FOR_REVIEW.value(), currentState(runId));
    assertEquals(1, executionCount(runId));

    orchestrationService.retryImplementation(runId, "corr-proit-retry");

    // A SECOND EXECUTION execution was dispatched (the prior completed one is preserved).
    assertEquals(2, executionCount(runId));
    assertTrue(
        jdbcTemplate.queryForObject(
                "select count(*) from runner_executions where status = 'completed'"
                    + " and workflow_run_id ="
                    + " (select id from workflow_runs where public_id = ?)",
                Integer.class,
                runId)
            >= 1);
  }

  // ---------------------------------------------------------------------------------------------

  private String insertRun(String publicId, WorkflowState state) {
    jdbcTemplate.update(
        "insert into workflow_runs (public_id, current_state) values (?, ?)",
        publicId,
        state.value());
    return publicId;
  }

  private String currentState(String runId) {
    return jdbcTemplate.queryForObject(
        "select current_state from workflow_runs where public_id = ?", String.class, runId);
  }

  private int executionCount(String runId) {
    return jdbcTemplate.queryForObject(
        "select count(*) from runner_executions where workflow_run_id ="
            + " (select id from workflow_runs where public_id = ?)",
        Integer.class,
        runId);
  }

  private String executionStatus(String runId) {
    return jdbcTemplate.queryForObject(
        "select status from runner_executions where workflow_run_id ="
            + " (select id from workflow_runs where public_id = ?) order by id desc limit 1",
        String.class,
        runId);
  }

  private int prOutputArtifactCount(String runId) {
    return jdbcTemplate.queryForObject(
        "select count(*) from artifacts where workflow_run_id ="
            + " (select id from workflow_runs where public_id = ?)"
            + " and artifact_type = 'prOutput'",
        Integer.class,
        runId);
  }

  /**
   * Seeds an APPROVED implementation-plan artifact lineage so {@code deriveExecutionSubStage}
   * returns {@code PR_OUTPUT}. The plan artifact itself stays {@code pending} (markAvailable
   * unwired); the approval row carries {@code decision='approved'} which is the sole condition
   * {@code findLatestApprovedForArtifactLineage} checks.
   */
  private void seedApprovedImplementationPlan(String runPublicId, String artifactPublicId) {
    Long runId =
        jdbcTemplate.queryForObject(
            "select id from workflow_runs where public_id = ?", Long.class, runPublicId);
    long uniq = System.nanoTime();

    Long linkedEventId =
        jdbcTemplate.queryForObject(
            "insert into workflow_events (public_id, workflow_run_id, event_type, actor_identity,"
                + " actor_type) values (?, ?, 'artifact.draftCreated', 'seed', 'system') returning"
                + " id",
            Long.class,
            "evt_proseed" + uniq,
            runId);

    Long artifactId =
        jdbcTemplate.queryForObject(
            "insert into artifacts (public_id, workflow_run_id, artifact_type, version,"
                + " parent_artifact_id, classification, status, linked_event_id) values (?, ?,"
                + " 'implementationPlan', 1, null, 'shareable-redacted', 'pending', ?) returning id",
            Long.class,
            artifactPublicId,
            runId,
            linkedEventId);

    jdbcTemplate.update(
        "insert into approvals (public_id, workflow_run_id, artifact_id, artifact_version,"
            + " context_bundle_version, actor_identity, actor_type, reviewer_role, decision,"
            + " idempotency_key) values (?, ?, ?, 1, 1, 'alex', 'human', 'tech_reviewer',"
            + " 'approved', ?)",
        "apr_proseed" + uniq,
        runId,
        artifactId,
        "idem-proseed-approval-" + uniq);
  }
}
