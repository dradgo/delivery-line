package org.dradgo.adapters.rest;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.dradgo.domain.registry.RejectionTaxonomy;

/**
 * Request body for {@code POST /api/v1/workflows/&#123;workflowRunId&#125;/reject-spec}.
 *
 * <p>Story 2.13 rebuilt this wire DTO to align with {@link ApproveSpecRequest}: actor identity
 * comes from the {@code X-Actor-Identity} header (with property fallback to {@code
 * deliveryline.security.local-actor-identity}); correlation id comes from MDC after the {@code
 * CorrelationIdFilter} ingress; {@code Idempotency-Key} is a required header.
 *
 * <p>{@code reviewerRole} is optional — controller defaults via {@link
 * org.dradgo.application.workflow.ApprovalReviewerRoleResolver}. {@code taggedFeedback} (story 2.10
 * {@link RejectionTaxonomy}) is required and structured; {@code reasonText} (cap widened 512 → 1024
 * in story 2.10, then → 16384 to accommodate detailed multi-point rejection feedback) is free-form
 * justification and excluded from the idempotency fingerprint.
 *
 * <p>Wire field renames vs the pre-2.13 shape: {@code artifactVersion} → {@code
 * expectedArtifactVersion}, {@code contextVersion} → {@code expectedContextBundleVersion}. The
 * underlying {@link org.dradgo.application.workflow.commands.RejectSpecCommand} command record
 * keeps the short field names for fingerprint symmetry with {@code
 * org.dradgo.application.workflow.commands.ApproveSpecCommand} and {@code
 * org.dradgo.application.workflow.commands.SubmitClarificationCommand}.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record RejectSpecRequest(
    @NotBlank @Size(max = 128) String artifactId,
    @NotNull @Positive Integer expectedArtifactVersion,
    @NotNull @Positive Integer expectedContextBundleVersion,
    @Size(max = 128) String reviewerRole,
    @NotNull RejectionTaxonomy taggedFeedback,
    @NotBlank @Size(max = 16384) String reasonText) {}
