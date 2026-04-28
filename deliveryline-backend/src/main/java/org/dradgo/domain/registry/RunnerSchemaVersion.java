package org.dradgo.domain.registry;

import java.util.Map;

public enum RunnerSchemaVersion implements RegistryValue {
	V1(1);

	private static final Map<String, RunnerSchemaVersion> LOOKUP = RegistryParsers.index(values());

	private final int version;

	RunnerSchemaVersion(int version) {
		this.version = version;
	}

	public int version() {
		return version;
	}

	@Override
	public String value() {
		return Integer.toString(version);
	}

	static RunnerSchemaVersion fromValue(String rawValue) {
		return fromValue(rawValue, null);
	}

	public static RunnerSchemaVersion fromValue(String rawValue, String field) {
		return RegistryParsers.parse("RunnerSchemaVersion", rawValue, field, LOOKUP);
	}
}
