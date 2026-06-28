package org.dradgo.adapters.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.dradgo.TestcontainersConfiguration;
import org.dradgo.application.workflow.BlockedDependencyView;
import org.dradgo.application.workflow.RunDependencyGraphView;
import org.dradgo.application.workflow.spi.RunDependencyPort;
import org.dradgo.application.workflow.spi.WorkflowRunCreatePort;
import org.dradgo.domain.id.PublicIdPrefixes;
import org.dradgo.domain.registry.WorkflowState;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Story 3f-3 — persistence behavior of the {@code run_dependencies} adapter + recursive cycle
 * probe.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles({"test", "linear-mock"})
@Tag("integration")
@Transactional
class RunDependencyPersistenceIT {

  @Autowired private RunDependencyPort port;
  @Autowired private WorkflowRunCreatePort createPort;
  @Autowired private JdbcTemplate jdbcTemplate;

  private String newRun(WorkflowState state) {
    String id = PublicIdPrefixes.WORKFLOW_RUN.next();
    createPort.create(id, state, null);
    return id;
  }

  @Test
  void edgesRoundTripAndLookUpInBothDirections() {
    String dependent = newRun(WorkflowState.INBOX);
    String prereq = newRun(WorkflowState.INVESTIGATING);

    port.addDependencies(dependent, List.of(prereq));

    assertThat(port.findPrerequisites(dependent))
        .extracting(BlockedDependencyView::runId)
        .containsExactly(prereq);
    assertThat(port.findDependents(prereq))
        .extracting(BlockedDependencyView::runId)
        .containsExactly(dependent);
    // The prerequisite is not Completed, so the dependent is blocked on it.
    assertThat(port.allPrerequisitesCompleted(dependent)).isFalse();
    assertThat(port.findBlockedOn(dependent))
        .extracting(BlockedDependencyView::runId)
        .containsExactly(prereq);
  }

  @Test
  void completingThePrerequisiteClearsTheBlock() {
    String dependent = newRun(WorkflowState.INBOX);
    String prereq = newRun(WorkflowState.INVESTIGATING);
    port.addDependencies(dependent, List.of(prereq));

    jdbcTemplate.update(
        "update workflow_runs set current_state = 'Completed' where public_id = ?", prereq);

    assertThat(port.allPrerequisitesCompleted(dependent)).isTrue();
    assertThat(port.findBlockedOn(dependent)).isEmpty();
  }

  @Test
  void duplicateEdgeDeclarationIsIdempotent() {
    String dependent = newRun(WorkflowState.INBOX);
    String prereq = newRun(WorkflowState.INVESTIGATING);

    port.addDependencies(dependent, List.of(prereq));
    port.addDependencies(dependent, List.of(prereq));

    assertThat(port.findPrerequisites(dependent)).hasSize(1);
  }

  @Test
  void cycleProbeDetectsTransitiveCycles() {
    // a <- b <- c  (b depends on a, c depends on b)
    String a = newRun(WorkflowState.INBOX);
    String b = newRun(WorkflowState.INBOX);
    String c = newRun(WorkflowState.INBOX);
    port.addDependencies(b, List.of(a));
    port.addDependencies(c, List.of(b));

    // Adding "a depends on c" would close a cycle (c -> b -> a -> c).
    assertThat(port.wouldCreateCycle(a, c)).isTrue();
    // Adding "a depends on b" would also close a cycle (b -> a -> b).
    assertThat(port.wouldCreateCycle(a, b)).isTrue();
    // A fresh run depending on a is acyclic (a depends on nothing).
    String fresh = newRun(WorkflowState.INBOX);
    assertThat(port.wouldCreateCycle(fresh, a)).isFalse();
  }

  @Test
  void graphViewAssemblesPrerequisitesDependentsAndBlockedSubset() {
    String dependent = newRun(WorkflowState.INBOX);
    String prereqOpen = newRun(WorkflowState.INVESTIGATING);
    String prereqDone = newRun(WorkflowState.COMPLETED);
    String downstream = newRun(WorkflowState.INBOX);
    port.addDependencies(dependent, List.of(prereqOpen, prereqDone));
    port.addDependencies(downstream, List.of(dependent));

    RunDependencyGraphView view = port.graphView(dependent);

    assertThat(view.prerequisites())
        .extracting(BlockedDependencyView::runId)
        .containsExactlyInAnyOrder(prereqOpen, prereqDone);
    assertThat(view.dependents())
        .extracting(BlockedDependencyView::runId)
        .containsExactly(downstream);
    assertThat(view.blockedOn())
        .extracting(BlockedDependencyView::runId)
        .containsExactly(prereqOpen);
    assertThat(view.blockedByDependencies()).isTrue();
  }

  @Test
  void addingEdgeToDanglingRunIdIsRejectedByForeignKey() {
    String dependent = newRun(WorkflowState.INBOX);
    assertThatThrownBy(() -> port.addDependencies(dependent, List.of("run_missing_prereq1")))
        .hasMessageContaining("fk_run_dependencies_depends_on_run");
  }
}
