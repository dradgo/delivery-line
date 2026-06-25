package org.dradgo.application.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import org.dradgo.application.workflow.commands.AcceptClarificationCommand;
import org.dradgo.application.workflow.commands.RegenerateSpecCommand;
import org.dradgo.application.workflow.commands.SubmitClarificationCommand;
import org.dradgo.application.workflow.commands.SubmitWorkflowCommand;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.DomainErrorCode;
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
 * Story 3e-2 (AC8) — the full incorporation loop over the real wiring (Testcontainers Postgres +
 * the mock runner): 3e-1 creates an {@code open} clarification → {@code /answer} → {@code /accept}
 * ({@code accepted}) → {@code regenerate_spec_with_clarifications} re-dispatches → the rebuilt spec
 * is ingested as a {@code newVersion} GRAFT of the prior spec, carrying structured {@code
 * clarificationAcknowledgements} → the {@code markAvailable} sweep marks the clarification {@code
 * incorporated} (addressed:true) or {@code superseded} (addressed:false).
 *
 * <p>Proves the crux (R3): a v1-pinned accepted clarification IS acted on when v2 becomes available
 * (the graft keeps v1 inside {@code lineageArtifactIds(v2)}).
 *
 * <p>Drives the async results deterministically (Trap T7): lease+dispatch the queued row as a
 * worker would, then harvest via {@code pollActiveExecutions}. The investigation scenario is
 * re-registered (same name) between the initial spec and the rebuild so the rebuild carries
 * acknowledgements.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles({"test", "linear-mock"})
@TestPropertySource(
    properties = {
      "deliveryline.runner.spec-stage.auto-dispatch=true",
      "deliveryline.runner.mock.default-scenario.investigation=test-happy-spec-with-clarifications"
    })
class SpecRebuildIncorporationIT {

  private static final String SCENARIO = "test-happy-spec-with-clarifications";
  private static final String QUESTION_ID = "Q-IT-001";

  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private WorkflowCommandService commandService;
  @Autowired private RunnerBroker runnerBroker;
  @Autowired private MockRunnerScenarioRegistry scenarioRegistry;
  @Autowired private ClarificationReadPort clarificationReadPort;
  @Autowired private RunnerExecutionQueue runnerExecutionQueue;

  @BeforeEach
  @AfterEach
  void cleanDatabase() {
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
  }

  @BeforeEach
  void registerInitialQuestionScenario() {
    registerInvestigationScenario("runner-scenarios/happy-spec-with-clarifications.json");
  }

  @AfterEach
  void clearTestScenarios() {
    scenarioRegistry.clearTestScenarios();
  }

  private void registerInvestigationScenario(String fixturePath) {
    scenarioRegistry.register(
        new MockRunnerScenario(
            SCENARIO,
            RunnerStage.INVESTIGATION,
            MockRunnerScenario.Behaviour.HAPPY,
            fixturePath,
            null));
  }

