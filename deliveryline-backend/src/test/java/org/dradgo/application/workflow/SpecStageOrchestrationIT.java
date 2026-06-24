package org.dradgo.application.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.dradgo.TestcontainersConfiguration;
import org.dradgo.adapters.runner.MockRunnerAdapter;
import org.dradgo.application.runner.RunnerBroker;
import org.dradgo.application.runner.queue.RunnerExecutionQueue;
import org.dradgo.application.runner.spi.RunnerExecutionSnapshot;
import org.dradgo.application.workflow.commands.ApproveSpecCommand;
import org.dradgo.application.workflow.commands.RejectSpecCommand;
import org.dradgo.application.workflow.commands.SubmitWorkflowCommand;
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.RejectionTaxonomy;
import org.dradgo.domain.registry.WorkflowState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
class SpecStageOrchestrationIT {

  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private WorkflowCommandService commandService;
  @Autowired private WorkflowOrchestrationService orchestrationService;
  @Autowired private RunnerBroker runnerBroker;
  @Autowired private MockRunnerAdapter mockRunnerAdapter;
  @Autowired private WorkflowInspectionService inspectionService;
  @Autowired private RunnerExecutionQueue runnerExecutionQueue;

  @Autowired
  private org.dradgo.application.clarification.spi.ClarificationReadPort clarificationReadPort;

  // Clean both before and after so residue leaked by a prior IT on the reused Testcontainers
  // Postgres (e.g. an active LIN-101 integration_links row) cannot cross-run-conflict this class's
  // LIN-101 submits regardless of class execution order.
  @BeforeEach
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

  /**
   * Story 3.17b — auto-dispatch now ENQUEUES onto the {@link RunnerExecutionQueue}; the worker pool
   * (off in the test profile, Trap T8) is what leases + dispatches queued rows. Drive that leg
   * deterministically here — exactly as a worker would ({@code dequeue} → {@code
   * executeQueuedDispatch}) — so the row leaves {@code queued} and the subsequent {@code
   * pollActiveExecutions()} can harvest the mock result (Trap T7: no async pool, no sleeping).
   */
  private void drainQueue() {
    Optional<RunnerExecutionSnapshot> leased;
    while ((leased = runnerExecutionQueue.dequeue("it-spec-worker")).isPresent()) {
      runnerBroker.executeQueuedDispatch(leased.get());
    }
  }

  @Test
  void submitAutoDispatchesToInvestigatingThenSpecReadyOnPoll() {
    // AC1: submit creates the run and auto-dispatches the spec runner inside the submit
    // transaction.
    SubmitWorkflowResult submitResult =
        commandService.submit(
            new SubmitWorkflowCommand(
                "alex", ActorType.HUMAN, "idem-e2e-happy-12345", "corr-e2e-happy", "LIN-101"));
    String runId = submitResult.workflowRunId();

    // Review finding P4 — submit reports the run's ACTUAL committed state (auto-advanced to
    // Investigating in the submit transaction), not the stale just-created Inbox value.
    assertEquals(WorkflowState.INVESTIGATING, submitResult.currentState());
    assertEquals(WorkflowState.INVESTIGATING.value(), currentState(runId));
    assertEquals(1, runnerExecutionCount(runId));
    // Story 3.17b: the auto-dispatch enqueues, so the fresh row sits at `queued` until a worker
    // leases it.
    assertEquals(
        "queued",
        jdbcTemplate.queryForObject(
            "select status from runner_executions where workflow_run_id ="
                + " (select id from workflow_runs where public_id = ?)",
            String.class,
            runId));

    // Drive the async result deterministically (Trap T7) — lease+dispatch the queued row as a
    // worker
    // would, then harvest the mock happy-spec result.
    drainQueue();
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
    // A spec artifact lineage was ingested by the broker's recordOperation path AND promoted to
    // `available` (checksum + storageRef stamped) so the human spec-approval gate accepts it. See
    // specArtifactIsMarkedAvailableAndApprovableAfterPoll for the full approve-eligibility
    // assertion.
    assertEquals(
        1,
        jdbcTemplate.queryForObject(
            "select count(*) from artifacts where workflow_run_id ="
                + " (select id from workflow_runs where public_id = ?) and artifact_type = 'spec'",
            Integer.class,
            runId));

    // Story 3e-1 parity: the question-less happy-spec scenario carries no specArtifact.questions,
    // so
    // the broker's clarification create seam is a no-op — a default run stays byte-identical to
    // pre-3e (zero clarifications). The question-bearing path is pinned by
    // SpecClarificationIngestIT.
    assertTrue(clarificationReadPort.listByWorkflowRunId(runId).isEmpty());
  }

