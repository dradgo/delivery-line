package org.dradgo.domain.registry;

import java.util.Map;

public enum ArtifactType implements RegistryValue {
	SPEC("spec"),
	IMPLEMENTATION_PLAN("implementationPlan"),
	PR_OUTPUT("prOutput");

	private static final Map<String, ArtifactType> LOOKUP = RegistryParsers.index(values());

	private final String value;

	ArtifactType(String value) {
		this.value = value;
	}

	@Override
	public String value() {
		return value;
	}

	static ArtifactType fromValue(String rawValue) {
		return fromValue(rawValue, null);
	}

	public static ArtifactType fromValue(String rawValue, String field) {
		return RegistryParsers.parse("ArtifactType", rawValue, field, LOOKUP);
	}
}
