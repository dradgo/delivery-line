package org.dradgo.adapters;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.dradgo.adapters.cli.WorkflowCommands;
import org.dradgo.adapters.rest.SubmitWorkflowRequest;
import org.dradgo.adapters.rest.SubmitWorkflowResponse;
import org.dradgo.adapters.rest.WorkflowController;
import org.dradgo.application.workflow.SubmitWorkflowResult;
import org.dradgo.application.workflow.WorkflowCommandService;
import org.dradgo.application.workflow.commands.SubmitWorkflowCommand;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.WorkflowState;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class WorkflowAdapterEquivalenceTest {

	@Test
	void cliAndRestSubmitTranslateTheSameLogicalPayloadIntoTheSameCommand() {
		WorkflowCommandService service = Mockito.mock(WorkflowCommandService.class);
		when(service.submit(any())).thenReturn(new SubmitWorkflowResult(
			"run_submit1234",
			WorkflowState.INBOX,
			"corr-submit-1"));

		WorkflowCommands cli = new WorkflowCommands(service);
		WorkflowController controller = new WorkflowController(service);

		String cliOutput = cli.submit(
			"LIN-123",
			"alex",
			ActorType.HUMAN,
			"idem-submit-1234567890",
			"corr-submit-1");
		SubmitWorkflowResponse restResponse = controller.submit(
			"idem-submit-1234567890",
			new SubmitWorkflowRequest(
				"LIN-123",
				"alex",
				ActorType.HUMAN,
				"corr-submit-1"));

		ArgumentCaptor<SubmitWorkflowCommand> captor = ArgumentCaptor.forClass(SubmitWorkflowCommand.class);
		verify(service, times(2)).submit(captor.capture());
		assertEquals(captor.getAllValues().get(0), captor.getAllValues().get(1));
		assertEquals("run_submit1234 submitted (state: Inbox)", cliOutput);
		assertEquals("run_submit1234", restResponse.workflowRunId());
		assertEquals("Inbox", restResponse.currentState());
		assertEquals("corr-submit-1", restResponse.correlationId());
	}

	@Test
	void cliAndRestSurfaceTheSameStableErrorCodeWhenSubmitFailsValidation() {
		WorkflowCommandService service = Mockito.mock(WorkflowCommandService.class);
		DomainException failure = new DomainException(
			DomainErrorCode.INVALID_COMMAND_PAYLOAD,
			"Invalid command payload");
		when(service.submit(any())).thenThrow(failure);

		WorkflowCommands cli = new WorkflowCommands(service);
		WorkflowController controller = new WorkflowController(service);

		DomainException cliError = assertThrows(
			DomainException.class,
			() -> cli.submit(
				"LIN-123",
				"alex",
				ActorType.HUMAN,
				"idem-submit-1234567890",
				"corr-submit-1"));
		DomainException restError = assertThrows(
			DomainException.class,
			() -> controller.submit(
				"idem-submit-1234567890",
				new SubmitWorkflowRequest(
					"LIN-123",
					"alex",
					ActorType.HUMAN,
					"corr-submit-1")));

		assertEquals(DomainErrorCode.INVALID_COMMAND_PAYLOAD, cliError.errorCode());
		assertEquals(cliError.errorCode(), restError.errorCode());
		assertEquals(cliError.getMessage(), restError.getMessage());
	}
}
