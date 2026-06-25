package org.dradgo.adapters.rest;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;

/**
 * Story 3c-8 (AC1) — request body for {@code PUT /api/v1/projects/{projectId}}. Edits the mutable
 * config only (name / repository url / kinds / OpenSpec flag); the slug and public id are
 * immutable.
 *
 * <p>Story 3e-4 (AC6) — the optional per-step {@code stepRunnerKinds} map is full-replace on update
 * (the submitted map is authoritative; omit/empty clears all per-step mappings).
 */
@Schema(name = "UpdateProjectRequest", description = "Edit a project's mutable configuration.")
public record UpdateProjectRequest(
    @NotBlank
        @Size(max = 256)
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "Acme Widgets")
        String name,
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
        String runnerKind,
    @Schema(
            description =
                "Optional per-step runner mapping (step → runner kind), full-replace on update. "
                    + "Keys: spec, implementationPlan, prOutput. Values: codex, claude, manual. "
                    + "Omit or send empty to clear all per-step mappings.",
            nullable = true,
            example = "{\"spec\":\"codex\",\"prOutput\":\"manual\"}")
        Map<String, String> stepRunnerKinds) {}
