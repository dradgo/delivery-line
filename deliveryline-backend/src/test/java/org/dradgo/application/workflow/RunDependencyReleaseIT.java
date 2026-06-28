package org.dradgo.application.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.dradgo.TestcontainersConfiguration;
import org.dradgo.application.workflow.WorkflowTransitionService.TransitionActor;
import org.dradgo.application.workflow.spi.WorkflowRunCreatePort;
import org.dradgo.domain.id.PublicIdPrefixes;
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

/**
 * Story 3f-3 (AC6/AC7) — end-to-end coverage of the post-commit dependency-release hook over the
 * real wiring (Testcontainers Postgres). NOT {@code @Transactional}: the hook fires only after a
 * genuine commit, so each test commits real rows and the {@code @AfterEach} cleans them up. A
 * dependent is parked on a prerequisite; completing the prerequisite releases it to {@code
 * Investigating}, while taking the prerequisite over leaves the dependent blocked (no cascade).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles({"test", "linear-mock"})
@Tag("integration")
class RunDependencyReleaseIT {

  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private WorkflowRunCreatePort createPort;
  @Autowired private RunDependencyService runDependencyService;
  @Autowired private WorkflowTransitionService transitionService;

  private static final TransitionActor SYSTEM = new TransitionActor("system", ActorType.SYSTEM);

  @AfterEach
  void cleanDatabase() {
    jdbcTemplate.update("delete from run_dependencies");
    jdbcTemplate.update("delete from idempotency_records");
    jdbcTemplate.update("delete from workflow_events");
    jdbcTemplate.update("delete from workflow_runs");
  }

  private String currentState(String runId) {
    return jdbcTemplate.queryForObject(
        "select current_state from workflow_runs where public_id = ?", String.class, runId);
  }

  private String parkDependentOnPrerequisite(String prereqState) {
    String prereq = PublicIdPrefixes.WORKFLOW_RUN.next();
    String dependent = PublicIdPrefixes.WORKFLOW_RUN.next();
    createPort.create(prereq, WorkflowState.fromValue(prereqState, "prereqState"), null);
    createPort.create(dependent, WorkflowState.INBOX, null);
    runDependencyService.declareDependencies(
        new DeclareRunDependenciesCommand(
            dependent,
            List.of(prereq),
            "alex",
            ActorType.HUMAN,
            "idem-rel-" + dependent,
            "corr-rel"));
    assertThat(currentState(dependent)).isEqualTo("WaitingForDependencies");
    return prereq + "," + dependent;
  }

  @Test
  void completingTheLastPrerequisiteReleasesTheDependent() {
    String[] ids = parkDependentOnPrerequisite("WaitingForReview").split(",");
    String prereq = ids[0];
    String dependent = ids[1];

    // Committed Completed transition → post-commit hook releases the dependent.
    transitionService.transition(
        prereq, WorkflowState.COMPLETED, SYSTEM, "review_approved", "rel-done:" + prereq);

    assertThat(currentState(dependent)).isEqualTo("Investigating");
  }

  @Test
  void takenOverPrerequisiteLeavesDependentBlocked() {
    String[] ids = parkDependentOnPrerequisite("WaitingForReview").split(",");
    String prereq = ids[0];
    String dependent = ids[1];

    // A non-Completed terminal outcome must NOT release the dependent (no cascade).
    transitionService.transition(
        prereq, WorkflowState.TAKEN_OVER, SYSTEM, "operator_takeover", "rel-takeover:" + prereq);

    assertThat(currentState(dependent)).isEqualTo("WaitingForDependencies");
  }
}
