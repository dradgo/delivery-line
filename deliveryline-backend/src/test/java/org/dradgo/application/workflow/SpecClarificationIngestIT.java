package org.dradgo.application.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.dradgo.TestcontainersConfiguration;
import org.dradgo.adapters.runner.MockRunnerAdapter;
import org.dradgo.adapters.runner.MockRunnerScenario;
import org.dradgo.adapters.runner.MockRunnerScenarioRegistry;
import org.dradgo.application.clarification.Clarification;
import org.dradgo.application.clarification.spi.ClarificationReadPort;
import org.dradgo.application.runner.RunnerBroker;
import org.dradgo.application.runner.queue.RunnerExecutionQueue;
import org.dradgo.application.runner.spi.RunnerExecutionSnapshot;
import org.dradgo.application.workflow.commands.SubmitClarificationCommand;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Story 3e-1 (AC8c/AC8e) — end-to-end broker spec-clarification ingest over the real wiring
 * (Testcontainers Postgres + mock runner). Drives a spec runner result whose {@code
 * specArtifact.questions} carries one open question through the broker's {@code onResult} /
 * handleSuccess path and asserts:
 *
 * <ul>
 *   <li>an {@code open} clarification row is created, pinned to the just-ingested spec artifact id
 *       + version, visible via {@link ClarificationReadPort#listByWorkflowRunId};
 *   <li>a re-harvest (replay of the same result) does NOT duplicate it (the deterministic
 *       idempotency key holds);
 *   <li>the pre-existing {@code /answer} flow records an answer over a created clarification.
 * </ul>
 *
 * <p>The question-less parity case (a spec result with NO questions creates zero clarifications) is
 * pinned by {@code SpecStageOrchestrationIT} (which drives the default question-less {@code
 * happy-spec} scenario).
 *
 * <p>Auto-dispatch is ON and the default investigation scenario is overridden (via {@link
 * TestPropertySource}) to a registered question-bearing scenario, so {@code submit} auto-dispatches
 * it inside the submit transaction (the per-run pin cannot beat submit-time dispatch). The async
 * result is driven deterministically (Trap T7): lease+dispatch the queued row as a worker would,
 * then harvest via {@code pollActiveExecutions}.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles({"test", "linear-mock"})
@TestPropertySource(
    properties = {
      "deliveryline.runner.spec-stage.auto-dispatch=true",
      "deliveryline.runner.mock.default-scenario.investigation=test-happy-spec-with-clarifications"
    })
class SpecClarificationIngestIT {

  private static final String SCENARIO_WITH_QUESTIONS = "test-happy-spec-with-clarifications";

  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private WorkflowCommandService commandService;
  @Autowired private RunnerBroker runnerBroker;
  @Autowired private MockRunnerAdapter mockRunnerAdapter;
  @Autowired private MockRunnerScenarioRegistry scenarioRegistry;
  @Autowired private ClarificationReadPort clarificationReadPort;
  @Autowired private RunnerExecutionQueue runnerExecutionQueue;
  @Autowired private PlatformTransactionManager transactionManager;

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

  @BeforeEach
  void registerQuestionBearingScenario() {
    // Register a HAPPY investigation scenario whose runner-result carries one
    // specArtifact.question.
    // The `test-` prefix keeps it clearable; it is otherwise the question-less happy-spec twin.
    scenarioRegistry.register(
        new MockRunnerScenario(
            SCENARIO_WITH_QUESTIONS,
            RunnerStage.INVESTIGATION,
            MockRunnerScenario.Behaviour.HAPPY,
            "runner-scenarios/happy-spec-with-clarifications.json",
            null));
  }

  @AfterEach
  void clearTestScenarios() {
    scenarioRegistry.clearTestScenarios();
  }

  private String driveSpecRun(String idemSuffix) {
    String runId =
        commandService
            .submit(
                new SubmitWorkflowCommand(
                    "alex",
                    ActorType.HUMAN,
                    "idem-clarification-submit-" + idemSuffix,
                    "corr-clr-" + idemSuffix,
                    "LIN-101"))
            .workflowRunId();
    drainQueue();
    runnerBroker.pollActiveExecutions();
    assertEquals(WorkflowState.WAITING_FOR_SPEC_APPROVAL.value(), currentState(runId));
    return runId;
  }

  private void drainQueue() {
    Optional<RunnerExecutionSnapshot> leased;
    while ((leased = runnerExecutionQueue.dequeue("it-clarification-worker")).isPresent()) {
      runnerBroker.executeQueuedDispatch(leased.get());
    }
  }

  @Test
  void specResultWithQuestionsCreatesOpenClarificationPinnedToSpecArtifact() {
    String runId = driveSpecRun("create");

    String specArtifactId =
        jdbcTemplate.queryForObject(
            "select public_id from artifacts where workflow_run_id ="
                + " (select id from workflow_runs where public_id = ?) and artifact_type = 'spec'",
            String.class,
            runId);

    List<Clarification> clarifications = clarificationReadPort.listByWorkflowRunId(runId);
    assertEquals(1, clarifications.size());
    Clarification clarification = clarifications.get(0);
    assertEquals(Clarification.STATUS_OPEN, clarification.status());
    assertEquals("Q-IT-001", clarification.questionId());
    assertEquals(specArtifactId, clarification.artifactId());
    assertEquals(1, clarification.artifactVersion());
    assertTrue(clarification.publicId().startsWith("clr_"));

    // The audit event was appended.
    assertEquals(
        1,
        (int)
            jdbcTemplate.queryForObject(
                "select count(*) from workflow_events where event_type = 'clarification.raised'"
                    + " and workflow_run_id ="
                    + " (select id from workflow_runs where public_id = ?)",
                Integer.class,
                runId));
  }

  @Test
  void duplicateQuestionIdInOneResultCollapsesToOneClarificationAndDoesNotStrandTheRun() {
    // Review 3e-1 (CRITICAL regression): two questions sharing one questionId used to make the
    // second insertOpen flush a uq_clarifications_idempotency_key conflict inside the broker's
    // shared transaction, poisoning the Hibernate session (AssertionFailure on the next flush) and
    // stranding the run in Investigating. The ingest service now de-dups by questionId first.
    // Override the default question-bearing scenario (same name) with the duplicate-id fixture.
    scenarioRegistry.register(
        new MockRunnerScenario(
            SCENARIO_WITH_QUESTIONS,
            RunnerStage.INVESTIGATION,
            MockRunnerScenario.Behaviour.HAPPY,
            "runner-scenarios/happy-spec-with-duplicate-question.json",
            null));

    // driveSpecRun asserts the run reaches WAITING_FOR_SPEC_APPROVAL — i.e. the completion is NOT
    // unwound by the duplicate-id conflict.
    String runId = driveSpecRun("dup");

    List<Clarification> clarifications = clarificationReadPort.listByWorkflowRunId(runId);
    assertEquals(
        1,
        clarifications.size(),
        "duplicate questionIds in one result must collapse to a single clarification");
    assertEquals("Q-DUP-001", clarifications.get(0).questionId());
    assertEquals(Clarification.STATUS_OPEN, clarifications.get(0).status());
  }

  @Test
  void reHarvestOfTheSameResultDoesNotDuplicateTheClarification() {
    String runId = driveSpecRun("replay");
    assertEquals(1, clarificationReadPort.listByWorkflowRunId(runId).size());

    String rex =
        jdbcTemplate.queryForObject(
            "select public_id from runner_executions where workflow_run_id ="
                + " (select id from workflow_runs where public_id = ?)",
            String.class,
            runId);
    byte[] resultBytes = mockRunnerAdapter.tryReadResult(rex).orElseThrow();

    // Replay the same result through the broker inside an ambient transaction (as the poll path
    // would) — the deterministic idempotency key makes the per-question insert a benign duplicate.
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(status -> runnerBroker.onResult(rex, resultBytes));

    assertEquals(
        1,
        clarificationReadPort.listByWorkflowRunId(runId).size(),
        "re-harvest must not duplicate the clarification (idempotency key holds)");
  }

  @Test
  void existingAnswerFlowSucceedsOverACreatedClarification() {
    String runId = driveSpecRun("answer");
    Clarification clarification = clarificationReadPort.listByWorkflowRunId(runId).get(0);

    commandService.answerClarification(
        new SubmitClarificationCommand(
            runId,
            clarification.publicId(),
            clarification.artifactId(),
            clarification.artifactVersion(),
            "Yes — include archived records.",
            "alex",
            ActorType.HUMAN,
            "idem-clarification-answer-0001",
            "corr-answer-clr"));

    Clarification answered =
        clarificationReadPort.findByPublicId(clarification.publicId()).orElseThrow();
    assertEquals(Clarification.STATUS_ANSWERED, answered.status());
  }

  private String currentState(String runId) {
    return jdbcTemplate.queryForObject(
        "select current_state from workflow_runs where public_id = ?", String.class, runId);
  }
}
