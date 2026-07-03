package org.dradgo.application.workflow.commands;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.RejectionTaxonomy;

/**
 * Canonical fingerprint fields after the shared envelope are: {@code workflowRunId}, {@code
 * artifactId}, {@code artifactVersion} (expected artifact version), {@code contextVersion}
 * (expected context-bundle version), {@code reviewerRole}, {@code taggedFeedback}.
 *
 * <p>The short field names {@code artifactVersion} / {@code contextVersion} ARE the expected
 * versions — symmetric with {@link ApproveSpecCommand}.
 *
 * <p>{@code reasonText} is intentionally <strong>excluded</strong> from the fingerprint (story 2.10
 * trap T6 / OQ-2 — symmetric with {@link ApproveSpecCommand#reason()} exclusion). A reviewer
 * editing free-form text on the same review must replay as idempotent. {@code reviewerRole} +
 * {@code taggedFeedback} ARE in the fingerprint — changing either is a semantic shift.
 *
 * <p>{@code reasonText} cap widened to 1024 chars in story 2.10 to match {@link
 * ApproveSpecCommand#reason()}, then raised to 16384 to accommodate detailed multi-point rejection
 * feedback (the persisted {@code approvals.reason} column is unbounded {@code text}).
 */
public record RejectSpecCommand(
    @NotBlank @Size(max = 128) String workflowRunId,
    @NotBlank @Size(max = 128) String artifactId,
    @NotNull @Positive Integer artifactVersion,
    @NotNull @Positive Integer contextVersion,
    @NotBlank @Size(max = 128) String actorIdentity,
    @NotNull ActorType actorType,
    @NotBlank @Size(max = 256) String idempotencyKey,
    @Size(max = 128) String correlationId,
    @NotBlank @Size(max = 128) String reviewerRole,
    @NotNull RejectionTaxonomy taggedFeedback,
    @NotBlank @Size(max = 16384) String reasonText)
    implements WorkflowCommand {}
