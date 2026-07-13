package org.dradgo.adapters.rest;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Story 4.12 (AC2, FR from epic-04) — request body for {@code POST
 * /api/v1/workflows/{workflowRunId}/rerun-from-step}. Rerun-from-step is an operator-governance
 * recovery action restricted to the {@code SafeRerunStep} boundaries, so {@code role} must equal
 * {@code workflow_owner}, validated at the boundary as the typed {@link
 * org.dradgo.domain.registry.DomainErrorCode#INVALID_REVIEWER_ROLE_FOR_ENDPOINT} then DISCARDED —
 * {@link org.dradgo.application.recovery.RecoveryService#rerunFromStep} hard-codes {@code
 * reviewer_role='workflow_owner'} on the {@code recovery_actions} insert. Mirrors {@link
 * ResumeWorkflowRequest} / {@link ReconcileWorkflowRequest} (the 4.10/4.11 siblings), diverging in
 * the two domain fields below.
 *
 * <p><strong>{@code targetStep} is a plain {@code String} with NO {@code @NotBlank} (Reconciliation
 * 5).</strong> {@code RecoveryService.rerunFromStep} validates it via {@code resolveTargetState} →
 * {@code SafeRerunStep.fromValue}, which throws the typed {@code INVALID_RERUN_TARGET_STEP} (400)
 * on null/blank/unknown. Typing the field as the {@link org.dradgo.domain.registry.SafeRerunStep}
 * enum would make Jackson surface a generic {@code INVALID_COMMAND_PAYLOAD} on an unknown value; a
 * {@code @NotBlank} would preempt the target-step code on blank. Either loses the typed code (epic
 * AC4). The two safe boundaries are surfaced to OpenAPI via {@code @Schema(allowableValues =
 * {...})} for the story 4.22 Decision-Bar dropdown — an enum <em>constraint</em> on a {@code
 * String} field, NOT a separate named component.
 *
 * <p><strong>⚠️ {@code reasonText} carries NO {@code @NotBlank} — the DEFINING divergence from
 * reconcile (4.11), which DID {@code @NotBlank} it (Reconciliation 6).</strong> {@code
 * RecoveryService.rerunFromStep} requires a non-blank reason via {@code requireReasonText} (Step 3,
 * {@code RecoveryService.java:1908}), throwing the DISTINCT typed code {@code MISSING_REASON_TEXT}
 * (400) — NOT {@code INVALID_COMMAND_PAYLOAD}. Epic AC4 explicitly lists {@code
 * MISSING_REASON_TEXT}, so it MUST be reachable. A boundary {@code @NotBlank} (fired by
 * {@code @Valid} before the controller body) would preempt it as {@code INVALID_COMMAND_PAYLOAD}
 * and break epic AC4. In reconcile the service maps a blank reason to {@code
 * INVALID_COMMAND_PAYLOAD} either way, so 4.11 could {@code @NotBlank} it — do NOT copy that here.
 * {@code requiredMode = REQUIRED} still documents "required" in OpenAPI (doc-only). The idempotency
 * replay pre-check runs BEFORE this guard ({@code RecoveryService.java:1891-1902}) so a minimal
 * retry that omits the reason still replays.
 *
 * <p>{@code @JsonIgnoreProperties(ignoreUnknown = false)} rejects unknown wire fields as {@code
 * INVALID_COMMAND_PAYLOAD} so a mis-shaped body fails fast rather than silently dropping data.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
@Schema(
    name = "RerunFromStepRequest",
    description = "Rerun a governed workflow run from a safe step boundary (rerun-from-step).")
public record RerunFromStepRequest(
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
                "Safe step boundary to rerun the run from. Validated by the service (no @NotBlank"
                    + " and not typed as the enum) so the typed INVALID_RERUN_TARGET_STEP code is"
                    + " reachable.",
            allowableValues = {"investigating", "executing"},
            example = "investigating")
        String targetStep,
    @Size(max = 512)
        @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            description =
                "Required operator note explaining the rerun. Validated by the service (no"
                    + " @NotBlank) so the typed MISSING_REASON_TEXT code is reachable — the defining"
                    + " divergence from ReconcileWorkflowRequest.reasonText.")
        String reasonText) {}
