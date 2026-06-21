package org.dradgo.adapters.rest;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Story 3c-8 (AC2/R5) — non-secret confirmation for a credential set/rotate. Carries only the role,
 * a {@code configured} status, and the non-secret {@code cred_} public id — never the secret.
 */
@Schema(name = "SetCredentialResponse", description = "Non-secret credential-set confirmation.")
public record SetCredentialResponse(
    @Schema(example = "ticket_source") String role,
    @Schema(example = "configured") String status,
    @Schema(description = "Non-secret credential public id.", example = "cred_abc123")
        String credentialId) {

  public static SetCredentialResponse configured(String role, String credentialId) {
    return new SetCredentialResponse(role, "configured", credentialId);
  }
}
