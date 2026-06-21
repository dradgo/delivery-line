package org.dradgo.adapters.rest;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Story 3c-8 (AC2/R5) — request body for {@code PUT
 * /api/v1/projects/{projectId}/credentials/{role}}.
 *
 * <p>The {@code secret} is the <strong>only</strong> place a plaintext credential enters the
 * system. It is marked {@code WRITE_ONLY} so the OpenAPI snapshot proves it is request-only and
 * never appears in any response schema; it is never echoed, never logged, and excluded from the
 * idempotency fingerprint.
 */
@Schema(name = "SetCredentialRequest", description = "Set or rotate a connector credential.")
public record SetCredentialRequest(
    @NotBlank
        @Size(max = 8192)
        @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            accessMode = Schema.AccessMode.WRITE_ONLY,
            description = "The plaintext connector credential. Write-only — never returned.")
        String secret) {}
