package org.dradgo.application.runner;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.RunnerExecutionStatus;
import org.junit.jupiter.api.Test;

class RunnerExecutionStateMachineTest {

  private static final String REX = "rex_unit12345678";

  @Test
  void pendingAllowsRunningAndAllTerminalTransitions() {
    assertDoesNotThrow(
        () ->
            RunnerExecutionStateMachine.assertCanTransition(
                REX, RunnerExecutionStatus.PENDING, RunnerExecutionStatus.RUNNING));
    assertDoesNotThrow(
        () ->
            RunnerExecutionStateMachine.assertCanTransition(
                REX, RunnerExecutionStatus.PENDING, RunnerExecutionStatus.COMPLETED));
    assertDoesNotThrow(
        () ->
            RunnerExecutionStateMachine.assertCanTransition(
                REX, RunnerExecutionStatus.PENDING, RunnerExecutionStatus.FAILED));
    assertDoesNotThrow(
        () ->
            RunnerExecutionStateMachine.assertCanTransition(
                REX, RunnerExecutionStatus.PENDING, RunnerExecutionStatus.TIMED_OUT));
    assertDoesNotThrow(
        () ->
            RunnerExecutionStateMachine.assertCanTransition(
                REX, RunnerExecutionStatus.PENDING, RunnerExecutionStatus.ORPHANED));
  }

  @Test
  void runningAllowsTerminalTransitions() {
    assertDoesNotThrow(
        () ->
            RunnerExecutionStateMachine.assertCanTransition(
                REX, RunnerExecutionStatus.RUNNING, RunnerExecutionStatus.COMPLETED));
    assertDoesNotThrow(
        () ->
            RunnerExecutionStateMachine.assertCanTransition(
                REX, RunnerExecutionStatus.RUNNING, RunnerExecutionStatus.FAILED));
    assertDoesNotThrow(
        () ->
            RunnerExecutionStateMachine.assertCanTransition(
                REX, RunnerExecutionStatus.RUNNING, RunnerExecutionStatus.TIMED_OUT));
    assertDoesNotThrow(
        () ->
            RunnerExecutionStateMachine.assertCanTransition(
                REX, RunnerExecutionStatus.RUNNING, RunnerExecutionStatus.ORPHANED));
  }

  @Test
  void pendingCannotGoToPending() {
    DomainException error =
        assertThrows(
            DomainException.class,
            () ->
                RunnerExecutionStateMachine.assertCanTransition(
                    REX, RunnerExecutionStatus.PENDING, RunnerExecutionStatus.PENDING));
    assertEquals(DomainErrorCode.ILLEGAL_TRANSITION, error.errorCode());
    assertEquals("no_op_transition", error.details().get("reason"));
    assertEquals(REX, error.details().get("runnerExecutionId"));
  }

  @Test
  void runningCannotGoBackToRunning() {
    DomainException error =
        assertThrows(
            DomainException.class,
            () ->
                RunnerExecutionStateMachine.assertCanTransition(
                    REX, RunnerExecutionStatus.RUNNING, RunnerExecutionStatus.RUNNING));
    assertEquals(DomainErrorCode.ILLEGAL_TRANSITION, error.errorCode());
  }

  @Test
  void runningCannotGoBackToPending() {
    DomainException error =
        assertThrows(
            DomainException.class,
            () ->
                RunnerExecutionStateMachine.assertCanTransition(
                    REX, RunnerExecutionStatus.RUNNING, RunnerExecutionStatus.PENDING));
    assertEquals(DomainErrorCode.ILLEGAL_TRANSITION, error.errorCode());
    assertEquals("transition_not_allowed", error.details().get("reason"));
  }

  @Test
  void completedAndFailedRemainLocked() {
    for (RunnerExecutionStatus terminal :
        new RunnerExecutionStatus[] {
          RunnerExecutionStatus.COMPLETED, RunnerExecutionStatus.FAILED
        }) {
      assertTrue(RunnerExecutionStateMachine.isTerminal(terminal));
      for (RunnerExecutionStatus target : RunnerExecutionStatus.values()) {
        assertThrows(
            DomainException.class,
            () -> RunnerExecutionStateMachine.assertCanTransition(REX, terminal, target),
            () -> "terminal " + terminal + " must not transition to " + target);
      }
    }
  }

