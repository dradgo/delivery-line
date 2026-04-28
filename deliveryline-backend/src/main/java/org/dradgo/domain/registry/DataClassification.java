package org.dradgo.domain.registry;

import java.util.Map;

public enum DataClassification implements RegistryValue {
	LOCAL_ONLY("local-only"),
	SHAREABLE_REDACTED("shareable-redacted"),
	SHAREABLE_FULL("shareable-full"),
	DERIVED_PUBLIC_SAFE("derived-public-safe");

	private static final Map<String, DataClassification> LOOKUP = RegistryParsers.index(values());

	private final String value;

	DataClassification(String value) {
		this.value = value;
	}

	@Override
	public String value() {
		return value;
	}

	static DataClassification fromValue(String rawValue) {
		return fromValue(rawValue, null);
	}

	public static DataClassification fromValue(String rawValue, String field) {
		return RegistryParsers.parse("DataClassification", rawValue, field, LOOKUP);
	}
}
