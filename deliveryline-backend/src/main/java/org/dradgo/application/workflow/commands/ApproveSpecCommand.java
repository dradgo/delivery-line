package org.dradgo.application.workflow.commands;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.dradgo.domain.registry.ActorType;

/**
 * Canonical fingerprint fields after the shared envelope are: {@code workflowRunId}, {@code
 * artifactId}, {@code artifactVersion}, {@code contextVersion}.
 */
public record ApproveSpecCommand(
    @NotBlank @Size(max = 128) String workflowRunId,
    @NotBlank @Size(max = 128) String artifactId,
    @NotNull @Positive Integer artifactVersion,
    @NotNull @Positive Integer contextVersion,
    @NotBlank @Size(max = 128) String actorIdentity,
    @NotNull ActorType actorType,
    @NotBlank @Size(max = 256) String idempotencyKey,
    @Size(max = 128) String correlationId)
    implements WorkflowCommand {}