  @Test
  void timedOutAndOrphanedCanBeReclassifiedToFailedOnly() {
    assertDoesNotThrow(
        () ->
            RunnerExecutionStateMachine.assertCanTransition(
                REX, RunnerExecutionStatus.TIMED_OUT, RunnerExecutionStatus.FAILED));
    assertDoesNotThrow(
        () ->
            RunnerExecutionStateMachine.assertCanTransition(
                REX, RunnerExecutionStatus.ORPHANED, RunnerExecutionStatus.FAILED));
    assertThrows(
        DomainException.class,
        () ->
            RunnerExecutionStateMachine.assertCanTransition(
                REX, RunnerExecutionStatus.TIMED_OUT, RunnerExecutionStatus.COMPLETED));
    assertThrows(
        DomainException.class,
        () ->
            RunnerExecutionStateMachine.assertCanTransition(
                REX, RunnerExecutionStatus.ORPHANED, RunnerExecutionStatus.RUNNING));
    assertTrue(RunnerExecutionStateMachine.isTerminal(RunnerExecutionStatus.TIMED_OUT));
    assertTrue(RunnerExecutionStateMachine.isTerminal(RunnerExecutionStatus.ORPHANED));
  }

  @Test
  void pendingAndRunningAreNotTerminal() {
    assertFalse(RunnerExecutionStateMachine.isTerminal(RunnerExecutionStatus.PENDING));
    assertFalse(RunnerExecutionStateMachine.isTerminal(RunnerExecutionStatus.RUNNING));
  }

  // Story 3.22 (AC5 / Trap T4) — developer-takeover cancellation edges.
  @Test
  void pendingAndRunningCanBeCancelledForTakeover() {
    assertDoesNotThrow(
        () ->
            RunnerExecutionStateMachine.assertCanTransition(
                REX, RunnerExecutionStatus.PENDING, RunnerExecutionStatus.CANCELLED_FOR_TAKEOVER));
    assertDoesNotThrow(
        () ->
            RunnerExecutionStateMachine.assertCanTransition(
                REX, RunnerExecutionStatus.RUNNING, RunnerExecutionStatus.CANCELLED_FOR_TAKEOVER));
  }

  @Test
  void awaitingManualCanBeCancelledForTakeover() {
    assertDoesNotThrow(
        () ->
            RunnerExecutionStateMachine.assertCanTransition(
                REX,
                RunnerExecutionStatus.AWAITING_MANUAL,
                RunnerExecutionStatus.CANCELLED_FOR_TAKEOVER));
  }

  // Story 3d-4 (AC4 / R1) — a submitted manual artifact finalizes the parked row to COMPLETED. The
  // edge was illegal before 3d-4 (3d-3 only declared the takeover edge). A parked row never runs in
  // a container, so RUNNING / PENDING stay rejected.
  @Test
  void awaitingManualCanBeCompletedOnManualSubmission() {
    assertDoesNotThrow(
        () ->
            RunnerExecutionStateMachine.assertCanTransition(
                REX, RunnerExecutionStatus.AWAITING_MANUAL, RunnerExecutionStatus.COMPLETED));
  }

  @Test
  void awaitingManualCannotGoToRunningOrPending() {
    DomainException toRunning =
        assertThrows(
            DomainException.class,
            () ->
                RunnerExecutionStateMachine.assertCanTransition(
                    REX, RunnerExecutionStatus.AWAITING_MANUAL, RunnerExecutionStatus.RUNNING));
    assertEquals(DomainErrorCode.ILLEGAL_TRANSITION, toRunning.errorCode());
    assertEquals("transition_not_allowed", toRunning.details().get("reason"));
    assertThrows(
        DomainException.class,
        () ->
            RunnerExecutionStateMachine.assertCanTransition(
                REX, RunnerExecutionStatus.AWAITING_MANUAL, RunnerExecutionStatus.PENDING));
  }

