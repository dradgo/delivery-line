package org.dradgo.application.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.dradgo.TestcontainersConfiguration;
import org.dradgo.adapters.runner.MockRunnerAdapter;
import org.dradgo.application.runner.RunnerBroker;
import org.dradgo.application.workflow.commands.RejectSpecCommand;
import org.dradgo.application.workflow.commands.SubmitWorkflowCommand;
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.RejectionTaxonomy;
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
 * Story 3a-1 (AC11) — end-to-end spec-stage orchestration over the real wiring (Testcontainers
 * Postgres + mock runner {@code happy-spec}). Opts into the auto-dispatch master switch via {@link
 * TestPropertySource} (the shared test profile keeps it off). Drives the async result
 * deterministically by invoking {@link RunnerBroker#pollActiveExecutions()} directly rather than
 * sleeping on the 5s scheduler (Trap T7).
 *
 * <p>Failure-mode → Failed (AC4), artifact-type mismatch (AC8), and the orchestration idempotency
 * key shape are covered deterministically by {@code WorkflowTransitionTableTest}, {@code
 * RunnerBrokerUnitTest}, and {@code WorkflowOrchestrationServiceTest}.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles({"test", "linear-mock"})
@TestPropertySource(properties = "deliveryline.runner.spec-stage.auto-dispatch=true")
class SpecStageOrchestrationIntegrationTest {

  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private WorkflowCommandService commandService;
  @Autowired private WorkflowOrchestrationService orchestrationService;
  @Autowired private RunnerBroker runnerBroker;
  @Autowired private MockRunnerAdapter mockRunnerAdapter;

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

  private String currentState(String runId) {
    return jdbcTemplate.queryForObject(
        "select current_state from workflow_runs where public_id = ?", String.class, runId);
  }

  private int runnerExecutionCount(String runId) {
    return jdbcTemplate.queryForObject(
        "select count(*) from runner_executions where workflow_run_id ="
            + " (select id from workflow_runs where public_id = ?)",
        Integer.class,
        runId);
  }

  @Test
  void submitAutoDispatchesToInvestigatingThenSpecReadyOnPoll() {
    // AC1: submit creates the run and auto-dispatches the spec runner inside the submit
    // transaction.
    String runId =
        commandService
            .submit(
                new SubmitWorkflowCommand(
                    "alex", ActorType.HUMAN, "idem-e2e-happy-12345", "corr-e2e-happy", "LIN-101"))
            .workflowRunId();

    assertEquals(WorkflowState.INVESTIGATING.value(), currentState(runId));
    assertEquals(1, runnerExecutionCount(runId));
    assertEquals(
        "pending",
        jdbcTemplate.queryForObject(
            "select status from runner_executions where workflow_run_id ="
                + " (select id from workflow_runs where public_id = ?)",
            String.class,
            runId));

    // Drive the async result deterministically (Trap T7) — the mock happy-spec result is harvested.
    runnerBroker.pollActiveExecutions();

    // AC2/AC3: the spec artifact is available and the run auto-advanced to WaitingForSpecApproval.
    assertEquals(WorkflowState.WAITING_FOR_SPEC_APPROVAL.value(), currentState(runId));
    assertEquals(
        "completed",
        jdbcTemplate.queryForObject(
            "select status from runner_executions where workflow_run_id ="
                + " (select id from workflow_runs where public_id = ?)",
            String.class,
            runId));
    // A spec artifact lineage was ingested by the broker's recordOperation path. NOTE: it remains
    // `pending` — marking it `available` requires ArtifactOperationService.markAvailable, which has
    // NO production caller today (a pre-existing ingestion-completion gap; AC2's "markAvailable"
    // step is unwired). Completing it needs checksum/storageRef plumbing + artifact code this story
    // scopes out ("unchanged ingestion; no new artifact code"). 3a-1 delivers the orchestration
    // auto-advance on successful spec ingest; full artifact availability is a follow-up (see
    // notes).
    assertEquals(
        1,
        jdbcTemplate.queryForObject(
            "select count(*) from artifacts where workflow_run_id ="
                + " (select id from workflow_runs where public_id = ?) and artifact_type = 'spec'",
            Integer.class,
            runId));
  }

  @Test
  void reDispatchWhileInFlightIsAnIdempotentNoOp() {
    // AC6: a duplicate dispatch for the same run while the spec execution is still pending replays.
    String runId =
        commandService
            .submit(
                new SubmitWorkflowCommand(
                    "alex", ActorType.HUMAN, "idem-e2e-idem-12345", "corr-e2e-idem", "LIN-101"))
            .workflowRunId();
    assertEquals(1, runnerExecutionCount(runId));

    orchestrationService.dispatchSpecGeneration(runId, "corr-e2e-idem");

    // Same idempotency key (spec-dispatch:<run>:0) -> broker replay, no second runner execution.
    assertEquals(1, runnerExecutionCount(runId));
    assertEquals(WorkflowState.INVESTIGATING.value(), currentState(runId));
  }

  @Test
  void rejectSpecReDispatchesAFreshRunnerExecution() {
    // AC5: a spec rejection re-enters Investigating and re-dispatches with a fresh execution.
    String runId =
        commandService
            .submit(
                new SubmitWorkflowCommand(
                    "alex", ActorType.HUMAN, "idem-e2e-reject-12345", "corr-e2e-reject", "LIN-101"))
            .workflowRunId();
    runnerBroker.pollActiveExecutions();
    assertEquals(WorkflowState.WAITING_FOR_SPEC_APPROVAL.value(), currentState(runId));

    String specArtifactId =
        jdbcTemplate.queryForObject(
            "select public_id from artifacts where workflow_run_id ="
                + " (select id from workflow_runs where public_id = ?) and artifact_type = 'spec'",
            String.class,
            runId);

    commandService.rejectSpec(
        new RejectSpecCommand(
            runId,
            specArtifactId,
            1,
            1,
            "alex",
            ActorType.HUMAN,
            "idem-e2e-reject-cmd-123",
            "corr-e2e-reject",
            "product_reviewer",
            RejectionTaxonomy.MISSING_SCOPE,
            "Needs more detail"));

    // Run re-entered Investigating and a SECOND runner execution was dispatched (prior preserved).
    assertEquals(WorkflowState.INVESTIGATING.value(), currentState(runId));
    assertEquals(2, runnerExecutionCount(runId));
    assertTrue(
        jdbcTemplate.queryForObject(
                "select count(*) from runner_executions where status = 'pending' and workflow_run_id ="
                    + " (select id from workflow_runs where public_id = ?)",
                Integer.class,
                runId)
            >= 1);
  }
}
