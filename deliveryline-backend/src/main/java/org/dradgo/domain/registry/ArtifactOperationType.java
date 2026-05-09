package org.dradgo.domain.registry;

import java.util.Map;

public enum ArtifactOperationType implements RegistryValue {
	CREATE("create"),
	UPDATE("update"),
	REPLACE("replace");

	private static final Map<String, ArtifactOperationType> LOOKUP = RegistryParsers.index(values());

	private final String value;

	ArtifactOperationType(String value) {
		this.value = value;
	}

	@Override
	public String value() {
		return value;
	}

	static ArtifactOperationType fromValue(String rawValue) {
		return fromValue(rawValue, null);
	}

	public static ArtifactOperationType fromValue(String rawValue, String field) {
		return RegistryParsers.parse("ArtifactOperationType", rawValue, field, LOOKUP);
	}
}
