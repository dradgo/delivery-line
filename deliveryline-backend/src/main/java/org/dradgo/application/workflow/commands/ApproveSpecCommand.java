package org.dradgo.application.workflow.commands;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.dradgo.domain.registry.ActorType;

/**
 * Canonical fingerprint fields after the shared envelope are: {@code workflowRunId}, {@code
 * artifactId}, {@code artifactVersion} (the expected artifact version the reviewer reviewed
 * against), {@code contextVersion} (the expected context-bundle version), {@code reviewerRole}.
 *
 * <p>The short field names {@code artifactVersion} / {@code contextVersion} ARE the expected
 * versions — kept short to avoid rippling renames across {@link
 * org.dradgo.application.idempotency.WorkflowCommandFingerprintFactory}, REST/OpenAPI surfaces and
 * existing tests. Story 2.13 will introduce a richer REST DTO with verbose names; the
 * application-layer command stays here.
 *
 * <p>{@code reason} is intentionally <strong>excluded</strong> from the fingerprint: a reviewer
 * editing free-form text on the same review must replay as idempotent. {@code reviewerRole} IS in
 * the fingerprint — changing the asserted role is a semantic shift.
 */
public record ApproveSpecCommand(
    @NotBlank @Size(max = 128) String workflowRunId,
    @NotBlank @Size(max = 128) String artifactId,
    @NotNull @Positive Integer artifactVersion,
    @NotNull @Positive Integer contextVersion,
    @NotBlank @Size(max = 128) String actorIdentity,
    @NotNull ActorType actorType,
    @NotBlank @Size(max = 256) String idempotencyKey,
    @Size(max = 128) String correlationId,
    @NotBlank @Size(max = 128) String reviewerRole,
    @Size(max = 1024) String reason)
    implements WorkflowCommand {}
