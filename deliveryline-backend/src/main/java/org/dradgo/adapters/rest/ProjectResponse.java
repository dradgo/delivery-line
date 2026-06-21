package org.dradgo.adapters.rest;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.List;
import org.dradgo.domain.project.Project;

/**
 * Story 3c-8 (AC1/AC6) — wire shape for a project. Carries project configuration, per-role
 * credential <strong>presence</strong> (never a secret value), and the backend-computed,
 * status-derived {@code allowedActions}. Direct resource shape — no envelope (matches {@code
 * WorkflowController}).
 */
@Schema(name = "Project", description = "A configured project (never carries a credential value).")
public record ProjectResponse(
    @Schema(description = "Project public id.", example = "prj_abc123") String id,
    @Schema(description = "Human-readable project name.", example = "Acme Widgets") String name,
    @Schema(description = "Stable URL/CLI slug.", example = "acme-widgets") String slug,
    @Schema(
            description = "Project status.",
            example = "active",
            allowableValues = {"active", "disabled"})
        String status,
    @Schema(
            description = "Repository URL (nullable).",
            nullable = true,
            example = "https://github.com/acme/widgets")
        String repositoryUrl,
    @Schema(description = "Ticket-source connector kind.", example = "linear")
        String ticketSourceKind,
    @Schema(description = "Repository-host connector kind.", example = "github")
        String repoHostKind,
    @Schema(description = "Whether OpenSpec is enabled for this project.") boolean openspecEnabled,
    @Schema(description = "Creation timestamp (UTC).") OffsetDateTime createdAt,
    @Schema(description = "Per-role credential presence (never the value).")
        List<CredentialPresenceResponse> credentials,
    @Schema(
            description =
                "Backend-derived actions valid for this project's current status. The UI must "
                    + "gracefully ignore unknown values for forward compatibility.",
            example = "[\"edit\", \"disable\", \"set_credential\", \"test_connection\"]")
        List<String> allowedActions) {

  public static ProjectResponse from(
      Project project, List<CredentialPresenceResponse> credentials, List<String> allowedActions) {
    return new ProjectResponse(
        project.publicId(),
        project.name(),
        project.slug(),
        project.status().value(),
        project.repositoryUrl(),
        project.ticketSourceKind().value(),
        project.repoHostKind().value(),
        project.openspecEnabled(),
        project.createdAt(),
        credentials,
        allowedActions);
  }
}
