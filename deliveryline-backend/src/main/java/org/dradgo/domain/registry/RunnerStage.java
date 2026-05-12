package org.dradgo.domain.registry;

import java.util.Map;

public enum RunnerStage implements RegistryValue {
	INVESTIGATION("investigation"),
	EXECUTION("execution");

	private static final Map<String, RunnerStage> LOOKUP = RegistryParsers.index(values());

	private final String value;

	RunnerStage(String value) {
		this.value = value;
	}

	@Override
	public String value() {
		return value;
	}

	static RunnerStage fromValue(String rawValue) {
		return fromValue(rawValue, null);
	}

	public static RunnerStage fromValue(String rawValue, String field) {
		return RegistryParsers.parse("RunnerStage", rawValue, field, LOOKUP);
	}
}
