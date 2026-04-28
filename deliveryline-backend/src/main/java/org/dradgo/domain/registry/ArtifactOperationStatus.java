package org.dradgo.domain.registry;

import java.util.Map;

public enum ArtifactOperationStatus implements RegistryValue {
	PENDING("pending"),
	COMPLETE("complete"),
	FAILED("failed"),
	FAILED_ORPHAN("failed_orphan");

	private static final Map<String, ArtifactOperationStatus> LOOKUP = RegistryParsers.index(values());

	private final String value;

	ArtifactOperationStatus(String value) {
		this.value = value;
	}

	@Override
	public String value() {
		return value;
	}

	static ArtifactOperationStatus fromValue(String rawValue) {
		return fromValue(rawValue, null);
	}

	public static ArtifactOperationStatus fromValue(String rawValue, String field) {
		return RegistryParsers.parse("ArtifactOperationStatus", rawValue, field, LOOKUP);
	}
}
