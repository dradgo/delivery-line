package org.dradgo.adapters.rest;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Story 3c-8 (AC1) — request body for {@code POST /api/v1/projects}. The connector {@code *Kind}
 * fields are wire strings parsed to {@code ConnectorKind} in the service (a bad value → typed 400).
 */
@Schema(name = "CreateProjectRequest", description = "Create a new project.")
public record CreateProjectRequest(
    @NotBlank
        @Size(max = 256)
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "Acme Widgets")
        String name,
    @NotBlank
        @Size(max = 256)
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "acme-widgets")
        String slug,
    @Size(max = 2048) @Schema(nullable = true, example = "https://github.com/acme/widgets")
        String repositoryUrl,
    @NotBlank @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "linear")
        String ticketSourceKind,
    @NotBlank @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "github")
        String repoHostKind,
    @Schema(description = "Whether OpenSpec is enabled.", example = "false")
        boolean openspecEnabled,
    @Schema(
            description = "Optional per-project runner override; null uses defaults.",
            nullable = true,
            allowableValues = {"manual", "codex", "claude"})
        String runnerKind) {}
