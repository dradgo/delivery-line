package org.dradgo.application.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.FailureCategory;
import org.dradgo.domain.registry.WorkflowState;
import org.junit.jupiter.api.Test;

class WorkflowTransitionTableTest {

	@Test
	void canonicalStatesAndAllowedTransitionsMatchTheStoryContract() {
		WorkflowTransitionTable table = WorkflowTransitionTable.defaultTable();

		assertEquals(
			Set.of(
				WorkflowState.INBOX,
				WorkflowState.PLANNED,
				WorkflowState.INVESTIGATING,
				WorkflowState.WAITING_FOR_SPEC_APPROVAL,
				WorkflowState.EXECUTING,
				WorkflowState.WAITING_FOR_REVIEW,
				WorkflowState.COMPLETED,
				WorkflowState.FAILED,
				WorkflowState.PAUSED,
				WorkflowState.TAKEN_OVER,
				WorkflowState.RECONCILED),
			table.canonicalStates());

		Map<WorkflowState, Set<WorkflowState>> expectedTargets = new LinkedHashMap<>();
		expectedTargets.put(WorkflowState.INBOX,
			Set.of(WorkflowState.PLANNED, WorkflowState.TAKEN_OVER, WorkflowState.RECONCILED));
		expectedTargets.put(WorkflowState.PLANNED,
			Set.of(WorkflowState.INVESTIGATING, WorkflowState.TAKEN_OVER, WorkflowState.RECONCILED));
		expectedTargets.put(WorkflowState.INVESTIGATING,
			Set.of(WorkflowState.WAITING_FOR_SPEC_APPROVAL, WorkflowState.TAKEN_OVER, WorkflowState.RECONCILED));
		expectedTargets.put(WorkflowState.WAITING_FOR_SPEC_APPROVAL,
			Set.of(WorkflowState.EXECUTING, WorkflowState.INVESTIGATING, WorkflowState.TAKEN_OVER, WorkflowState.RECONCILED));
		expectedTargets.put(WorkflowState.EXECUTING,
			Set.of(WorkflowState.WAITING_FOR_REVIEW, WorkflowState.FAILED, WorkflowState.PAUSED, WorkflowState.TAKEN_OVER,
				WorkflowState.RECONCILED));
		expectedTargets.put(WorkflowState.WAITING_FOR_REVIEW,
			Set.of(WorkflowState.COMPLETED, WorkflowState.EXECUTING, WorkflowState.TAKEN_OVER, WorkflowState.RECONCILED));
		expectedTargets.put(WorkflowState.COMPLETED, Set.of());
		expectedTargets.put(WorkflowState.FAILED,
			Set.of(WorkflowState.EXECUTING, WorkflowState.INVESTIGATING, WorkflowState.TAKEN_OVER, WorkflowState.RECONCILED));
		expectedTargets.put(WorkflowState.PAUSED,
			Set.of(WorkflowState.EXECUTING, WorkflowState.TAKEN_OVER, WorkflowState.RECONCILED));
		expectedTargets.put(WorkflowState.TAKEN_OVER, Set.of());
		expectedTargets.put(WorkflowState.RECONCILED, Set.of());

		for (Map.Entry<WorkflowState, Set<WorkflowState>> entry : expectedTargets.entrySet()) {
			assertEquals(entry.getValue(), table.allowedTargetsFrom(entry.getKey()), () -> "Unexpected targets from " + entry.getKey());
		}
	}

	@Test
	void everyDisallowedTransitionIsRejectedWithIllegalTransition() {
		WorkflowTransitionTable table = WorkflowTransitionTable.defaultTable();

		Map<WorkflowState, Set<WorkflowState>> allowed = new LinkedHashMap<>();
		for (WorkflowState source : WorkflowState.values()) {
			allowed.put(source, table.allowedTargetsFrom(source));
		}

		for (WorkflowState source : WorkflowState.values()) {
			for (WorkflowState target : WorkflowState.values()) {
				if (allowed.get(source).contains(target)) {
					continue;
				}
				DomainException error = assertThrows(
					DomainException.class,
					() -> table.assertTransitionAllowed(
						"run_demo1234",
						source,
						target,
						target == WorkflowState.FAILED && source == WorkflowState.EXECUTING
							? FailureCategory.RUNNER_TIMEOUT
							: null,
						"reason for " + source + "->" + target),
					() -> "Expected ILLEGAL_TRANSITION for " + source + " -> " + target);
				assertEquals(DomainErrorCode.ILLEGAL_TRANSITION, error.errorCode(),
					() -> "Wrong error code for " + source + " -> " + target);
			}
		}
	}

