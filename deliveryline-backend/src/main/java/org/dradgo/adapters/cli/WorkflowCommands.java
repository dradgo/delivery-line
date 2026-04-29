package org.dradgo.adapters.cli;

import org.dradgo.application.workflow.SubmitWorkflowResult;
import org.dradgo.application.workflow.WorkflowCommandService;
import org.dradgo.application.workflow.commands.SubmitWorkflowCommand;
import org.dradgo.domain.registry.ActorType;
import org.springframework.shell.core.command.annotation.Command;
import org.springframework.shell.core.command.annotation.CommandGroup;
import org.springframework.shell.core.command.annotation.Option;
import org.springframework.stereotype.Component;

@Component
@CommandGroup(name = "workflow", description = "Workflow commands", prefix = "deliveryline")
public class WorkflowCommands {

	private final WorkflowCommandService workflowCommandService;

	public WorkflowCommands(WorkflowCommandService workflowCommandService) {
		this.workflowCommandService = workflowCommandService;
	}

	@Command(name = "submit", description = "Submit a workflow ticket for governed execution")
	public String submit(
		@Option(longName = "ticket", description = "Linear ticket reference", required = true) String linearTicketReference,
		@Option(longName = "actor-identity", description = "Actor identity", required = true) String actorIdentity,
		@Option(longName = "actor-type", description = "Actor type", required = true) ActorType actorType,
		@Option(longName = "idempotency-key", description = "Idempotency key", required = true) String idempotencyKey,
		@Option(longName = "correlation-id", description = "Correlation ID", required = false) String correlationId
	) {
		SubmitWorkflowResult result = workflowCommandService.submit(new SubmitWorkflowCommand(
			actorIdentity,
			actorType,
			idempotencyKey,
			correlationId,
			linearTicketReference));
		return result.workflowRunId() + " submitted (state: " + result.currentState().value() + ")";
	}
}
