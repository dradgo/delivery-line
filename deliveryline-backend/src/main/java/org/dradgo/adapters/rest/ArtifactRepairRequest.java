package org.dradgo.adapters.rest;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Story 4.16 (AC5 / Reconciliation 11/12) — request body for {@code POST
 * /api/v1/artifact-drift/{driftId}/repair}. {@code repairAction} is a plain {@code @Size} String
 * with inline {@code @Schema(allowableValues)} (NO {@code @NotBlank}) so the service validates it
 * and the typed {@code INVALID_REPAIR_ACTION_FOR_DRIFT_CATEGORY} / {@code
 * MISSING_REPAIR_REQUIRED_FIELD} codes stay reachable; the action-specific {@code
 * completionEvidence}/{@code backupSource} fields are optional (service-validated). {@code role} is
 * gated by {@code requireWorkflowOwnerRole} ({@code INVALID_REVIEWER_ROLE_FOR_ENDPOINT} — satisfies
 * epic AC6's {@code ACTION_NOT_ALLOWED}, OQ-7). Unknown fields fail fast ({@code
 * INVALID_COMMAND_PAYLOAD}).
 */
@JsonIgnoreProperties(ignoreUnknown = false)
@Schema(
    name = "ArtifactRepairRequest",
    description = "Apply an operator-driven repair to a detected artifact drift.")
public record ArtifactRepairRequest(
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
                "Typed repair action. Validated by the service against the drift's category (no"
                    + " @NotBlank) so the typed INVALID_REPAIR_ACTION_FOR_DRIFT_CATEGORY code is"
                    + " reachable. restore_from_backup is a forward-compat E4 stub (rejected until"
                    + " backup integration lands).",
            allowableValues = {
              "mark_operation_failed",
              "mark_operation_complete",
              "mark_payload_unavailable",
              "restore_from_backup",
              "mark_corrupted",
              "re_verify_checksum"
            },
            example = "mark_corrupted")
        String repairAction,
    @Size(max = 512)
        @Schema(
            requiredMode = Schema.RequiredMode.NOT_REQUIRED,
            nullable = true,
            description = "Optional operator note explaining the repair.")
        String reasonText,
    @Size(max = 512)
        @Schema(
            requiredMode = Schema.RequiredMode.NOT_REQUIRED,
            nullable = true,
            description =
                "Action-specific: evidence the operation actually completed. REQUIRED for"
                    + " mark_operation_complete (else MISSING_REPAIR_REQUIRED_FIELD).")
        String completionEvidence,
    @Size(max = 512)
        @Schema(
            requiredMode = Schema.RequiredMode.NOT_REQUIRED,
            nullable = true,
            description =
                "Action-specific: the backup source for restore_from_backup (E4 stub — not yet"
                    + " honored).")
        String backupSource) {}
