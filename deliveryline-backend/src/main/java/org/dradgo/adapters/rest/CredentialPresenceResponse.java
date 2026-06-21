package org.dradgo.adapters.rest;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Story 3c-8 (AC1/R5) — per-role credential <strong>presence</strong> on a {@code ProjectResponse}.
 * Conveys only whether a credential is configured for the role; it NEVER carries the secret value
 * (plaintext or ciphertext) — the credential store is write-only end-to-end.
 */
@Schema(
    name = "CredentialPresence",
    description = "Per-role credential presence (never the value).")
public record CredentialPresenceResponse(
    @Schema(description = "Connector role.", example = "ticket_source") String role,
    @Schema(
            description = "Whether a credential is configured for this role.",
            example = "configured",
            allowableValues = {"configured", "not_configured"})
        String status) {

  public static final String CONFIGURED = "configured";
  public static final String NOT_CONFIGURED = "not_configured";

  public static CredentialPresenceResponse of(String role, boolean configured) {
    return new CredentialPresenceResponse(role, configured ? CONFIGURED : NOT_CONFIGURED);
  }
}
