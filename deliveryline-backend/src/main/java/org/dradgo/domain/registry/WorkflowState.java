package org.dradgo.domain.registry;

import java.util.Map;

public enum WorkflowState implements RegistryValue {
	INBOX("Inbox"),
	PLANNED("Planned"),
	INVESTIGATING("Investigating"),
	WAITING_FOR_SPEC_APPROVAL("WaitingForSpecApproval"),
	EXECUTING("Executing"),
	WAITING_FOR_REVIEW("WaitingForReview"),
	COMPLETED("Completed"),
	FAILED("Failed"),
	PAUSED("Paused"),
	TAKEN_OVER("TakenOver"),
	RECONCILED("Reconciled");

	private static final Map<String, WorkflowState> LOOKUP = RegistryParsers.index(values());

	private final String value;

	WorkflowState(String value) {
		this.value = value;
	}

	@Override
	public String value() {
		return value;
	}

	static WorkflowState fromValue(String rawValue) {
		return fromValue(rawValue, null);
	}

	public static WorkflowState fromValue(String rawValue, String field) {
		return RegistryParsers.parse("WorkflowState", rawValue, field, LOOKUP);
	}

	public static WorkflowState fromNullableValue(String rawValue, String field) {
		return RegistryParsers.parseNullable("WorkflowState", rawValue, field, LOOKUP);
	}
}
