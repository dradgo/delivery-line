package org.dradgo.adapters.rest;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
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
        Map<String, String> stepRunnerKinds,
    @Schema(
            description =
                "Story 3h-1 — whether the pre-review build-validation stage runs for this project. "
                    + "Default false ⇒ BUILD skipped (pre-3h parity). Requires buildCommand to be set.",
            example = "false")
        boolean buildStageEnabled,
    @Schema(
            description =
                "Story 3h-1 — optional build command run backend-side in the workspace before review "
                    + "(null/empty = no build, BUILD skipped even if enabled).",
            nullable = true,
            example = "mvn -q -DskipTests package")
        String buildCommand,
    @Schema(
            description =
                "Story 3h-2 — whether the pre-review CPU lint gate runs for this project. Default "
                    + "false ⇒ LINT skipped (pre-3h-2 parity). Requires lintCommands to be set.",
            example = "false")
        boolean lintStageEnabled,
    @Schema(
            description =
                "Story 3h-2 — optional CPU linter commands run backend-side in the workspace before "
                    + "review (null/empty = no lint, LINT skipped even if enabled). Fail-fast: the "
                    + "first command whose exit signals critical findings parks the run.",
            nullable = true,
            example = "[\"mvn -q -DskipTests checkstyle:check\", \"npm run lint\"]")
        List<String> lintCommands) {}
