package org.dradgo.application.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.dradgo.TestcontainersConfiguration;
import org.dradgo.adapters.runner.MockRunnerScenario;
import org.dradgo.adapters.runner.MockRunnerScenarioRegistry;
import org.dradgo.application.clarification.Clarification;
import org.dradgo.application.clarification.spi.ClarificationReadPort;
import org.dradgo.application.runner.RunnerBroker;
import org.dradgo.application.runner.queue.RunnerExecutionQueue;
import org.dradgo.application.runner.spi.RunnerExecutionSnapshot;
import org.dradgo.application.workflow.WorkflowInspectionService.ReviewerVerdictView;
import org.dradgo.application.workflow.commands.SubmitWorkflowCommand;
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.RunnerStage;
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
 * Story 3e-3 (AC1/AC2/AC3/AC4/AC6/AC9, Task 5) — the full spec-phase advisory-reviewer loop over
 * the real wiring (Testcontainers Postgres + the offline mock runner), proving the 3d-2 reviewer
 * now also fires at the {@code WaitingForSpecApproval} gate:
 *
 * <ol>
 *   <li>a reviewer-bound project drives {@code Investigating → WaitingForSpecApproval} ({@code
 *       onSpecStageSucceeded}) → a {@link RunnerStage#REVIEW} execution is enqueued over the SPEC
 *       artifact (AC1), its spec-review bundle inlines the run's open clarification (AC2);
 *   <li>dispatching+harvesting that REVIEW execution (mock {@code happy-review}) persists a {@code
 *       step_reviews} row whose reviewed artifact is the SPEC (AC3), served stage-agnostically by
 *       {@code getReviewerVerdict} for the WaitingForSpecApproval run (AC4/AC9);
 *   <li>a project WITHOUT a reviewer binding enqueues NO reviewer at the spec gate — byte-identical
 *       to pre-3e-3 (AC6 parity).
 * </ol>
 *
 * <p>Drives the async results deterministically exactly like {@code SpecRebuildIncorporationIT}:
 * lease+dispatch each queued row as a worker would, then harvest via {@code pollActiveExecutions}.
 * The reviewer is enqueued DURING the spec harvest, so a second drain dispatches+harvests it.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles({"test", "linear-mock"})
@TestPropertySource(
    properties = {
      "deliveryline.runner.spec-stage.auto-dispatch=true",
      "deliveryline.runner.mock.default-scenario.investigation=spec-reviewer-it-investigation",
      // The investigation override above rebuilds the default-scenario map, so the REVIEW default
      // ("happy-review") must be re-stated or scenarioFor(REVIEW) falls back to a spec scenario and
      // the reviewer emits a runner-result the harvest rejects as a contract violation.
      "deliveryline.runner.mock.default-scenario.review=happy-review"
    })
class SpecPhaseAdvisoryReviewerIT {

  private static final String SCENARIO = "spec-reviewer-it-investigation";

  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private WorkflowCommandService commandService;
  @Autowired private WorkflowInspectionService inspectionService;
  @Autowired private RunnerBroker runnerBroker;
  @Autowired private MockRunnerScenarioRegistry scenarioRegistry;
  @Autowired private ClarificationReadPort clarificationReadPort;
  @Autowired private RunnerExecutionQueue runnerExecutionQueue;

  @BeforeEach
  @AfterEach
  void cleanDatabase() {
    jdbcTemplate.update("delete from step_reviews");
    jdbcTemplate.update("delete from spec_clarification_acknowledgements");
    jdbcTemplate.update("delete from idempotency_records");
    jdbcTemplate.update("delete from integration_links");
    jdbcTemplate.update("delete from clarifications");
    jdbcTemplate.update("delete from approvals");
    jdbcTemplate.update("delete from artifact_operations");
    jdbcTemplate.update("delete from artifacts");
    jdbcTemplate.update("delete from runner_executions");
    jdbcTemplate.update("delete from workflow_events");
    jdbcTemplate.update("delete from workflow_runs");
    // Reset the reviewer binding so each test starts from the no-binding parity baseline.
    jdbcTemplate.update("update projects set reviewer_model_kind = null");
  }

  @BeforeEach
  void registerInvestigationScenario() {
    scenarioRegistry.register(
        new MockRunnerScenario(
            SCENARIO,
            RunnerStage.INVESTIGATION,
            MockRunnerScenario.Behaviour.HAPPY,
            "runner-scenarios/happy-spec-with-clarifications.json",
            null));
  }

  @AfterEach
  void clearTestScenarios() {
    scenarioRegistry.clearTestScenarios();
  }

  @Test
  void specReviewerEnqueuesOnSpecGateAndPersistsVerdictAgainstTheSpec() {
    bindReviewer("claude");

    String runId = submit("idem-submit-specrev-bound", "corr-specrev-bound");
    // Drain #1: dispatch + harvest the INVESTIGATION spec → WaitingForSpecApproval + reviewer
    // enqueued (during the spec harvest) over the spec artifact.
    drainAndHarvest();

    assertEquals(WorkflowState.WAITING_FOR_SPEC_APPROVAL.value(), currentState(runId));
    // AC1 — a REVIEW execution was enqueued at the spec gate.
    Integer reviewExecCount = reviewExecutionCount(runId);
    assertEquals(1, reviewExecCount, "exactly one reviewer execution enqueued at the spec gate");
    // AC2 — an open clarification exists (it is inlined into the spec-review bundle).
    Clarification open = clarificationReadPort.listByWorkflowRunId(runId).get(0);
    assertEquals(Clarification.STATUS_OPEN, open.status());

    // Drain #2: dispatch + harvest the reviewer execution (mock happy-review → review-result.v1).
    drainAndHarvest();

    // AC3 — a step_reviews row persists, reviewed artifact = the SPEC (no schema change).
    List<String> reviewedTypes =
        jdbcTemplate.queryForList(
            "select a.artifact_type from step_reviews sr "
                + "join artifacts a on sr.reviewed_artifact_id = a.id "
                + "where sr.workflow_run_id = (select id from workflow_runs where public_id = ?)",
            String.class,
            runId);
    assertEquals(1, reviewedTypes.size(), "exactly one persisted verdict");
    assertEquals("spec", reviewedTypes.get(0), "the verdict reviews the SPEC artifact");

    // AC4/AC9 — the stage-agnostic endpoint serves the spec-phase verdict for the
    // WaitingForSpecApproval run; the run is NOT auto-advanced (advisory-only).
    ReviewerVerdictView verdict = inspectionService.getReviewerVerdict(runId);
    assertEquals("available", verdict.state());
    assertTrue(
        List.of("pass", "concern", "fail").contains(verdict.outcome()),
        "advisory outcome surfaced: " + verdict.outcome());
    assertEquals(
        WorkflowState.WAITING_FOR_SPEC_APPROVAL.value(),
        currentState(runId),
        "the verdict is advisory-only — the spec gate is not auto-advanced");
  }

  @Test
  void noReviewerBindingEnqueuesNoReviewerAtSpecGateParity() {
    // AC6 — no reviewer binding on the project ⇒ NO reviewer execution at the spec gate, byte-
    // identical to pre-3e-3 (the parity hot path).
    String runId = submit("idem-submit-specrev-parity", "corr-specrev-parity");
    drainAndHarvest();

    assertEquals(WorkflowState.WAITING_FOR_SPEC_APPROVAL.value(), currentState(runId));
    assertEquals(0, reviewExecutionCount(runId), "no reviewer enqueued without a binding");
    Integer verdictRows =
        jdbcTemplate.queryForObject(
            "select count(*) from step_reviews sr "
                + "where sr.workflow_run_id = (select id from workflow_runs where public_id = ?)",
            Integer.class,
            runId);
    assertEquals(0, verdictRows, "no verdict persisted without a reviewer");
  }

  private void bindReviewer(String kind) {
    jdbcTemplate.update("update projects set reviewer_model_kind = ?", kind);
  }

  private String submit(String idempotencyKey, String correlationId) {
    return commandService
        .submit(
            new SubmitWorkflowCommand(
                "alex", ActorType.HUMAN, idempotencyKey, correlationId, "LIN-101"))
        .workflowRunId();
  }

  private void drainAndHarvest() {
    Optional<RunnerExecutionSnapshot> leased;
    while ((leased = runnerExecutionQueue.dequeue("it-specrev-worker")).isPresent()) {
      runnerBroker.executeQueuedDispatch(leased.get());
    }
    runnerBroker.pollActiveExecutions();
  }

  private Integer reviewExecutionCount(String runId) {
    return jdbcTemplate.queryForObject(
        "select count(*) from runner_executions "
            + "where workflow_run_id = (select id from workflow_runs where public_id = ?) "
            + "and stage = 'review'",
        Integer.class,
        runId);
  }

  private String currentState(String runId) {
    return jdbcTemplate.queryForObject(
        "select current_state from workflow_runs where public_id = ?", String.class, runId);
  }
}
