package org.dradgo.domain.registry;

import java.util.Map;

public enum AllowedAction implements RegistryValue {
	APPROVE_SPEC("approve_spec"),
	REJECT_SPEC("reject_spec"),
	ANSWER_CLARIFICATION("answer_clarification"),
	VIEW_ONLY("view_only"),
	AWAIT_OUTCOME("await_outcome"),
	RETRY("retry"),
	VIEW_DIAGNOSTICS("view_diagnostics"),
	CLEAR_ESCALATION_MARKER("clear_escalation_marker");

	private static final Map<String, AllowedAction> LOOKUP = RegistryParsers.index(values());

	private final String value;

	AllowedAction(String value) {
		this.value = value;
	}

	@Override
	public String value() {
		return value;
	}

	static AllowedAction fromValue(String rawValue) {
		return fromValue(rawValue, null);
	}

	public static AllowedAction fromValue(String rawValue, String field) {
		return RegistryParsers.parse("AllowedAction", rawValue, field, LOOKUP);
	}
}
