package org.dradgo.domain.registry;

import java.util.Map;

public enum ActorType implements RegistryValue {
	HUMAN("human"),
	AGENT("agent"),
	SYSTEM("system"),
	SERVICE_ACCOUNT("service_account");

	private static final Map<String, ActorType> LOOKUP = RegistryParsers.index(values());

	private final String value;

	ActorType(String value) {
		this.value = value;
	}

	@Override
	public String value() {
		return value;
	}

	static ActorType fromValue(String rawValue) {
		return fromValue(rawValue, null);
	}

	public static ActorType fromValue(String rawValue, String field) {
		return RegistryParsers.parse("ActorType", rawValue, field, LOOKUP);
	}
}
