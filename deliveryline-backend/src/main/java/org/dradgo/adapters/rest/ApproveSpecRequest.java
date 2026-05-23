package org.dradgo.adapters.rest;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.dradgo.domain.registry.ActorType;

/**
 * Request body for {@code POST /api/v1/workflows/&#123;workflowRunId&#125;/approve-spec}.
 *
 * <p>Story 2.9 introduced the optional {@code reviewerRole} + {@code reason} fields. When {@code
 * reviewerRole} is omitted, the controller falls back to the configured {@code
 * deliveryline.approval.default-reviewer-role} property — this is an MVP-fallback retained until
 * story 2.13 rebuilds the REST surface with proper actor-identity header parsing.
 */
public record ApproveSpecRequest(
    @jakarta.validation.constraints.NotBlank @Size(max = 128) String artifactId,
    @NotNull @Positive Integer artifactVersion,
    @NotNull @Positive Integer contextVersion,
    @jakarta.validation.constraints.NotBlank @Size(max = 128) String actorIdentity,
    @NotNull ActorType actorType,
    @Size(max = 128) String correlationId,
    @Size(max = 128) String reviewerRole,
    @Size(max = 1024) String reason) {}
