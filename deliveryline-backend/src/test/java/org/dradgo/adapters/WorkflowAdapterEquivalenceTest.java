package org.dradgo.adapters;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.dradgo.adapters.cli.WorkflowCommands;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = WorkflowController.class)
class WorkflowAdapterEquivalenceTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private WorkflowCommandService workflowCommandService;

	@Test
	void cliAndRestSubmitTranslateTheSameLogicalPayloadIntoTheSameCommand() throws Exception {
		when(workflowCommandService.submit(any())).thenReturn(new SubmitWorkflowResult(
			"run_submit1234",
			WorkflowState.INBOX,
			"corr-submit-1"));

		WorkflowCommands cli = new WorkflowCommands(workflowCommandService);

		String cliOutput = cli.submit(
			"LIN-123",
			"alex",
			ActorType.HUMAN,
			"idem-submit-1234567890",
			"corr-submit-1");

		mockMvc.perform(post("/api/v1/workflows/submit-workflow")
				.contentType(MediaType.APPLICATION_JSON)
				.accept(MediaType.APPLICATION_JSON)
				.header("Idempotency-Key", "idem-submit-1234567890")
				.content("""
					{
					  "linearTicketReference": "LIN-123",
					  "actorIdentity": "alex",
					  "actorType": "HUMAN",
					  "correlationId": "corr-submit-1"
					}
					"""))
			.andExpect(status().isOk())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(jsonPath("$.workflowRunId").value("run_submit1234"))
			.andExpect(jsonPath("$.currentState").value("Inbox"))
			.andExpect(jsonPath("$.correlationId").value("corr-submit-1"));

		ArgumentCaptor<SubmitWorkflowCommand> captor = ArgumentCaptor.forClass(SubmitWorkflowCommand.class);
		verify(workflowCommandService, times(2)).submit(captor.capture());
		assertEquals(captor.getAllValues().get(0), captor.getAllValues().get(1));
		assertEquals("run_submit1234 submitted (state: Inbox)", cliOutput);
	}

	@Test
	void cliAndRestSurfaceTheSameStableErrorCodeWhenSubmitFailsValidation() throws Exception {
		DomainException failure = new DomainException(
			DomainErrorCode.INVALID_COMMAND_PAYLOAD,
			"Invalid command payload");
		when(workflowCommandService.submit(any())).thenThrow(failure);

		WorkflowCommands cli = new WorkflowCommands(workflowCommandService);

		DomainException cliError = assertThrows(
			DomainException.class,
			() -> cli.submit(
				"LIN-123",
				"alex",
				ActorType.HUMAN,
				"idem-submit-1234567890",
				"corr-submit-1"));

		assertEquals(DomainErrorCode.INVALID_COMMAND_PAYLOAD, cliError.errorCode());
		mockMvc.perform(post("/api/v1/workflows/submit-workflow")
				.contentType(MediaType.APPLICATION_JSON)
				.accept(MediaType.APPLICATION_JSON)
				.header("Idempotency-Key", "idem-submit-1234567890")
				.content("""
					{
					  "linearTicketReference": "LIN-123",
					  "actorIdentity": "alex",
					  "actorType": "HUMAN",
					  "correlationId": "corr-submit-1"
					}
					"""))
			.andExpect(status().isBadRequest())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.code").value(cliError.errorCode().value()));
	}

	@Test
	void missingIdempotencyHeaderIsRejectedByTheActualWebLayer() throws Exception {
		mockMvc.perform(post("/api/v1/workflows/submit-workflow")
				.contentType(MediaType.APPLICATION_JSON)
				.accept(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "linearTicketReference": "LIN-123",
					  "actorIdentity": "alex",
					  "actorType": "HUMAN",
					  "correlationId": "corr-submit-1"
					}
					"""))
			.andExpect(status().isBadRequest())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.code").value(DomainErrorCode.INVALID_COMMAND_PAYLOAD.value()));
	}

	@Test
	void actualWebLayerParsesJsonEnumsAndHeadersIntoTheSharedSubmitCommand() throws Exception {
		when(workflowCommandService.submit(any())).thenReturn(new SubmitWorkflowResult(
			"run_submit5678",
			WorkflowState.INBOX,
			null));

		mockMvc.perform(post("/api/v1/workflows/submit-workflow")
				.contentType(MediaType.APPLICATION_JSON)
				.accept(MediaType.APPLICATION_JSON)
				.header("Idempotency-Key", "idem-submit-abcdef123456")
				.content("""
					{
					  "linearTicketReference": "LIN-456",
					  "actorIdentity": "alex",
					  "actorType": "HUMAN",
					  "correlationId": "corr-submit-2"
					}
					"""))
			.andExpect(status().isOk());

		ArgumentCaptor<SubmitWorkflowCommand> captor = ArgumentCaptor.forClass(SubmitWorkflowCommand.class);
		verify(workflowCommandService).submit(captor.capture());
		assertEquals(new SubmitWorkflowCommand(
			"alex",
			ActorType.HUMAN,
			"idem-submit-abcdef123456",
			"corr-submit-2",
			"LIN-456"), captor.getValue());
	}
}
