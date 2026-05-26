package org.dradgo.adapters.rest;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /api/v1/workflows/&#123;workflowRunId&#125;/approve-spec}.
 *
 * <p>Story 2.13 rebuilt this wire DTO so the actor and correlation identity move to HTTP headers
 * (per architecture security posture lines 256-257, 343):
 *
 * <ul>
 *   <li>Actor identity is resolved from the {@code X-Actor-Identity} request header with
 *       property-fallback to {@code deliveryline.security.local-actor-identity} (default {@code
 *       local-operator}); see {@link org.dradgo.application.security.LocalActorIdentityResolver}.
 *       Actor type is always {@code HUMAN} at this transport (audit-only RBAC per architecture line
 *       256).
 *   <li>Correlation id is read from MDC after {@code
 *       org.dradgo.infrastructure.observability.CorrelationIdFilter} parses {@code
 *       X-Correlation-Id} (auto-generated UUIDv7 when missing).
 *   <li>The {@code Idempotency-Key} header is required.
 * </ul>
 *
 * <p>{@code reviewerRole} stays optional — when omitted the controller falls back to {@code
 * deliveryline.approval.default-reviewer-role} via {@link
 * org.dradgo.application.workflow.ApprovalReviewerRoleResolver}. {@code reason} is free-form
 * justification text excluded from the idempotency fingerprint so reviewers may edit it on retry.
 *
 * <p>Wire field renames vs the pre-2.13 shape: {@code artifactVersion} → {@code
 * expectedArtifactVersion}, {@code contextVersion} → {@code expectedContextBundleVersion}. The
 * underlying {@link org.dradgo.application.workflow.commands.ApproveSpecCommand} command record
 * keeps the short field names for fingerprint symmetry with {@code
 * org.dradgo.application.workflow.commands.SubmitClarificationCommand} (see its Javadoc).
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record ApproveSpecRequest(
    @NotBlank @Size(max = 128) String artifactId,
    @NotNull @Positive Integer expectedArtifactVersion,
    @NotNull @Positive Integer expectedContextBundleVersion,
    @Size(max = 128) String reviewerRole,
    @Size(max = 1024) String reason) {}
