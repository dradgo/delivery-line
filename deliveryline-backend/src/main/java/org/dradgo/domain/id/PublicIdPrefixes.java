package org.dradgo.domain.id;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.RegistryValue;

public enum PublicIdPrefixes implements RegistryValue {
	WORKFLOW_RUN("workflowRun", "run_", "ck_workflow_runs_public_id_format"),
	WORKFLOW_EVENT("workflowEvent", "evt_", "ck_workflow_events_public_id_format"),
	ARTIFACT("artifact", "art_", "ck_artifacts_public_id_format"),
	ARTIFACT_OPERATION("artifactOperation", "op_", "ck_artifact_operations_public_id_format"),
	APPROVAL("approval", "apr_", "ck_approvals_public_id_format"),
	RUNNER_EXECUTION("runnerExecution", "rex_", "ck_runner_executions_public_id_format"),
	INTEGRATION_LINK("integrationLink", "ilk_", "ck_integration_links_public_id_format"),
	RECOVERY_ACTION("recoveryAction", "rcv_", "ck_recovery_actions_public_id_format"),
	IDEMPOTENCY_RECORD("idempotencyRecord", "idm_", "ck_idempotency_records_public_id_format");

	/**
	 * Mirrors the V1 SQL CHECK shape: {@code <prefix>[A-Za-z0-9_-]{4,64}}.
	 * The full public_id therefore matches {@code ^<prefix>_[A-Za-z0-9_-]{4,64}$} (the trailing
	 * underscore is part of the prefix string).
	 */
	private static final Pattern SUFFIX_PATTERN = Pattern.compile("[A-Za-z0-9_-]{4,64}");

	private final String alias;
	private final String prefix;
	private final String constraintName;

	PublicIdPrefixes(String alias, String prefix, String constraintName) {
		this.alias = alias;
		this.prefix = prefix;
		this.constraintName = constraintName;
	}

	static {
		// Reject prefix-of-prefix relationships: a future `run_v2_` would silently shadow `run_`.
		PublicIdPrefixes[] all = values();
		for (PublicIdPrefixes a : all) {
			for (PublicIdPrefixes b : all) {
				if (a != b && a.prefix.startsWith(b.prefix)) {
					throw new IllegalStateException(
						"PublicIdPrefixes invariant: '" + a.prefix
							+ "' starts with another prefix '" + b.prefix + "'");
				}
			}
		}
	}

	public String alias() {
		return alias;
	}

	public String prefix() {
		return prefix;
	}

	public String constraintName() {
		return constraintName;
	}

	@Override
	public String value() {
		return prefix;
	}

	public static Map<String, String> prefixMap() {
		Map<String, String> values = new LinkedHashMap<>();
		for (PublicIdPrefixes prefix : values()) {
			values.put(prefix.alias(), prefix.prefix());
		}
		return Collections.unmodifiableMap(values);
	}

	public static PublicIdPrefixes fromPublicId(String publicId) {
		if (publicId == null) {
			throw missingPublicId();
		}
		if (publicId.isEmpty()) {
			throw blankPublicId(publicId);
		}
		for (PublicIdPrefixes prefix : values()) {
			if (publicId.startsWith(prefix.prefix())) {
				String suffix = publicId.substring(prefix.prefix().length());
				if (!SUFFIX_PATTERN.matcher(suffix).matches()) {
					throw malformedPublicId(publicId, prefix);
				}
				return prefix;
			}
		}
		throw invalidPrefix(publicId, null);
	}

	public static String require(String publicId, PublicIdPrefixes expected) {
		PublicIdPrefixes actual = fromPublicId(publicId);
		if (actual != expected) {
			throw invalidPrefix(publicId, expected);
		}
		return publicId;
	}

	public String format(String suffix) {
		if (suffix == null || !SUFFIX_PATTERN.matcher(suffix).matches()) {
			throw malformedPublicId(prefix + (suffix == null ? "" : suffix), this);
		}
		return prefix + suffix;
	}

	public String next() {
		return format(java.util.UUID.randomUUID().toString().replace("-", ""));
	}

	private static DomainException invalidPrefix(String publicId, PublicIdPrefixes expected) {
		Map<String, Object> details = new LinkedHashMap<>();
		details.put("registry", "PublicIdPrefixes");
		details.put("value", publicId);
		details.put("reason", "unknown_or_mismatched_prefix");
		if (expected != null) {
			details.put("expectedPrefix", expected.prefix());
		}

		return new DomainException(
			DomainErrorCode.INVALID_ID_PREFIX,
			"Unknown or mismatched public ID prefix: " + publicId,
			details);
	}

	private static DomainException missingPublicId() {
		Map<String, Object> details = new LinkedHashMap<>();
		details.put("registry", "PublicIdPrefixes");
		details.put("value", null);
		details.put("reason", "null_value");
		return new DomainException(
			DomainErrorCode.INVALID_ID_PREFIX,
			"Missing (null) public ID — public_id values are required, never null",
			details);
	}

	private static DomainException blankPublicId(String publicId) {
		Map<String, Object> details = new LinkedHashMap<>();
		details.put("registry", "PublicIdPrefixes");
		details.put("value", publicId);
		details.put("reason", "empty_value");
		return new DomainException(
			DomainErrorCode.INVALID_ID_PREFIX,
			"Empty public ID — public_id values must carry a registered prefix and a 4-64 char suffix",
			details);
	}

	private static DomainException malformedPublicId(String publicId, PublicIdPrefixes prefix) {
		Map<String, Object> details = new LinkedHashMap<>();
		details.put("registry", "PublicIdPrefixes");
		details.put("value", publicId);
		details.put("expectedPrefix", prefix.prefix());
		details.put("reason", "malformed_suffix");
		details.put("suffixPattern", SUFFIX_PATTERN.pattern());
		return new DomainException(
			DomainErrorCode.INVALID_ID_PREFIX,
			"Public ID '" + publicId + "' has prefix '" + prefix.prefix()
				+ "' but suffix does not match " + SUFFIX_PATTERN.pattern(),
			details);
	}
}
