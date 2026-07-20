package org.dradgo.adapters.rest;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Story 3h-4 (AC4, FR78) — request body for {@code POST
 * /api/v1/workflows/{workflowRunId}/approve-delivery}. The pre-review delivery gate is an
 * operator-governance gate, so {@code role} must be {@code workflow_owner} (validated at the
 * boundary as {@code INVALID_REVIEWER_ROLE_FOR_ENDPOINT}). {@code reasonText} is an optional
 * operator note.
 */
@Schema(
    name = "ApproveDeliveryRequest",
    description = "Dismiss the pre-review delivery gate (approve_delivery).")
public record ApproveDeliveryRequest(
    @NotBlank
        @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            description = "Governing role; must be 'workflow_owner'.",
            example = "workflow_owner")
        String role,
    @Size(max = 512) @Schema(nullable = true, description = "Optional operator note.")
        String reasonText) {}
