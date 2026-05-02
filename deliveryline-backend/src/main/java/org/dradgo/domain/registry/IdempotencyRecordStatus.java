package org.dradgo.domain.registry;

import java.util.Map;

public enum IdempotencyRecordStatus implements RegistryValue {
	RESERVED("reserved"),
	COMPLETED("completed"),
	FAILED("failed");

	private static final Map<String, IdempotencyRecordStatus> LOOKUP = RegistryParsers.index(values());

	private final String value;

	IdempotencyRecordStatus(String value) {
		this.value = value;
	}

	@Override
	public String value() {
		return value;
	}

	static IdempotencyRecordStatus fromValue(String rawValue) {
		return fromValue(rawValue, null);
	}

	public static IdempotencyRecordStatus fromValue(String rawValue, String field) {
		return RegistryParsers.parse("IdempotencyRecordStatus", rawValue, field, LOOKUP);
	}
}
