package org.dradgo.adapters.rest;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /api/v1/workflows/&#123;workflowRunId&#125;/accept-implementation}
 * (story 3.23). The technical-approval twin of {@link ApproveSpecRequest} — same wire shape
 * (header-derived actor + correlation identity; required {@code Idempotency-Key} header), surfacing
 * the {@code done} story 3.20 {@code WorkflowCommandService.acceptImplementation} service.
 *
 * <p>The verbose wire names {@code expectedArtifactVersion} / {@code expectedContextBundleVersion}
 * map to the short {@code artifactVersion} / {@code contextVersion} command fields on {@link
 * org.dradgo.application.workflow.commands.AcceptImplementationCommand} (Trap T1, documented on
 * that record): the short names ARE the expected versions the reviewer reviewed against.
 *
 * <p>{@code reviewerRole} must be {@code developer} — the controller (story 3.23 Task 3) enforces
 * it at the boundary with the typed {@link
 * org.dradgo.domain.registry.DomainErrorCode#INVALID_REVIEWER_ROLE_FOR_ENDPOINT} code, and does NOT
 * route the value through {@link org.dradgo.application.workflow.ApprovalReviewerRoleResolver}
 * (whose blank-fallback default {@code product_reviewer} would mask the mismatch). This is the key
 * delta from {@code ApproveSpecRequest}, whose role IS resolver-defaulted.
 *
 * <p>{@code reason} is optional free-form justification text <strong>excluded from the idempotency
 * fingerprint</strong> ({@code WorkflowCommandFingerprintFactory} omits it), so reviewers may edit
 * wording on retry and the request replays idempotently — mirrors {@code
 * ApproveSpecCommand.reason}. There is no {@code taggedFeedback} / {@code reasonText} here; those
 * belong to the reject twin (story 3.24).
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record AcceptImplementationRequest(
    @NotBlank @Size(max = 128) String artifactId,
    @NotNull @Positive Integer expectedArtifactVersion,
    @NotNull @Positive Integer expectedContextBundleVersion,
    @Size(max = 128) String reviewerRole,
    @Size(max = 1024) String reason) {}
