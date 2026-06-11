package org.dradgo.application.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.dradgo.TestcontainersConfiguration;
import org.dradgo.adapters.integration.linear.LinearMockAdapter;
import org.dradgo.application.workflow.WorkflowTransitionService.TransitionActor;
import org.dradgo.application.workflow.commands.SubmitWorkflowCommand;
import org.dradgo.domain.registry.ActorType;
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
 * Story 3.16 (AC3/AC10) — end-to-end coverage of the post-commit completion-sync hook over the real
 * wiring (Testcontainers Postgres + {@code linear-mock} adapter). Opts into the completion-sync
 * master switch via {@link TestPropertySource} (the shared test profile keeps it OFF). A run is
 * submitted (which links its source Linear ticket), driven through the legal state machine to
 * {@code WaitingForReview}, then transitioned to {@code Completed}; the hook fires post-commit and
 * the mock Linear adapter records exactly one merge-ready comment.
 *
 * <p>The disabled (opt-out) suppression, redaction, failure-event, defensive-resolution, and
 * idempotent-fingerprint behaviours are covered deterministically by {@code
 * WorkflowOrchestrationServiceTest}. The startup template-validator is covered by {@code
 * LinearCompletionSyncConfigurationTest}.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles({"test", "linear-mock"})
@TestPropertySource(properties = "deliveryline.workflow.linear-completion-sync.enabled=true")
class CompletionSyncOrchestrationIT {

  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private WorkflowCommandService commandService;
  @Autowired private WorkflowTransitionService transitionService;
  @Autowired private LinearMockAdapter linearMockAdapter;

  @BeforeEach
  void resetMock() {
    linearMockAdapter.clearPostedComments();
  }

  @AfterEach
  void cleanDatabase() {
    linearMockAdapter.clearPostedComments();
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

  @Test
  void completedTransitionFiresPostCommitHookAndPostsMergeReadyComment() {
    String runId =
        commandService
            .submit(
                new SubmitWorkflowCommand(
                    "alex", ActorType.HUMAN, "idem-cs-1234567890", "corr-cs", "LIN-101"))
            .workflowRunId();

    TransitionActor actor = new TransitionActor("system", ActorType.SYSTEM);
    transitionService.transition(
        runId, WorkflowState.INVESTIGATING, actor, "spec_dispatch", "cs-inv:" + runId);
    transitionService.transition(
        runId, WorkflowState.WAITING_FOR_SPEC_APPROVAL, actor, "spec_ready", "cs-spec:" + runId);
    transitionService.transition(
        runId, WorkflowState.EXECUTING, actor, "spec_approved", "cs-exec:" + runId);
    transitionService.transition(
        runId, WorkflowState.WAITING_FOR_REVIEW, actor, "plan_ready", "cs-review:" + runId);

    // No comment yet — the hook only fires on a COMPLETED transition.
    assertTrue(linearMockAdapter.postedComments().isEmpty());

    transitionService.transition(
        runId, WorkflowState.COMPLETED, actor, "accepted", "cs-complete:" + runId);

    assertEquals(WorkflowState.COMPLETED.value(), currentState(runId));
    // The post-commit hook fired and posted exactly one merge-ready comment for the source ticket.
    assertEquals(1, linearMockAdapter.postedComments().size());
    LinearMockAdapter.PostedComment posted = linearMockAdapter.postedComments().get(0);
    assertEquals("LIN-101", posted.ticketRef());
    assertEquals(runId, posted.comment().runPublicId());
    assertTrue(posted.comment().body().contains(runId));
  }
}
