package org.dradgo.adapters.rest;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Story 4.13 (AC2, FR from epic-04) — request body for {@code POST
 * /api/v1/workflows/{workflowRunId}/pause}. Pause is an operator-governance recovery action
 * (halting orchestrator dispatch and cancelling in-flight + queued runner work without taking
 * over), so {@code role} must equal {@code workflow_owner}, validated at the boundary as the typed
 * {@link org.dradgo.domain.registry.DomainErrorCode#INVALID_REVIEWER_ROLE_FOR_ENDPOINT} then
 * DISCARDED — {@link org.dradgo.application.recovery.RecoveryService#pause} hard-codes {@code
 * reviewer_role='workflow_owner'} on the {@code recovery_actions} insert. Mirrors {@link
 * ResumeWorkflowRequest} / {@link ReconcileWorkflowRequest} / {@link RerunFromStepRequest} (the
 * 4.10/4.11/4.12 siblings), diverging only in the {@code reasonText} validation posture below.
 *
 * <p><strong>⚠️ {@code reasonText} carries NO {@code @NotBlank} — the DEFINING trap of this story
 * (shared with rerun 4.12, OPPOSITE of reconcile 4.11 and resume 4.10) (Reconciliation 5).</strong>
 * {@link org.dradgo.application.recovery.RecoveryService#pause} requires a non-blank reason via
 * {@code requirePauseReasonText} (Step 1, {@code RecoveryService.java:1145}), throwing the DISTINCT
 * typed code {@code MISSING_REASON_TEXT} (400) — NOT {@code INVALID_COMMAND_PAYLOAD}. Story 4.8 AC2
 * makes {@code reasonText} required and the service enforces it with this typed code, so it MUST be
 * reachable. A boundary {@code @NotBlank} (fired by {@code @Valid} before the controller body)
 * would preempt it as {@code INVALID_COMMAND_PAYLOAD} and mask {@code MISSING_REASON_TEXT}.
 *
 * <p>This diverges from BOTH sibling postures: {@link ResumeWorkflowRequest#reasonText()} is
 * genuinely OPTIONAL ({@code @Schema(nullable = true)}, no service guard at all), while {@link
 * ReconcileWorkflowRequest}'s {@code reasonText} IS {@code @NotBlank} (its service maps a blank
 * reason to {@code INVALID_COMMAND_PAYLOAD} either way). Pause's reason is REQUIRED-but-service-
 * validated: NO {@code @NotBlank}, and NO {@code @Schema(nullable = true)} optional posture. Unlike
 * reconcile/rerun, {@code MISSING_REASON_TEXT} fires BEFORE the idempotency replay pre-check
 * ({@code RecoveryService.java:1145} vs {@code :1150}) because {@code reasonText} composes the
 * fingerprint identity.
 *
 * <p>{@code @JsonIgnoreProperties(ignoreUnknown = false)} rejects unknown wire fields as {@code
 * INVALID_COMMAND_PAYLOAD} so a mis-shaped body fails fast rather than silently dropping data.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
@Schema(name = "PauseWorkflowRequest", description = "Manually pause a workflow run (pause).")
public record PauseWorkflowRequest(
    @NotBlank
        @Size(max = 128)
        @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            description = "Governing role; must be 'workflow_owner'.",
            example = "workflow_owner")
        String role,
    @Size(max = 512)
        @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            description =
                "Required operator note explaining the pause. Validated by the service (no"
                    + " @NotBlank) so the typed MISSING_REASON_TEXT code is reachable — the defining"
                    + " divergence from ResumeWorkflowRequest.reasonText (genuinely optional) and"
                    + " ReconcileWorkflowRequest.reasonText (@NotBlank).")
        String reasonText) {}
