package org.dradgo.adapters.cli;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.dradgo.application.idempotency.IdempotencyKeyValidator;
import org.dradgo.application.idempotency.UuidV7Generator;
import org.dradgo.application.workflow.SubmitWorkflowResult;
import org.dradgo.application.workflow.WorkflowCommandService;
import org.dradgo.application.workflow.commands.SubmitWorkflowCommand;
import org.dradgo.domain.registry.ActorType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.shell.core.command.annotation.Command;
import org.springframework.shell.core.command.annotation.CommandGroup;
import org.springframework.shell.core.command.annotation.Option;
import org.springframework.stereotype.Component;

@Component
@CommandGroup(name = "workflow", description = "Workflow commands", prefix = "deliveryline")
public class WorkflowCommands {

	private final WorkflowCommandService workflowCommandService;
	private final BooleanSupplier interactivityDetector;
	private final Supplier<String> generatedKeySupplier;
	private final IdempotencyKeyValidator idempotencyKeyValidator;

	@Autowired
	public WorkflowCommands(
		WorkflowCommandService workflowCommandService,
		CliInteractivityDetector cliInteractivityDetector,
		UuidV7Generator uuidV7Generator,
		IdempotencyKeyValidator idempotencyKeyValidator
	) {
		this(
			workflowCommandService,
			cliInteractivityDetector::isInteractive,
			uuidV7Generator::generate,
			idempotencyKeyValidator);
	}

	public WorkflowCommands(
		WorkflowCommandService workflowCommandService,
		BooleanSupplier interactivityDetector,
		Supplier<String> generatedKeySupplier
	) {
		this(
			workflowCommandService,
			interactivityDetector,
			generatedKeySupplier,
			new IdempotencyKeyValidator());
	}

	private WorkflowCommands(
		WorkflowCommandService workflowCommandService,
		BooleanSupplier interactivityDetector,
		Supplier<String> generatedKeySupplier,
		IdempotencyKeyValidator idempotencyKeyValidator
	) {
		this.workflowCommandService = workflowCommandService;
		this.interactivityDetector = interactivityDetector;
		this.generatedKeySupplier = generatedKeySupplier;
		this.idempotencyKeyValidator = idempotencyKeyValidator;
	}

	@Command(
		name = "submit",
		description = "Submit a workflow ticket for governed execution",
		exitStatusExceptionMapper = WorkflowCliExitStatusExceptionMapper.BEAN_NAME)
	public String submit(
		@Option(longName = "ticket", description = "Linear ticket reference", required = true) String linearTicketReference,
		@Option(longName = "actor-identity", description = "Actor identity", required = true) String actorIdentity,
		@Option(longName = "actor-type", description = "Actor type", required = true) ActorType actorType,
		@Option(longName = "idempotency-key", description = "Idempotency key", required = false) String idempotencyKey,
		@Option(longName = "correlation-id", description = "Correlation ID", required = false) String correlationId,
		@Option(longName = "verbose", description = "Print additional command metadata", required = false, defaultValue = "false") boolean verbose
	) {
		String resolvedIdempotencyKey = resolveIdempotencyKey(idempotencyKey);
		SubmitWorkflowResult result = workflowCommandService.submit(new SubmitWorkflowCommand(
			actorIdentity,
			actorType,
			resolvedIdempotencyKey,
			correlationId,
			linearTicketReference));
		String output = result.workflowRunId() + " submitted (state: " + result.currentState().value() + ")";
		if (idempotencyKey == null) {
			// Always surface auto-generated keys so the operator can replay if the
			// response is ever lost in transit. --verbose is retained as a no-op
			// for backward compatibility but no longer gates the key disclosure.
			output += " [generated-idempotency-key: " + resolvedIdempotencyKey + "]";
		}
		return output;
	}

	private String resolveIdempotencyKey(String idempotencyKey) {
		if (idempotencyKey != null) {
			return idempotencyKey;
		}
		if (interactivityDetector.getAsBoolean()) {
			return generatedKeySupplier.get();
		}
		throw idempotencyKeyValidator.missingKeyException();
	}
}
