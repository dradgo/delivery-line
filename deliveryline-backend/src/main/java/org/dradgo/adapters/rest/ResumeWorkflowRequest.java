package org.dradgo.adapters.rest;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Story 4.10 (AC2, FR from epic-04) — request body for {@code POST
 * /api/v1/workflows/{workflowRunId}/resume}. Resume is an operator-governance recovery action, so
 * {@code role} must equal {@code workflow_owner}, validated at the boundary as the typed {@link
 * org.dradgo.domain.registry.DomainErrorCode#INVALID_REVIEWER_ROLE_FOR_ENDPOINT} then DISCARDED —
 * {@link org.dradgo.application.recovery.RecoveryService#resume} hard-codes {@code
 * reviewer_role='workflow_owner'} on the {@code recovery_actions} insert. This mirrors {@link
 * ApproveLintRequest} (the live {@code workflow_owner}-gate precedent), not {@link TakeoverRequest}
 * (whose {@code reviewerRole} gates on {@code developer}).
 *
 * <p>{@code reasonText} is <strong>optional</strong> (epic AC2 — {@code reasonText?}): {@code
 * RecoveryService.resume} accepts a null reason. Unlike {@code /takeover}, no {@code @NotBlank}
 * guard applies. {@code @JsonIgnoreProperties(ignoreUnknown = false)} rejects unknown wire fields
 * as {@code INVALID_COMMAND_PAYLOAD} so a mis-shaped body fails fast rather than silently dropping
 * data.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
@Schema(name = "ResumeWorkflowRequest", description = "Resume a paused workflow run (resume).")
public record ResumeWorkflowRequest(
    @NotBlank
        @Size(max = 128)
        @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            description = "Governing role; must be 'workflow_owner'.",
            example = "workflow_owner")
        String role,
    @Size(max = 512) @Schema(nullable = true, description = "Optional operator note.")
        String reasonText) {}
