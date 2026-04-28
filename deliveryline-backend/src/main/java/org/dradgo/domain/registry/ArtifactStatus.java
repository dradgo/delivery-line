package org.dradgo.domain.registry;

import java.util.Map;

public enum ArtifactStatus implements RegistryValue {
	PENDING("pending"),
	AVAILABLE("available"),
	FAILED("failed"),
	LATE_OR_STALE("late_or_stale");

	private static final Map<String, ArtifactStatus> LOOKUP = RegistryParsers.index(values());

	private final String value;

	ArtifactStatus(String value) {
		this.value = value;
	}

	@Override
	public String value() {
		return value;
	}

	static ArtifactStatus fromValue(String rawValue) {
		return fromValue(rawValue, null);
	}

	public static ArtifactStatus fromValue(String rawValue, String field) {
		return RegistryParsers.parse("ArtifactStatus", rawValue, field, LOOKUP);
	}
}
