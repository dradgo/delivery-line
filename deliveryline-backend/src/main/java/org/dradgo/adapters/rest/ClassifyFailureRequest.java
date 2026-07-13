package org.dradgo.adapters.rest;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Story 4.14 (AC2, FR37/FR38 from epic-04) — request body for {@code POST
 * /api/v1/workflows/{workflowRunId}/classify-failure}. Classify is an operator-governance recovery
 * action that stamps a governed {@link org.dradgo.domain.registry.FailureTaxonomyValue} onto a
 * FAILED run for cross-run pattern analysis, so {@code role} must equal {@code workflow_owner},
 * validated at the boundary as the typed {@link
 * org.dradgo.domain.registry.DomainErrorCode#INVALID_REVIEWER_ROLE_FOR_ENDPOINT} then DISCARDED —
 * {@link org.dradgo.application.recovery.RecoveryService#classifyFailure} hard-codes {@code
 * reviewer_role='workflow_owner'} on the {@code recovery_actions} insert. Mirrors {@link
 * ReconcileWorkflowRequest} for the domain-field-before-{@code idempotencyKey} argument shape,
 * diverging in the two field postures below.
 *
 * <p><strong>{@code taxonomyValue} is a plain {@code String} with NO {@code @NotBlank}
 * (Reconciliation 4).</strong> {@code RecoveryService.classifyFailure} validates it via {@code
 * parseTaxonomyValue}, which throws the typed {@code MISSING_TAXONOMY_VALUE} (400) on null/blank
 * and {@code INVALID_TAXONOMY_VALUE} (400, {@code details.provided}) on a value not in the
 * registry; then {@code FailureTaxonomyPolicy.requireNotDeprecated} throws {@code
 * DEPRECATED_TAXONOMY_VALUE} (400, {@code details.replacementValue}) for a retired value. Typing
 * the field as the {@link org.dradgo.domain.registry.FailureTaxonomyValue} enum would make Jackson
 * surface a generic {@code INVALID_COMMAND_PAYLOAD} on an unknown value (losing {@code
 * INVALID_TAXONOMY_VALUE}); a {@code @NotBlank} would preempt {@code MISSING_TAXONOMY_VALUE}.
 * Either loses a typed code (epic AC4). The six active wire values are surfaced to OpenAPI via
 * {@code @Schema(allowableValues = {...})} for the story 4.24 taxonomy dropdown — an enum
 * <em>constraint</em> on a {@code String} field, NOT a separate named {@code FailureTaxonomyValue}
 * component. No value is deprecated today, so epic AC5's "deprecated markers" is a no-op (a future
 * retired value would be dropped from {@code allowableValues} under ADR 0035). The service is the
 * single validator of all three taxonomy codes.
 *
 * <p><strong>{@code reasonText} is GENUINELY OPTIONAL and carries NO {@code @NotBlank}
 * (Reconciliation 5 — the DEFINING divergence from reconcile/rerun/pause).</strong> {@code
 * RecoveryService.classifyFailure} blanks a null/blank reason to {@code null} with NO error ({@code
 * RecoveryService.java:1754-1755}) — there is NO {@code MISSING_REASON_TEXT} code on the classify
 * path at all. This is the {@link ResumeWorkflowRequest#reasonText} posture (genuinely optional /
 * nullable), NOT {@link ReconcileWorkflowRequest#reasonText} (required-but-service-validated). An
 * omitted {@code reasonText} returns 200.
 *
 * <p>{@code @JsonIgnoreProperties(ignoreUnknown = false)} rejects unknown wire fields as {@code
 * INVALID_COMMAND_PAYLOAD} so a mis-shaped body fails fast rather than silently dropping data.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
@Schema(
    name = "ClassifyFailureRequest",
    description = "Apply a governed failure-taxonomy classification to a failed workflow run.")
public record ClassifyFailureRequest(
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
                "Governed failure-taxonomy wire value recording the operator's judgment of WHY the"
                    + " run failed. Validated by the service (no @NotBlank) so the typed"
                    + " MISSING_/INVALID_/DEPRECATED_TAXONOMY_VALUE codes are reachable.",
            allowableValues = {
              "specification_gap",
              "context_gap",
              "agent_execution_failure",
              "review_rejection",
              "integration_or_merge_failure",
              "tooling_or_infrastructure_failure"
            },
            example = "agent_execution_failure")
        String taxonomyValue,
    @Size(max = 512)
        @Schema(
            requiredMode = Schema.RequiredMode.NOT_REQUIRED,
            nullable = true,
            description =
                "Optional operator note explaining the classification. Genuinely optional — the"
                    + " service stores a blank/absent reason as null (no MISSING_REASON_TEXT).")
        String reasonText) {}