  @Test
  void acceptedClarificationIsIncorporatedWhenTheRebuiltSpecAcknowledgesIt() {
    String runId = driveAcceptedClarification("incorp");

    // Rebuild emits clarificationAcknowledgements:[{Q-IT-001, addressed:true}].
    registerInvestigationScenario("runner-scenarios/happy-spec-rebuild-acknowledged.json");
    regenerate(runId, "idem-regenerate-spec-incorp-0001");
    drainAndHarvest();

    Clarification swept = clarificationReadPort.listByWorkflowRunId(runId).get(0);
    assertEquals(
        Clarification.STATUS_INCORPORATED,
        swept.status(),
        "an addressed:true acknowledgement must mark the accepted clarification incorporated. "
            + diagnostics(runId));

    // R3 crux — the rebuilt spec grafted onto the prior lineage (v2.parent = v1), so the v1-pinned
    // clarification was inside lineageArtifactIds(v2) and the sweep acted on it.
    List<String> specVersions =
        jdbcTemplate.queryForList(
            "select a.version || ':' || coalesce(p.public_id, 'null') "
                + "from artifacts a left join artifacts p on a.parent_artifact_id = p.id "
                + "where a.workflow_run_id = (select id from workflow_runs where public_id = ?) "
                + "and a.artifact_type = 'spec' order by a.version",
            String.class,
            runId);
    assertEquals(2, specVersions.size(), "the rebuild must produce a v2 spec (graft, not a no-op)");
    assertTrue(specVersions.get(0).startsWith("1:"), "v1 has no parent");
    assertTrue(
        specVersions.get(1).startsWith("2:") && !specVersions.get(1).endsWith(":null"),
        "v2 must be grafted onto v1 (non-null parent): " + specVersions.get(1));

    // The acknowledgement was persisted into the V25 side-store.
    Integer ackRows =
        jdbcTemplate.queryForObject(
            "select count(*) from spec_clarification_acknowledgements "
                + "where question_id = ? and addressed = true",
            Integer.class,
            QUESTION_ID);
    assertEquals(1, ackRows, "the addressed:true acknowledgement was persisted at ingest");
  }

  @Test
  void acceptedClarificationIsSupersededWhenTheRebuiltSpecDoesNotAddressIt() {
    String runId = driveAcceptedClarification("super");

    // Rebuild emits clarificationAcknowledgements:[{Q-IT-001, addressed:false}].
    registerInvestigationScenario("runner-scenarios/happy-spec-rebuild-not-acknowledged.json");
    regenerate(runId, "idem-regenerate-spec-super-0001");
    drainAndHarvest();

    Clarification swept = clarificationReadPort.listByWorkflowRunId(runId).get(0);
    assertEquals(
        Clarification.STATUS_SUPERSEDED,
        swept.status(),
        "an addressed:false acknowledgement must mark the accepted clarification superseded");
  }

  @Test
  void regenerateIsRejectedWhenNoClarificationHasBeenAccepted() {
    // (review D1) The REGENERATE_SPEC action is surfaced unconditionally at WaitingForSpecApproval,
    // so the executor must gate: a rebuild with ZERO accepted clarifications to incorporate is a
    // precondition mismatch (REGENERATE_NOT_APPLICABLE), not a spurious no-op spec re-run.
    String runId =
        commandService
            .submit(
                new SubmitWorkflowCommand(
                    "alex",
                    ActorType.HUMAN,
                    "idem-submit-rebuild-noaccept",
                    "corr-rebuild-noaccept",
                    "LIN-101"))
            .workflowRunId();
    drainAndHarvest();
    assertEquals(WorkflowState.WAITING_FOR_SPEC_APPROVAL.value(), currentState(runId));
    // An OPEN clarification exists, but none is `accepted`.
    assertEquals(
        Clarification.STATUS_OPEN,
        clarificationReadPort.listByWorkflowRunId(runId).get(0).status());

    DomainException thrown =
        assertThrows(
            DomainException.class,
            () ->
                commandService.regenerateSpecWithClarifications(
                    new RegenerateSpecCommand(
                        runId, "alex", ActorType.HUMAN, "idem-regen-noaccept-0001", "corr-regen")));
    assertEquals(DomainErrorCode.REGENERATE_NOT_APPLICABLE, thrown.errorCode());

    // The guard fires BEFORE any state mutation: the run stays WaitingForSpecApproval and no v2
    // spec is minted (all-or-nothing precondition reject).
    assertEquals(WorkflowState.WAITING_FOR_SPEC_APPROVAL.value(), currentState(runId));
    Integer specVersions =
        jdbcTemplate.queryForObject(
            "select count(*) from artifacts "
                + "where workflow_run_id = (select id from workflow_runs where public_id = ?) "
                + "and artifact_type = 'spec'",
            Integer.class,
            runId);
    assertEquals(1, specVersions, "no rebuild dispatch — only the v1 spec exists");
  }

