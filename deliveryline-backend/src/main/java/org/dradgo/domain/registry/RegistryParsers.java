package org.dradgo.domain.registry;

import java.util.LinkedHashMap;
import java.util.Map;
import org.dradgo.domain.DomainException;

final class RegistryParsers {

	private RegistryParsers() {
	}

	static <E extends Enum<E> & RegistryValue> Map<String, E> index(E[] values) {
		Map<String, E> lookup = new LinkedHashMap<>();
		for (E value : values) {
			E previous = lookup.put(value.value(), value);
			if (previous != null) {
				throw new IllegalStateException(
					"Duplicate registry value '" + value.value()
						+ "' on " + value.getClass().getSimpleName()
						+ " — collision between " + previous + " and " + value);
			}
		}
		return java.util.Collections.unmodifiableMap(lookup);
	}

	static <E> E parse(String registry, String rawValue, String field, Map<String, E> lookup) {
		if (rawValue == null) {
			Map<String, Object> details = new LinkedHashMap<>();
			details.put("registry", registry);
			details.put("value", null);
			details.put("reason", "null_value");
			if (field != null) {
				details.put("field", field);
			}
			throw new DomainException(
				DomainErrorCode.UNKNOWN_REGISTRY_VALUE,
				"Null value for registry " + registry + (field != null ? " (field: " + field + ")" : ""),
				details);
		}

		E value = lookup.get(rawValue);
		if (value != null) {
			return value;
		}

		Map<String, Object> details = new LinkedHashMap<>();
		details.put("registry", registry);
		details.put("value", rawValue);
		if (field != null) {
			details.put("field", field);
		}

		throw new DomainException(
			DomainErrorCode.UNKNOWN_REGISTRY_VALUE,
			"Unknown value '" + rawValue + "' for registry " + registry,
			details);
	}

	static <E> E parseNullable(String registry, String rawValue, String field, Map<String, E> lookup) {
		if (rawValue == null) {
			return null;
		}
		return parse(registry, rawValue, field, lookup);
	}
}
