package org.dradgo.adapters.rest;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Story 4.11 (AC2, FR from epic-04) — request body for {@code POST
 * /api/v1/workflows/{workflowRunId}/reconcile}. Reconcile is an operator-governance recovery action
 * over an unresolved {@code integration_conflicts} row, so {@code role} must equal {@code
 * workflow_owner}, validated at the boundary as the typed {@link
 * org.dradgo.domain.registry.DomainErrorCode#INVALID_REVIEWER_ROLE_FOR_ENDPOINT} then DISCARDED —
 * {@link org.dradgo.application.recovery.RecoveryService#reconcile} hard-codes {@code
 * reviewer_role='workflow_owner'} on the {@code recovery_actions} insert. Mirrors {@link
 * ResumeWorkflowRequest} (the 4.10 sibling), diverging only in the three domain fields below.
 *
 * <p><strong>{@code resolutionDecision} is a plain {@code String} with NO {@code @NotBlank}
 * (Reconciliation 6).</strong> {@code RecoveryService.reconcile} validates it via {@code
 * parseDecision}, which throws the typed {@code MISSING_RECONCILIATION_DECISION} (400) on
 * null/blank and {@code INVALID_RECONCILIATION_DECISION} (400) on an unknown value. Typing the
 * field as the {@link org.dradgo.domain.registry.ReconciliationDecision} enum would make Jackson
 * surface a generic {@code INVALID_COMMAND_PAYLOAD} on an unknown value; a {@code @NotBlank} would
 * preempt the missing-decision code. Either loses the two typed codes (epic AC4). The enum values
 * are surfaced to OpenAPI via {@code @Schema(allowableValues = {...})} for the story 4.23 dropdown
 * — an enum <em>constraint</em> on a {@code String} field, NOT a separate named component.
 *
 * <p><strong>{@code reasonText} is required on a fresh reconcile but carries NO {@code @NotBlank}
 * (same treatment as {@code resolutionDecision} — Reconciliation 6/6b, refined by code review
 * 2026-07-13).</strong> {@code RecoveryService.reconcile} runs its idempotency replay pre-check
 * BEFORE input validation ({@code RecoveryService.java:1568-1582}) precisely so a genuine retry of
 * an already-succeeded reconcile replays even when the client omits {@code reasonText}. A boundary
 * {@code @NotBlank} (fired by {@code @Valid} before the controller body) would reject that minimal
 * retry with {@code INVALID_COMMAND_PAYLOAD} (400) and never reach the replay path that returns the
 * idempotent 200. On the fresh path the service's {@code resolveReconcileReasonText} still maps a
 * blank reason to {@code INVALID_COMMAND_PAYLOAD}, so the required-field contract holds — the
 * service is the single validator, mirroring the decision field. {@code requiredMode = REQUIRED}
 * still documents "required" in OpenAPI (doc-only, like {@code resolutionDecision}).
 *
 * <p>{@code @JsonIgnoreProperties(ignoreUnknown = false)} rejects unknown wire fields as {@code
 * INVALID_COMMAND_PAYLOAD} so a mis-shaped body fails fast rather than silently dropping data.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
@Schema(
    name = "ReconcileWorkflowRequest",
    description = "Reconcile an unresolved integration conflict on a workflow run (reconcile).")
public record ReconcileWorkflowRequest(
    @NotBlank
        @Size(max = 128)
        @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            description = "Governing role; must be 'workflow_owner'.",
            example = "workflow_owner")
        String role,
    @NotBlank
        @Size(max = 64)
        @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            description = "Public id of the unresolved integration conflict to reconcile.",
            example = "icf_0190000000007000800000000000abcd")
        String conflictId,
    @Size(max = 64)
        @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            description =
                "Reconciliation decision governing how the internal/external state divergence is"
                    + " resolved. Validated by the service (no @NotBlank) so the typed"
                    + " MISSING_/INVALID_RECONCILIATION_DECISION codes are reachable.",
            allowableValues = {
              "accept_external_state",
              "accept_internal_state",
              "mark_completed_externally",
              "mark_failed_externally"
            },
            example = "accept_external_state")
        String resolutionDecision,
    @Size(max = 512)
        @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            description = "Required operator note explaining the reconciliation decision.")
        String reasonText) {}
