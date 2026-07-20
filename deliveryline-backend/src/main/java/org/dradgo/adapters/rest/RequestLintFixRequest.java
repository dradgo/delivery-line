package org.dradgo.adapters.rest;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Story 3h-2 (AC5, FR76) — request body for {@code POST
 * /api/v1/workflows/{workflowRunId}/request-lint-fix}. The pre-review lint gate is an
 * operator-governance gate, so {@code role} must be {@code workflow_owner} (validated at the
 * boundary as {@code INVALID_REVIEWER_ROLE_FOR_ENDPOINT}). {@code reasonText} is an optional
 * operator note that rides the redaction-policed feedback back to the implementation runner.
 */
@Schema(
    name = "RequestLintFixRequest",
    description = "Feed the lint findings back to the implementation runner (request_lint_fix).")
public record RequestLintFixRequest(
    @NotBlank
        @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            description = "Governing role; must be 'workflow_owner'.",
            example = "workflow_owner")
        String role,
    @Size(max = 16384)
        @Schema(nullable = true, description = "Optional operator note for the fix re-dispatch.")
        String reasonText) {}
