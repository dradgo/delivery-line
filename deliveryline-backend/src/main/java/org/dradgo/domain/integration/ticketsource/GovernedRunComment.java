package org.dradgo.domain.integration.ticketsource;

import java.util.Objects;
import org.dradgo.domain.registry.DataClassification;

/**
 * Vendor-neutral governed-run comment payload destined for write-back to a source ticket system.
 * The {@code body} is expected to have already been through {@link
 * org.dradgo.application.security.RedactionPolicyService RedactionPolicyService}; the adapter does
 * not redact. {@code fingerprint} is the idempotency marker — adapters embed it in the comment body
 * so re-posting the same fingerprint on the same {@code runPublicId} is a no-op (story 3.16 AC3
 * idempotency requirement, preserved verbatim across the story 3.32 abstraction).
 */
public record GovernedRunComment(
    String runPublicId, String fingerprint, String body, DataClassification classification) {

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
