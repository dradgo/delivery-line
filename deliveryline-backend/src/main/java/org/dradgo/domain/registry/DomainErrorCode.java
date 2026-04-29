package org.dradgo.domain.registry;

import java.util.Map;

/**
 * Stable wire form is the explicit {@code wireValue} (decoupled from the enum constant name).
 * Renaming an enum constant must keep its {@code wireValue} identical or it is a wire-breaking change.
 */
public enum DomainErrorCode implements RegistryValue {
	ILLEGAL_TRANSITION("ILLEGAL_TRANSITION"),
	IDEMPOTENCY_KEY_CONFLICT("IDEMPOTENCY_KEY_CONFLICT"),
	APPROVAL_VERSION_MISMATCH("APPROVAL_VERSION_MISMATCH"),
	CONCURRENT_TRANSITION_CONFLICT("CONCURRENT_TRANSITION_CONFLICT"),
	RUNNER_TIMEOUT("RUNNER_TIMEOUT"),
	RUNNER_CONTRACT_VIOLATION("RUNNER_CONTRACT_VIOLATION"),
	ARTIFACT_PAYLOAD_UNAVAILABLE("ARTIFACT_PAYLOAD_UNAVAILABLE"),
	INVALID_COMMAND_PAYLOAD("INVALID_COMMAND_PAYLOAD"),
	UNKNOWN_REGISTRY_VALUE("UNKNOWN_REGISTRY_VALUE"),
	INVALID_ID_PREFIX("INVALID_ID_PREFIX"),
	RUN_NOT_FOUND("RUN_NOT_FOUND"),
	DOCTOR_POSTGRES_UNREACHABLE("DOCTOR_POSTGRES_UNREACHABLE"),
	DOCTOR_FLYWAY_FAILED("DOCTOR_FLYWAY_FAILED"),
	DOCTOR_REST_BIND_UNAVAILABLE("DOCTOR_REST_BIND_UNAVAILABLE"),
	DOCTOR_DOCKER_MISSING("DOCTOR_DOCKER_MISSING"),
	DOCTOR_CONFIG_PERMISSIONS_UNSAFE("DOCTOR_CONFIG_PERMISSIONS_UNSAFE"),
	DOCTOR_UNSUPPORTED_ENVIRONMENT("DOCTOR_UNSUPPORTED_ENVIRONMENT"),
	DOCTOR_ARTIFACT_DIR_UNWRITABLE("DOCTOR_ARTIFACT_DIR_UNWRITABLE");

	private static final Map<String, DomainErrorCode> LOOKUP = RegistryParsers.index(values());

	private final String wireValue;

	DomainErrorCode(String wireValue) {
		this.wireValue = wireValue;
	}

	@Override
	public String value() {
		return wireValue;
	}

	public String wireValue() {
		return wireValue;
	}

	static DomainErrorCode fromValue(String rawValue) {
		return fromValue(rawValue, null);
	}

	public static DomainErrorCode fromValue(String rawValue, String field) {
		return RegistryParsers.parse("DomainErrorCode", rawValue, field, LOOKUP);
	}
}
