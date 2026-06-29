package org.dradgo.application.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.dradgo.TestcontainersConfiguration;
import org.dradgo.application.workflow.WorkflowTransitionService.TransitionActor;
import org.dradgo.application.workflow.spi.WorkflowRunCreatePort;
import org.dradgo.domain.id.PublicIdPrefixes;
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.FailureCategory;
import org.dradgo.domain.registry.WorkflowState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Story 3f-7 (AC2/AC3/AC4/AC6) — end-to-end coverage of the post-commit split-completion rollup
 * over the real wiring (Testcontainers Postgres). NOT {@code @Transactional}: the rollup fires only
 * after a genuine commit (a {@code @Transactional} test rolls the afterCommit hook back), so each
 * test commits real rows and the {@code @AfterEach} cleans them up (mirrors {@code
 * RunDependencyReleaseIT} / {@code CompletionSyncOrchestrationIT}).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles({"test", "linear-mock"})
@Tag("integration")
class RunSplitCompletionRollupIT {

  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private WorkflowRunCreatePort createPort;
  @Autowired private RunDependencyService runDependencyService;
  @Autowired private WorkflowTransitionService transitionService;

  private static final TransitionActor SYSTEM = new TransitionActor("system", ActorType.SYSTEM);

  @AfterEach
  void cleanDatabase() {
    // Null the self-referencing parent_run_id (ON DELETE RESTRICT) before the bulk delete so the
    // split-child rows do not block the parent rows.
    jdbcTemplate.update("update workflow_runs set parent_run_id = null");
    jdbcTemplate.update("delete from run_dependencies");
    jdbcTemplate.update("delete from idempotency_records");
    jdbcTemplate.update("delete from workflow_events");
    jdbcTemplate.update("delete from workflow_runs");
  }

  private String currentState(String runId) {
    return jdbcTemplate.queryForObject(
        "select current_state from workflow_runs where public_id = ?", String.class, runId);
  }

  private long viaSplitRollupEventCount(String runId) {
    Long count =
        jdbcTemplate.queryForObject(
            "select count(*) from workflow_events e "
                + "join workflow_runs r on r.id = e.workflow_run_id "
                + "where r.public_id = ? and e.details::text like '%viaSplitRollup%'",
            Long.class, runId);
    return count == null ? 0 : count;
  }

  private String newRun(WorkflowState state, String parentRunId) {
    String id = PublicIdPrefixes.WORKFLOW_RUN.next();
    createPort.create(id, state, null, parentRunId);
    return id;
  }

  private void complete(String runId) {
    transitionService.transition(
        runId, WorkflowState.COMPLETED, SYSTEM, "review_approved", "done:" + runId);
  }

  @Test
  void completingTheLastChildRollsTheSplitParentUp() {
    String parent = newRun(WorkflowState.SPLIT, null);
    String childA = newRun(WorkflowState.WAITING_FOR_REVIEW, parent);
    String childB = newRun(WorkflowState.WAITING_FOR_REVIEW, parent);

    // Completing a non-last child does NOT roll the parent up.
    complete(childA);
    assertThat(currentState(parent)).isEqualTo("Split");
    assertThat(viaSplitRollupEventCount(parent)).isZero();

    // Completing the last child rolls the parent Split -> Completed carrying viaSplitRollup.
    complete(childB);
    assertThat(currentState(parent)).isEqualTo("Completed");
    assertThat(viaSplitRollupEventCount(parent)).isEqualTo(1);
  }

  @Test
  void completingTheLeafFlipsParentThenGrandparentViaTheHookChain() {
    String grandparent = newRun(WorkflowState.SPLIT, null);
    String parent = newRun(WorkflowState.SPLIT, grandparent);
    String leaf = newRun(WorkflowState.WAITING_FOR_REVIEW, parent);

    complete(leaf);

    // The parent's own Split -> Completed transition re-fires the rollup hook for the grandparent.
    assertThat(currentState(parent)).isEqualTo("Completed");
    assertThat(currentState(grandparent)).isEqualTo("Completed");
  }

  @Test
  void crossSplitDependentIsReleasedWhenThePrerequisiteSubtreeRollsUp() {
    // X depends on Y; Y is later split into Y1,Y2. When Y1+Y2 complete, Y rolls up to Completed and
    // the UNCHANGED 3f-3 release resolver releases X (AC3).
    String prerequisite = newRun(WorkflowState.WAITING_FOR_REVIEW, null);
    String dependent = newRun(WorkflowState.INBOX, null);
    runDependencyService.declareDependencies(
        new DeclareRunDependenciesCommand(
            dependent,
            List.of(prerequisite),
            "alex",
            ActorType.HUMAN,
            "idem-xsplit-" + dependent,
            "corr-xsplit"));
    assertThat(currentState(dependent)).isEqualTo("WaitingForDependencies");

    // Split the prerequisite (WaitingForReview -> Split) and give it two children.
    transitionService.transition(
        prerequisite, WorkflowState.SPLIT, SYSTEM, "approve_split", "split:" + prerequisite);
    String childY1 = newRun(WorkflowState.WAITING_FOR_REVIEW, prerequisite);
    String childY2 = newRun(WorkflowState.WAITING_FOR_REVIEW, prerequisite);

    complete(childY1);
    assertThat(currentState(prerequisite)).isEqualTo("Split");
    assertThat(currentState(dependent)).isEqualTo("WaitingForDependencies");

    complete(childY2);
    // The prerequisite subtree finished -> Y rolls up, then X releases (the gating transition; the
    // dispatch itself is config-gated — assert the release, per the 3f-5 IT gotcha).
    assertThat(currentState(prerequisite)).isEqualTo("Completed");
    assertThat(currentState(dependent)).isEqualTo("Investigating");
  }

  @Test
  void failedDescendantStallsRollupAndCompletingItResumes() {
    String parent = newRun(WorkflowState.SPLIT, null);
    String failing = newRun(WorkflowState.EXECUTING, parent);
    String childB = newRun(WorkflowState.WAITING_FOR_REVIEW, parent);
    transitionService.transition(
        failing,
        WorkflowState.FAILED,
        SYSTEM,
        "runner_failed",
        "fail:" + failing,
        FailureCategory.RUNNER_CRASH);

    // Completing the healthy child does NOT roll the parent up — a failed descendant stalls it.
    complete(childB);
    assertThat(currentState(parent)).isEqualTo("Split");

    // Drive the failed child to Completed (Failed -> Executing -> WaitingForReview -> Completed);
    // the rollup resumes and the parent finally rolls up. No cascade was triggered in between.
    transitionService.transition(
        failing, WorkflowState.EXECUTING, SYSTEM, "retry", "retry:" + failing);
    transitionService.transition(
        failing, WorkflowState.WAITING_FOR_REVIEW, SYSTEM, "execution_done", "exec:" + failing);
    complete(failing);

    assertThat(currentState(parent)).isEqualTo("Completed");
  }
}
