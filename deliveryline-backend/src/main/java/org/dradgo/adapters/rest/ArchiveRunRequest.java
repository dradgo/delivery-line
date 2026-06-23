package org.dradgo.adapters.rest;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /api/v1/workflows/&#123;workflowRunId&#125;/archive} (story 3d-8).
 * Header-derived actor + correlation identity (the 2.13 pattern; required {@code Idempotency-Key}
 * header). {@code reason} is <strong>required</strong> — ADR 0027 mandates a who/when/why on every
 * governed hide; a blank value surfaces as {@code INVALID_COMMAND_PAYLOAD} via {@code @NotBlank}.
 * The reason is excluded from the archive idempotency fingerprint so editing wording on retry still
 * replays idempotently.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record ArchiveRunRequest(
    @Schema(description = "Why this run is being hidden (required).", example = "ticket removed")
        @NotBlank
        @Size(max = 512)
        String reason) {}
