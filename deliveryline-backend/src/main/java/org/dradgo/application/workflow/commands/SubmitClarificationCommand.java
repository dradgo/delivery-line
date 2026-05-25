package org.dradgo.application.workflow.commands;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.dradgo.domain.registry.ActorType;

/**
 * Story 2.11: command for submitting (or revising) an answer to an open clarification.
 *
 * <p>Canonical fingerprint fields after the shared envelope are: {@code workflowRunId}, {@code
 * clarificationId}, {@code artifactId}, {@code artifactVersion}.
 *
 * <p>The short field name {@code artifactVersion} IS the expected artifact version (the spec
 * version the reviewer reviewed against) — kept short for symmetry with {@link
 * ApproveSpecCommand#artifactVersion()} and {@link RejectSpecCommand#artifactVersion()}.
 *
 * <p>{@code answerText} is intentionally <strong>excluded</strong> from the fingerprint (story 2.11
 * trap T8 — symmetric with {@link ApproveSpecCommand#reason()} and {@link
 * RejectSpecCommand#reasonText()} exclusion). A reviewer editing the free-form answer text on the
 * same clarification must replay as idempotent. The clarification's {@code clarificationId} +
 * {@code artifactVersion} carry the semantic identity.
 *
 * <p>Unlike approve/reject commands, this command carries <strong>no</strong> {@code
 * expectedContextBundleVersion} (story 2.11 trap T5): an answer is part of building the NEXT
 * bundle, not a binding on the bundle that produced the current spec.
 */
public record SubmitClarificationCommand(
    @NotBlank @Size(max = 128) String workflowRunId,
    @NotBlank @Size(max = 128) String clarificationId,
    @NotBlank @Size(max = 128) String artifactId,
    @NotNull @Positive Integer artifactVersion,
    @NotBlank @Size(max = 8192) String answerText,
    @NotBlank @Size(max = 128) String actorIdentity,
    @NotNull ActorType actorType,
    @NotBlank @Size(max = 256) String idempotencyKey,
    @Size(max = 128) String correlationId)
    implements WorkflowCommand {}