  /** Submit → harvest v1 → answer → accept → returns the run id with one accepted clarification. */
  private String driveAcceptedClarification(String suffix) {
    String runId =
        commandService
            .submit(
                new SubmitWorkflowCommand(
                    "alex",
                    ActorType.HUMAN,
                    "idem-submit-rebuild-" + suffix,
                    "corr-rebuild-" + suffix,
                    "LIN-101"))
            .workflowRunId();
    drainAndHarvest();
    assertEquals(WorkflowState.WAITING_FOR_SPEC_APPROVAL.value(), currentState(runId));

    Clarification open = clarificationReadPort.listByWorkflowRunId(runId).get(0);
    assertEquals(Clarification.STATUS_OPEN, open.status());

    commandService.answerClarification(
        new SubmitClarificationCommand(
            runId,
            open.publicId(),
            open.artifactId(),
            open.artifactVersion(),
            "Yes — include archived records.",
            "alex",
            ActorType.HUMAN,
            "idem-answer-rebuild-" + suffix,
            "corr-answer-" + suffix));

    commandService.acceptClarification(
        new AcceptClarificationCommand(
            runId,
            open.publicId(),
            "alex",
            ActorType.HUMAN,
            "idem-accept-rebuild-" + suffix,
            "corr-accept-" + suffix));

    Clarification accepted = clarificationReadPort.findByPublicId(open.publicId()).orElseThrow();
    assertEquals(Clarification.STATUS_ACCEPTED, accepted.status());
    return runId;
  }

  private void regenerate(String runId, String idempotencyKey) {
    WorkflowStateChangeResult result =
        commandService.regenerateSpecWithClarifications(
            new RegenerateSpecCommand(
                runId, "alex", ActorType.HUMAN, idempotencyKey, "corr-regen"));
    assertNotNull(result);
    assertEquals(WorkflowState.INVESTIGATING, result.currentState());
  }

  private void drainAndHarvest() {
    Optional<RunnerExecutionSnapshot> leased;
    while ((leased = runnerExecutionQueue.dequeue("it-rebuild-worker")).isPresent()) {
      runnerBroker.executeQueuedDispatch(leased.get());
    }
    runnerBroker.pollActiveExecutions();
  }

  private String currentState(String runId) {
    return jdbcTemplate.queryForObject(
        "select current_state from workflow_runs where public_id = ?", String.class, runId);
  }

  private String diagnostics(String runId) {
    String specs =
        String.join(
            ", ",
            jdbcTemplate.queryForList(
                "select a.public_id || ' v' || a.version || ' status=' || a.status || ' parent=' "
                    + "|| coalesce(p.public_id,'null') from artifacts a "
                    + "left join artifacts p on a.parent_artifact_id = p.id "
                    + "where a.workflow_run_id = (select id from workflow_runs where public_id = ?) "
                    + "and a.artifact_type = 'spec' order by a.version",
                String.class,
                runId));
    String acks =
        String.join(
            ", ",
            jdbcTemplate.queryForList(
                "select spec_artifact_id || ':' || question_id || '=' || addressed "
                    + "from spec_clarification_acknowledgements",
                String.class));
    String rexes =
        String.join(
            ", ",
            jdbcTemplate.queryForList(
                "select public_id || ' ' || stage || ' ' || status from runner_executions "
                    + "where workflow_run_id = (select id from workflow_runs where public_id = ?) "
                    + "order by id",
                String.class,
                runId));
    String events =
        String.join(
            " || ",
            jdbcTemplate.queryForList(
                "select event_type || ' ' || coalesce(reason,'') || ' ' "
                    + "|| coalesce(failure_category,'') from workflow_events "
                    + "where workflow_run_id = (select id from workflow_runs where public_id = ?) "
                    + "order by id desc limit 4",
                String.class,
                runId));
    return "[runState="
        + currentState(runId)
        + " | specs=["
        + specs
        + "] | acks=["
        + acks
        + "] | rexes=["
        + rexes
        + "] | lastEvents=["
        + events
        + "]]";
  }
}
