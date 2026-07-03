package org.dradgo.adapters.rest;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.dradgo.domain.registry.RejectionTaxonomy;

/**
 * Request body for {@code POST /api/v1/workflows/&#123;workflowRunId&#125;/reject-implementation}
 * (story 3.24). The technical-rejection twin of {@link RejectSpecRequest} — same wire shape
 * (header-derived actor + correlation identity; required {@code Idempotency-Key} header), surfacing
 * the {@code done} story 3.21 {@code WorkflowCommandService.rejectImplementation} service. Mirrors
 * {@link RejectSpecRequest} field-for-field; it is the reject sibling of {@link
 * AcceptImplementationRequest}.
 *
 * <p>The verbose wire names {@code expectedArtifactVersion} / {@code expectedContextBundleVersion}
 * map to the short {@code artifactVersion} / {@code contextVersion} command fields on {@link
 * org.dradgo.application.workflow.commands.RejectImplementationCommand} (Trap T1, documented on
 * that record): the short names ARE the expected versions the reviewer reviewed against.
 *
 * <p>{@code reviewerRole} must be {@code developer} — the controller (story 3.24 Task 3) enforces
 * it at the boundary with the typed {@link
 * org.dradgo.domain.registry.DomainErrorCode#INVALID_REVIEWER_ROLE_FOR_ENDPOINT} code (the idiom
 * created by story 3.23), and does NOT route the value through {@link
 * org.dradgo.application.workflow.ApprovalReviewerRoleResolver} (whose blank-fallback default
 * {@code product_reviewer} would mask the mismatch). This is the key delta from {@code
 * RejectSpecRequest}, whose role IS resolver-defaulted.
 *
 * <p>{@code taggedFeedback} is a required structured {@link RejectionTaxonomy} value and must be a
 * <strong>developer</strong>-subset value (story 3.21 {@link
 * RejectionTaxonomy#isDeveloperValue()}); the controller rejects a valid-but-product value (e.g.
 * {@code MISSING_SCOPE}) as the typed {@link
 * org.dradgo.domain.registry.DomainErrorCode#INVALID_REJECTION_TAXONOMY} code. An entirely-unknown
 * enum string never reaches this record — Jackson deserialization fails first → {@code
 * INVALID_COMMAND_PAYLOAD}. Jackson binds the enum by constant NAME (UPPERCASE, e.g. {@code
 * INCORRECT_APPROACH}), not by {@code value()} (R5). {@code reasonText} is free-form justification
 * excluded from the idempotency fingerprint (mirrors {@code RejectSpecCommand.reasonText}), so
 * reviewers may edit wording on retry and the request replays idempotently.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record RejectImplementationRequest(
    @NotBlank @Size(max = 128) String artifactId,
    @NotNull @Positive Integer expectedArtifactVersion,
    @NotNull @Positive Integer expectedContextBundleVersion,
    @Size(max = 128) String reviewerRole,
    @NotNull RejectionTaxonomy taggedFeedback,
    @NotBlank @Size(max = 16384) String reasonText) {}
