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
		assertDoesNotThrow(() -> RunnerExecutionStateMachine.assertCanTransition(REX,
			RunnerExecutionStatus.PENDING, RunnerExecutionStatus.RUNNING));
		assertDoesNotThrow(() -> RunnerExecutionStateMachine.assertCanTransition(REX,
			RunnerExecutionStatus.PENDING, RunnerExecutionStatus.COMPLETED));
		assertDoesNotThrow(() -> RunnerExecutionStateMachine.assertCanTransition(REX,
			RunnerExecutionStatus.PENDING, RunnerExecutionStatus.FAILED));
		assertDoesNotThrow(() -> RunnerExecutionStateMachine.assertCanTransition(REX,
			RunnerExecutionStatus.PENDING, RunnerExecutionStatus.TIMED_OUT));
		assertDoesNotThrow(() -> RunnerExecutionStateMachine.assertCanTransition(REX,
			RunnerExecutionStatus.PENDING, RunnerExecutionStatus.ORPHANED));
	}

	@Test
	void runningAllowsTerminalTransitions() {
		assertDoesNotThrow(() -> RunnerExecutionStateMachine.assertCanTransition(REX,
			RunnerExecutionStatus.RUNNING, RunnerExecutionStatus.COMPLETED));
		assertDoesNotThrow(() -> RunnerExecutionStateMachine.assertCanTransition(REX,
			RunnerExecutionStatus.RUNNING, RunnerExecutionStatus.FAILED));
		assertDoesNotThrow(() -> RunnerExecutionStateMachine.assertCanTransition(REX,
			RunnerExecutionStatus.RUNNING, RunnerExecutionStatus.TIMED_OUT));
		assertDoesNotThrow(() -> RunnerExecutionStateMachine.assertCanTransition(REX,
			RunnerExecutionStatus.RUNNING, RunnerExecutionStatus.ORPHANED));
	}

	@Test
	void pendingCannotGoToPending() {
		DomainException error = assertThrows(DomainException.class,
			() -> RunnerExecutionStateMachine.assertCanTransition(REX,
				RunnerExecutionStatus.PENDING, RunnerExecutionStatus.PENDING));
		assertEquals(DomainErrorCode.ILLEGAL_TRANSITION, error.errorCode());
		assertEquals("no_op_transition", error.details().get("reason"));
		assertEquals(REX, error.details().get("runnerExecutionId"));
	}

	@Test
	void runningCannotGoBackToRunning() {
		DomainException error = assertThrows(DomainException.class,
			() -> RunnerExecutionStateMachine.assertCanTransition(REX,
				RunnerExecutionStatus.RUNNING, RunnerExecutionStatus.RUNNING));
		assertEquals(DomainErrorCode.ILLEGAL_TRANSITION, error.errorCode());
	}

	@Test
	void runningCannotGoBackToPending() {
		DomainException error = assertThrows(DomainException.class,
			() -> RunnerExecutionStateMachine.assertCanTransition(REX,
				RunnerExecutionStatus.RUNNING, RunnerExecutionStatus.PENDING));
		assertEquals(DomainErrorCode.ILLEGAL_TRANSITION, error.errorCode());
		assertEquals("transition_not_allowed", error.details().get("reason"));
	}

	@Test
	void completedAndFailedRemainLocked() {
		for (RunnerExecutionStatus terminal : new RunnerExecutionStatus[] {
			RunnerExecutionStatus.COMPLETED,
			RunnerExecutionStatus.FAILED}) {
			assertTrue(RunnerExecutionStateMachine.isTerminal(terminal));
			for (RunnerExecutionStatus target : RunnerExecutionStatus.values()) {
				assertThrows(DomainException.class,
					() -> RunnerExecutionStateMachine.assertCanTransition(REX, terminal, target),
					() -> "terminal " + terminal + " must not transition to " + target);
			}
		}
	}

	@Test
	void timedOutAndOrphanedCanBeReclassifiedToFailedOnly() {
		assertDoesNotThrow(() -> RunnerExecutionStateMachine.assertCanTransition(
			REX, RunnerExecutionStatus.TIMED_OUT, RunnerExecutionStatus.FAILED));
		assertDoesNotThrow(() -> RunnerExecutionStateMachine.assertCanTransition(
			REX, RunnerExecutionStatus.ORPHANED, RunnerExecutionStatus.FAILED));
		assertThrows(DomainException.class,
			() -> RunnerExecutionStateMachine.assertCanTransition(
				REX, RunnerExecutionStatus.TIMED_OUT, RunnerExecutionStatus.COMPLETED));
		assertThrows(DomainException.class,
			() -> RunnerExecutionStateMachine.assertCanTransition(
				REX, RunnerExecutionStatus.ORPHANED, RunnerExecutionStatus.RUNNING));
		assertTrue(RunnerExecutionStateMachine.isTerminal(RunnerExecutionStatus.TIMED_OUT));
		assertTrue(RunnerExecutionStateMachine.isTerminal(RunnerExecutionStatus.ORPHANED));
	}

	@Test
	void pendingAndRunningAreNotTerminal() {
		assertFalse(RunnerExecutionStateMachine.isTerminal(RunnerExecutionStatus.PENDING));
		assertFalse(RunnerExecutionStateMachine.isTerminal(RunnerExecutionStatus.RUNNING));
	}
}
