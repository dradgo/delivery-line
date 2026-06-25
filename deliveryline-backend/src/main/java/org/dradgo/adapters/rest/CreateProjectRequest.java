package org.dradgo.adapters.rest;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;

/**
 * Story 3c-8 (AC1) — request body for {@code POST /api/v1/projects}. The connector {@code *Kind}
 * fields are wire strings parsed to {@code ConnectorKind} in the service (a bad value → typed 400).
 *
 * <p>Story 3e-4 (AC6) — adds the optional per-step {@code stepRunnerKinds} map (step → runner
 * kind); a bad step or kind is parsed to a typed 400 in the service ({@code
 * ProjectRunnerStep}/{@code RunnerKind} fromValue), never a 500.
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
        String runnerKind,
    @Schema(
            description =
                "Optional per-step runner mapping (step → runner kind). Keys: spec, "
                    + "implementationPlan, prOutput. Values: codex, claude, manual. A step omitted "
                    + "from the map uses the project-wide runnerKind default, else the global "
                    + "per-stage kind. Resolves more specifically than runnerKind.",
            nullable = true,
            example = "{\"spec\":\"codex\",\"prOutput\":\"manual\"}")
        Map<String, String> stepRunnerKinds) {}
