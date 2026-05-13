package org.dradgo.application.integration.linear;

import java.util.Objects;
import org.dradgo.domain.registry.DataClassification;

/**
 * Domain-shaped governed-run comment payload destined for write-back to the source ticket system
 * (Linear). The {@code body} is expected to have already been through
 * {@link org.dradgo.application.security.RedactionPolicyService RedactionPolicyService}; the
 * adapter does not redact. {@code fingerprint} is the idempotency marker — adapters embed it in
 * the comment body so re-posting the same fingerprint on the same {@code runPublicId} is a no-op
 * (AC3 idempotency requirement).
 */
public record GovernedRunComment(
	String runPublicId,
	String fingerprint,
	String body,
	DataClassification classification) {

	public GovernedRunComment {
		if (runPublicId == null || runPublicId.isBlank()) {
			throw new IllegalArgumentException("runPublicId must be non-blank");
		}
		if (fingerprint == null || fingerprint.isBlank()) {
			throw new IllegalArgumentException("fingerprint must be non-blank");
		}
		if (body == null || body.isBlank()) {
			throw new IllegalArgumentException("body must be non-blank");
		}
		Objects.requireNonNull(classification, "classification");
	}
}