	@Test
	void failureCategoryIsRejectedForNonExecutingToFailedTargets() {
		WorkflowTransitionTable table = WorkflowTransitionTable.defaultTable();

		DomainException error = assertThrows(
			DomainException.class,
			() -> table.assertTransitionAllowed(
				"run_demo1234",
				WorkflowState.EXECUTING,
				WorkflowState.PAUSED,
				FailureCategory.RUNNER_TIMEOUT,
				"pause"));

		assertEquals(DomainErrorCode.ILLEGAL_TRANSITION, error.errorCode());
		assertEquals("failure_category_only_valid_for_executing_to_failed", error.details().get("reason"));
	}

	@Test
	void interventionStatesHaveNoOutboundTransitions() {
		WorkflowTransitionTable table = WorkflowTransitionTable.defaultTable();

		assertEquals(Set.of(), table.allowedTargetsFrom(WorkflowState.TAKEN_OVER));
		assertEquals(Set.of(), table.allowedTargetsFrom(WorkflowState.RECONCILED));
	}

	@Test
	void completedHasNoOutboundTransitionsAndWildcardRulesExcludeIt() {
		WorkflowTransitionTable table = WorkflowTransitionTable.defaultTable();

		assertEquals(Set.of(), table.allowedTargetsFrom(WorkflowState.COMPLETED));

		DomainException error = assertThrows(
			DomainException.class,
			() -> table.assertTransitionAllowed(
				"run_demo1234",
				WorkflowState.COMPLETED,
				WorkflowState.TAKEN_OVER,
				null,
				"manual takeover"));

		assertEquals(DomainErrorCode.ILLEGAL_TRANSITION, error.errorCode());
		assertEquals("Completed", error.details().get("sourceState"));
		assertEquals("TakenOver", error.details().get("targetState"));
	}

	@Test
	void executingToFailedRequiresAnAllowedRunnerFailureCategory() {
		WorkflowTransitionTable table = WorkflowTransitionTable.defaultTable();

		table.assertTransitionAllowed(
			"run_demo1234",
			WorkflowState.EXECUTING,
			WorkflowState.FAILED,
			FailureCategory.RUNNER_TIMEOUT,
			"runner timed out");

		DomainException missingCategory = assertThrows(
			DomainException.class,
			() -> table.assertTransitionAllowed(
				"run_demo1234",
				WorkflowState.EXECUTING,
				WorkflowState.FAILED,
				null,
				"runner failed"));
		assertEquals(DomainErrorCode.ILLEGAL_TRANSITION, missingCategory.errorCode());
		assertEquals("runner_failure_category_required", missingCategory.details().get("reason"));

		DomainException disallowedCategory = assertThrows(
			DomainException.class,
			() -> table.assertTransitionAllowed(
				"run_demo1234",
				WorkflowState.EXECUTING,
				WorkflowState.FAILED,
				FailureCategory.RUNNER_LATE_RESULT,
				"runner was late"));
		assertEquals(DomainErrorCode.ILLEGAL_TRANSITION, disallowedCategory.errorCode());
		assertEquals("runner_failure_category_not_allowed", disallowedCategory.details().get("reason"));
	}

	@Test
	void interventionTransitionsRequireANonBlankReason() {
		WorkflowTransitionTable table = WorkflowTransitionTable.defaultTable();

		DomainException error = assertThrows(
			DomainException.class,
			() -> table.assertTransitionAllowed(
				"run_demo1234",
				WorkflowState.INBOX,
				WorkflowState.TAKEN_OVER,
				null,
				"  "));

		assertEquals(DomainErrorCode.ILLEGAL_TRANSITION, error.errorCode());
		assertEquals("intervention_reason_required", error.details().get("reason"));
	}
}
