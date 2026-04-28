package org.dradgo.application.workflow;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.FailureCategory;
import org.dradgo.domain.registry.WorkflowState;

public final class WorkflowTransitionTable {

	private static final Set<FailureCategory> ALLOWED_RUNNER_FAILURE_CATEGORIES = Set.of(
		FailureCategory.RUNNER_TIMEOUT,
		FailureCategory.RUNNER_CRASH,
		FailureCategory.RUNNER_CONTRACT_VIOLATION,
		FailureCategory.RUNNER_NON_ZERO_EXIT);

	private final Map<WorkflowState, Set<WorkflowState>> allowedTargets;

	private WorkflowTransitionTable(Map<WorkflowState, Set<WorkflowState>> allowedTargets) {
		this.allowedTargets = allowedTargets;
	}

	public static WorkflowTransitionTable defaultTable() {
		Map<WorkflowState, Set<WorkflowState>> rules = new EnumMap<>(WorkflowState.class);
		put(rules, WorkflowState.INBOX, WorkflowState.PLANNED, WorkflowState.TAKEN_OVER, WorkflowState.RECONCILED);
		put(rules, WorkflowState.PLANNED, WorkflowState.INVESTIGATING, WorkflowState.TAKEN_OVER, WorkflowState.RECONCILED);
		put(rules, WorkflowState.INVESTIGATING, WorkflowState.WAITING_FOR_SPEC_APPROVAL, WorkflowState.TAKEN_OVER,
			WorkflowState.RECONCILED);
		put(rules, WorkflowState.WAITING_FOR_SPEC_APPROVAL, WorkflowState.EXECUTING, WorkflowState.INVESTIGATING,
			WorkflowState.TAKEN_OVER, WorkflowState.RECONCILED);
		put(rules, WorkflowState.EXECUTING, WorkflowState.WAITING_FOR_REVIEW, WorkflowState.FAILED, WorkflowState.PAUSED,
			WorkflowState.TAKEN_OVER, WorkflowState.RECONCILED);
		put(rules, WorkflowState.WAITING_FOR_REVIEW, WorkflowState.COMPLETED, WorkflowState.EXECUTING,
			WorkflowState.TAKEN_OVER, WorkflowState.RECONCILED);
		put(rules, WorkflowState.COMPLETED);
		put(rules, WorkflowState.FAILED, WorkflowState.EXECUTING, WorkflowState.INVESTIGATING, WorkflowState.TAKEN_OVER,
			WorkflowState.RECONCILED);
		put(rules, WorkflowState.PAUSED, WorkflowState.EXECUTING, WorkflowState.TAKEN_OVER, WorkflowState.RECONCILED);
		put(rules, WorkflowState.TAKEN_OVER);
		put(rules, WorkflowState.RECONCILED);
		assertCoversAllStates(rules);
		return new WorkflowTransitionTable(Map.copyOf(rules));
	}

	private static void assertCoversAllStates(Map<WorkflowState, Set<WorkflowState>> rules) {
		for (WorkflowState state : WorkflowState.values()) {
			if (!rules.containsKey(state)) {
				throw new IllegalStateException(
					"WorkflowTransitionTable is missing rules entry for state " + state.value());
			}
		}
	}

	public Set<WorkflowState> canonicalStates() {
		return allowedTargets.keySet();
	}

	public Set<WorkflowState> allowedTargetsFrom(WorkflowState source) {
		return allowedTargets.getOrDefault(Objects.requireNonNull(source, "source"), Set.of());
	}

	public void assertTransitionAllowed(
		String runId,
		WorkflowState sourceState,
		WorkflowState targetState,
		FailureCategory failureCategory,
		String reason
	) {
		Objects.requireNonNull(runId, "runId");
		Objects.requireNonNull(sourceState, "sourceState");
		Objects.requireNonNull(targetState, "targetState");

		if (!allowedTargetsFrom(sourceState).contains(targetState)) {
			throw illegalTransition(runId, sourceState, targetState, failureCategory, "target_not_allowed");
		}

		if (targetState == WorkflowState.FAILED && sourceState == WorkflowState.EXECUTING) {
			if (failureCategory == null) {
				throw illegalTransition(runId, sourceState, targetState, null, "runner_failure_category_required");
			}
			if (!ALLOWED_RUNNER_FAILURE_CATEGORIES.contains(failureCategory)) {
				throw illegalTransition(runId, sourceState, targetState, failureCategory,
					"runner_failure_category_not_allowed");
			}
		} else if (failureCategory != null) {
			throw illegalTransition(runId, sourceState, targetState, failureCategory,
				"failure_category_only_valid_for_executing_to_failed");
		}

		if ((targetState == WorkflowState.TAKEN_OVER || targetState == WorkflowState.RECONCILED)
			&& (reason == null || reason.isBlank())) {
			throw illegalTransition(runId, sourceState, targetState, failureCategory, "intervention_reason_required");
		}
	}

	private static void put(Map<WorkflowState, Set<WorkflowState>> rules, WorkflowState source, WorkflowState... targets) {
		rules.put(source, Set.of(targets));
	}

	private static DomainException illegalTransition(
		String runId,
		WorkflowState sourceState,
		WorkflowState targetState,
		FailureCategory failureCategory,
		String reason
	) {
		Map<String, Object> details = new LinkedHashMap<>();
		details.put("runId", runId);
		details.put("sourceState", sourceState.value());
		details.put("targetState", targetState.value());
		details.put("reason", reason);
		if (failureCategory != null) {
			details.put("failureCategory", failureCategory.value());
		}
		return new DomainException(
			DomainErrorCode.ILLEGAL_TRANSITION,
			"Illegal workflow transition from " + sourceState.value() + " to " + targetState.value(),
			details);
	}
}