  @Test
  void queuedAllowsRunningAndCancelledForTakeoverOnly() {
    assertDoesNotThrow(
        () ->
            RunnerExecutionStateMachine.assertCanTransition(
                REX, RunnerExecutionStatus.QUEUED, RunnerExecutionStatus.RUNNING));
    assertDoesNotThrow(
        () ->
            RunnerExecutionStateMachine.assertCanTransition(
                REX, RunnerExecutionStatus.QUEUED, RunnerExecutionStatus.CANCELLED_FOR_TAKEOVER));
    DomainException illegal =
        assertThrows(
            DomainException.class,
            () ->
                RunnerExecutionStateMachine.assertCanTransition(
                    REX, RunnerExecutionStatus.QUEUED, RunnerExecutionStatus.COMPLETED));
    assertEquals(DomainErrorCode.ILLEGAL_TRANSITION, illegal.errorCode());
    assertEquals("transition_not_allowed", illegal.details().get("reason"));
  }

  @Test
  void cancelledForTakeoverIsTerminalAndLocked() {
    assertTrue(
        RunnerExecutionStateMachine.isTerminal(RunnerExecutionStatus.CANCELLED_FOR_TAKEOVER));
    for (RunnerExecutionStatus target : RunnerExecutionStatus.values()) {
      assertThrows(
          DomainException.class,
          () ->
              RunnerExecutionStateMachine.assertCanTransition(
                  REX, RunnerExecutionStatus.CANCELLED_FOR_TAKEOVER, target),
          () -> "terminal cancelled_for_takeover must not transition to " + target);
    }
  }

  // Story 4.8 (AC4) — manual-pause cancellation edges: the same {pending, queued, running}
  // dispatch-visible scan set as takeover.
  @Test
  void pendingQueuedAndRunningCanBeCancelledForPause() {
    assertDoesNotThrow(
        () ->
            RunnerExecutionStateMachine.assertCanTransition(
                REX, RunnerExecutionStatus.PENDING, RunnerExecutionStatus.CANCELLED_FOR_PAUSE));
    assertDoesNotThrow(
        () ->
            RunnerExecutionStateMachine.assertCanTransition(
                REX, RunnerExecutionStatus.QUEUED, RunnerExecutionStatus.CANCELLED_FOR_PAUSE));
    assertDoesNotThrow(
        () ->
            RunnerExecutionStateMachine.assertCanTransition(
                REX, RunnerExecutionStatus.RUNNING, RunnerExecutionStatus.CANCELLED_FOR_PAUSE));
  }

  // Story 4.8 (Reconciliation 5) — pause deliberately has NO awaiting_manual edge (a cancelled
  // manual park has no re-park path on resume; takeover keeps its edge because it is terminal).
  @Test
  void awaitingManualCannotBeCancelledForPause() {
    DomainException error =
        assertThrows(
            DomainException.class,
            () ->
                RunnerExecutionStateMachine.assertCanTransition(
                    REX,
                    RunnerExecutionStatus.AWAITING_MANUAL,
                    RunnerExecutionStatus.CANCELLED_FOR_PAUSE));
    assertEquals(DomainErrorCode.ILLEGAL_TRANSITION, error.errorCode());
    assertEquals("transition_not_allowed", error.details().get("reason"));
  }

  @Test
  void cancelledForPauseIsTerminalAndLocked() {
    assertTrue(RunnerExecutionStateMachine.isTerminal(RunnerExecutionStatus.CANCELLED_FOR_PAUSE));
    for (RunnerExecutionStatus target : RunnerExecutionStatus.values()) {
      assertThrows(
          DomainException.class,
          () ->
              RunnerExecutionStateMachine.assertCanTransition(
                  REX, RunnerExecutionStatus.CANCELLED_FOR_PAUSE, target),
          () -> "terminal cancelled_for_pause must not transition to " + target);
    }
  }
}