  @Test
  void specArtifactIsMarkedAvailableAndApprovableAfterPoll() {
    // The user-facing bug: a real spec run reaches WaitingForSpecApproval but the ingested spec
    // artifact stays `pending`, so the human approval gate (ArtifactService.isApprovalEligible,
    // which requires status=AVAILABLE + checksum + storageRef) rejects approval with
    // ARTIFACT_PAYLOAD_UNAVAILABLE. Wiring markAvailable into the spec-stage ingest must make the
    // artifact available and the approval succeed.
    String runId =
        commandService
            .submit(
                new SubmitWorkflowCommand(
                    "alex", ActorType.HUMAN, "idem-e2e-avail-12345", "corr-e2e-avail", "LIN-101"))
            .workflowRunId();

    drainQueue();
    runnerBroker.pollActiveExecutions();

    assertEquals(WorkflowState.WAITING_FOR_SPEC_APPROVAL.value(), currentState(runId));

    String specArtifactId =
        jdbcTemplate.queryForObject(
            "select public_id from artifacts where workflow_run_id ="
                + " (select id from workflow_runs where public_id = ?) and artifact_type = 'spec'",
            String.class,
            runId);

    // The freshly ingested spec artifact must be AVAILABLE so the approval-eligibility gate accepts
    // it.
    assertEquals(
        "available",
        jdbcTemplate.queryForObject(
            "select status from artifacts where public_id = ?", String.class, specArtifactId));

    // Read half (story 3a-9 AC8): the reviewer reads the real broker-produced spec body through the
    // artifact-read service path BEFORE approving — one integrated read -> approve -> Executing
    // flow
    // (the contract test exercises the HTTP boundary on hand-seeded rows; this asserts the live
    // run).
    WorkflowInspectionService.ArtifactDetailView specDetail =
        inspectionService.getArtifactDetail(runId, specArtifactId);
    assertEquals(specArtifactId, specDetail.artifactId());
    assertEquals("spec", specDetail.artifactType());
    assertEquals("available", specDetail.status());
    assertTrue(specDetail.body() != null && !specDetail.body().isBlank());

    // Approving the spec must succeed and advance the run to Executing (plan-stage auto-dispatch is
    // off in this profile, so the run simply lands in Executing).
    commandService.approveSpec(
        new ApproveSpecCommand(
            runId,
            specArtifactId,
            1,
            1,
            "alex",
            ActorType.HUMAN,
            "idem-e2e-approve-123",
            "corr-e2e-avail",
            "product_reviewer",
            "looks good"));

    assertEquals(WorkflowState.EXECUTING.value(), currentState(runId));
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
    drainQueue();
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

    // Run re-entered Investigating and a SECOND runner execution was enqueued (prior preserved).
    // Story 3.17b: the fresh re-dispatch is `queued` (undrained here), not `pending`.
    assertEquals(WorkflowState.INVESTIGATING.value(), currentState(runId));
    assertEquals(2, runnerExecutionCount(runId));
    assertTrue(
        jdbcTemplate.queryForObject(
                "select count(*) from runner_executions where status = 'queued' and workflow_run_id ="
                    + " (select id from workflow_runs where public_id = ?)",
                Integer.class,
                runId)
            >= 1);
  }
}
