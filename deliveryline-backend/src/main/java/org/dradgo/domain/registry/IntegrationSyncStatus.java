package org.dradgo.domain.registry;

import java.util.Map;

public enum IntegrationSyncStatus implements RegistryValue {
	LINKED("linked"),
	SYNCED("synced"),
	STALE("stale"),
	FAILED("failed"),
	SUPERSEDED("superseded");

	private static final Map<String, IntegrationSyncStatus> LOOKUP = RegistryParsers.index(values());

	private final String value;

	IntegrationSyncStatus(String value) {
		this.value = value;
	}

	@Override
	public String value() {
		return value;
	}

	static IntegrationSyncStatus fromValue(String rawValue) {
		return fromValue(rawValue, null);
	}

	public static IntegrationSyncStatus fromValue(String rawValue, String field) {
		return RegistryParsers.parse("IntegrationSyncStatus", rawValue, field, LOOKUP);
	}
}
