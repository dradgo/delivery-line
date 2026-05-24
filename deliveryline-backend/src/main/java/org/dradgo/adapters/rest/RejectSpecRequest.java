package org.dradgo.adapters.rest;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.RejectionTaxonomy;

/**
 * Request body for {@code POST /api/v1/workflows/&#123;workflowRunId&#125;/reject-spec}.
 *
 * <p>Story 2.10 added the {@code reviewerRole} (optional — controller defaults via {@code
 * ApprovalReviewerRoleResolver}) and {@code taggedFeedback} (required — {@link RejectionTaxonomy}
 * structured rework taxonomy per FR9 / AR34a) fields. {@code reasonText} cap widened 512 → 1024 to
 * match {@link ApproveSpecRequest#reason()}.
 *
 * <p>The MVP-fallback {@code reviewerRole} handling is retained until story 2.13 rebuilds the REST
 * surface with proper actor-identity header parsing.
 */
public record RejectSpecRequest(
    @NotBlank @Size(max = 128) String artifactId,
    @NotNull @Positive Integer artifactVersion,
    @NotNull @Positive Integer contextVersion,
    @NotBlank @Size(max = 128) String actorIdentity,
    @NotNull ActorType actorType,
    @Size(max = 128) String correlationId,
    @Size(max = 128) String reviewerRole,
    @NotNull RejectionTaxonomy taggedFeedback,
    @NotBlank @Size(max = 1024) String reasonText) {}
