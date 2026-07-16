package org.dradgo.adapters.rest;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Story 4.16a (AC9 / Reconciliation 9/10) — request body for {@code POST
 * /api/v1/artifacts/{artifactId}/reconcile-lineage}. {@code lineageAction} is a plain {@code @Size}
 * String with inline {@code @Schema(allowableValues)} (NO {@code @NotBlank}) so the service
 * validates it and the typed {@code INVALID_LINEAGE_RECOVERY_ACTION} / {@code
 * MISSING_LINEAGE_RECOVERY_FIELD} codes stay reachable; {@code chosenParentArtifactId} is
 * action-specific (REQUIRED for {@code reattach_to_existing_lineage}, service-validated). {@code
 * role} is gated by {@code requireWorkflowOwnerRole} ({@code INVALID_REVIEWER_ROLE_FOR_ENDPOINT}).
 * Unknown fields fail fast ({@code INVALID_COMMAND_PAYLOAD}).
 */
@JsonIgnoreProperties(ignoreUnknown = false)
@Schema(
    name = "ArtifactLineageReconcileRequest",
    description = "Apply an operator-driven lineage-recovery action to an ambiguous artifact.")
public record ArtifactLineageReconcileRequest(
    @NotBlank
        @Size(max = 128)
        @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            description = "Governing role; must be 'workflow_owner'.",
            example = "workflow_owner")
        String role,
    @Size(max = 64)
        @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            description =
                "Typed lineage-recovery action. Validated by the service (no @NotBlank) so the typed"
                    + " INVALID_LINEAGE_RECOVERY_ACTION code is reachable.",
            allowableValues = {
              "reattach_to_existing_lineage",
              "terminate_ambiguous_lineage",
              "create_explicit_fork"
            },
            example = "reattach_to_existing_lineage")
        String lineageAction,
    @Size(max = 64)
        @Schema(
            requiredMode = Schema.RequiredMode.NOT_REQUIRED,
            nullable = true,
            description =
                "Action-specific: the art_ id of the lineage leaf to re-parent onto. REQUIRED for"
                    + " reattach_to_existing_lineage (else MISSING_LINEAGE_RECOVERY_FIELD).",
            example = "art_9f3b2c1d")
        String chosenParentArtifactId,
    @Size(max = 512)
        @Schema(
            requiredMode = Schema.RequiredMode.NOT_REQUIRED,
            nullable = true,
            description = "Optional operator note explaining the lineage-recovery decision.")
        String reasonText) {}
