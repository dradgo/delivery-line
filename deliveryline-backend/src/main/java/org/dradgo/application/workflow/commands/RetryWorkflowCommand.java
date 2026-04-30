package org.dradgo.application.workflow.commands;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.dradgo.domain.registry.ActorType;

public record RetryWorkflowCommand(
	@NotBlank @Size(max = 128) String workflowRunId,
	@NotBlank @Size(max = 128) String actorIdentity,
	@NotNull ActorType actorType,
	@NotBlank @Size(max = 256) String idempotencyKey,
	@Size(max = 128) String correlationId,
	@Size(max = 512) String reasonText
) implements WorkflowCommand {
	public RetryWorkflowCommand {
		correlationId = WorkflowCommand.normalizeOptional(correlationId);
	}
}
