package org.dradgo.domain.registry;

import java.util.Map;

public enum FailureCategory implements RegistryValue {
	RUNNER_TIMEOUT("runner_timeout"),
	RUNNER_CRASH("runner_crash"),
	RUNNER_CONTRACT_VIOLATION("runner_contract_violation"),
	RUNNER_NON_ZERO_EXIT("runner_non_zero_exit"),
	RUNNER_LATE_RESULT("runner_late_result"),
	RUNNER_DUPLICATE_RESULT("runner_duplicate_result"),
	RUNNER_MALFORMED_OUTPUT("runner_malformed_output");

	private static final Map<String, FailureCategory> LOOKUP = RegistryParsers.index(values());

	private final String value;

	FailureCategory(String value) {
		this.value = value;
	}

	@Override
	public String value() {
		return value;
	}

	static FailureCategory fromValue(String rawValue) {
		return fromValue(rawValue, null);
	}

	public static FailureCategory fromValue(String rawValue, String field) {
		return RegistryParsers.parse("FailureCategory", rawValue, field, LOOKUP);
	}

	public static FailureCategory fromNullableValue(String rawValue, String field) {
		return RegistryParsers.parseNullable("FailureCategory", rawValue, field, LOOKUP);
	}
}
