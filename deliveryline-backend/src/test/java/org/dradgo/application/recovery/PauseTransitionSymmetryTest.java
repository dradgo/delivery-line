package org.dradgo.application.recovery;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;
import java.util.Set;
import org.dradgo.application.workflow.WorkflowTransitionTable;
import org.dradgo.domain.registry.WorkflowState;
import org.junit.jupiter.api.Test;

/**
 * Story 4.8 (Reconciliation 2) — THE PAUSE SYMMETRY INVARIANT. Every {@code X → Paused} edge must
 * ship with its {@code Paused → X} return edge, because resume (4.5) transitions back to the typed
 * {@code priorState} recorded on the {@code → Paused} event WITHOUT validating it is a legal {@code
 * Paused →} target (4.5's deferred review finding). An inbound edge without its return edge ships a
 * runbook that strands runs: pause succeeds, resume throws raw {@code ILLEGAL_TRANSITION}, and the
 * run is wedged in Paused. Constructing the two sets together closes that finding BY CONSTRUCTION —
 * this test pins the construction.
 */
class PauseTransitionSymmetryTest {

  private final WorkflowTransitionTable table = WorkflowTransitionTable.defaultTable();

  @Test
  void everyPausableSourceHasAnInboundPausedEdge() {
    for (WorkflowState source : RecoveryService.PAUSABLE_SOURCE_STATES) {
      assertThat(table.allowedTargetsFrom(source))
          .as("PAUSABLE source %s must have a → Paused edge", source)
          .contains(WorkflowState.PAUSED);
    }
  }

  @Test
  void everyPausableSourceHasItsPausedReturnEdge() {
    Set<WorkflowState> pausedTargets = table.allowedTargetsFrom(WorkflowState.PAUSED);
    for (WorkflowState source : RecoveryService.PAUSABLE_SOURCE_STATES) {
      assertThat(pausedTargets)
          .as(
              "Paused must have the return edge → %s (resume restores the recorded priorState)",
              source)
          .contains(source);
    }
  }

  @Test
  void pausedRowIsExactlyThePausableSourcesPlusTheRecoverySafetyEdges() {
    // Tight in BOTH directions: the Paused row is the pausable-source set plus the two universal
    // recovery/safety edges (TakenOver / Reconciled) and NOTHING else. A future edge added to only
    // one side of the pair fails here.
    Set<WorkflowState> expected = EnumSet.copyOf(RecoveryService.PAUSABLE_SOURCE_STATES);
    expected.add(WorkflowState.TAKEN_OVER);
    expected.add(WorkflowState.RECONCILED);
    assertThat(table.allowedTargetsFrom(WorkflowState.PAUSED))
        .containsExactlyInAnyOrderElementsOf(expected);
  }

  @Test
  void noNonPausableStateHasAnInboundPausedEdge() {
    // The four autonomous-driver states (Inbox/Planned/Split/WaitingForDependencies) and the
    // terminal states must NOT gain a → Paused edge: pausing them would silently consume a
    // one-shot driver trigger (Reconciliation 1).
    for (WorkflowState state : WorkflowState.values()) {
      if (RecoveryService.PAUSABLE_SOURCE_STATES.contains(state)) {
        continue;
      }
      assertThat(table.allowedTargetsFrom(state))
          .as("non-pausable state %s must NOT have a → Paused edge", state)
          .doesNotContain(WorkflowState.PAUSED);
    }
  }
}
