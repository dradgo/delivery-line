package org.dradgo.contract;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import org.dradgo.adapters.rest.WorkflowController;
import org.dradgo.application.workflow.WorkflowCommandService;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.DomainErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = WorkflowController.class)
class ProblemDetailsContractTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private WorkflowCommandService workflowCommandService;

	@Test
	void domainExceptionsAreSerializedAsProblemDetailsWithStableMachineReadableFields() throws Exception {
		when(workflowCommandService.submit(any())).thenThrow(new DomainException(
			DomainErrorCode.INVALID_COMMAND_PAYLOAD,
			"Invalid command payload for SubmitWorkflowCommand",
			Map.of(
				"commandType", "SubmitWorkflowCommand",
				"fieldErrors", List.of(Map.of(
					"field", "actorIdentity",
					"code", "NotBlank",
					"rejectedValue", " ",
					"message", "must not be blank")))));

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
			.andExpect(jsonPath("$.type").value("https://deliveryline.local/problems/invalid-command-payload"))
			.andExpect(jsonPath("$.status").value(400))
			.andExpect(jsonPath("$.instance").value("/api/v1/workflows/submit-workflow"))
			.andExpect(jsonPath("$.code").value("INVALID_COMMAND_PAYLOAD"))
			.andExpect(jsonPath("$.retryable").value(false))
			.andExpect(jsonPath("$.details[0].field").value("actorIdentity"))
			.andExpect(jsonPath("$.details[0].constraint").value("NotBlank"))
			.andExpect(jsonPath("$.details[0].rejectedValue").value(" "));
	}

	@Test
	void approvalVersionMismatchExposesRefreshMetadataInProblemDetails() throws Exception {
		when(workflowCommandService.approveSpec(any())).thenThrow(new DomainException(
			DomainErrorCode.APPROVAL_VERSION_MISMATCH,
			"Artifact version is stale",
			Map.of(
				"expectedArtifactVersion", 6,
				"currentArtifactVersion", 7)));

		mockMvc.perform(post("/api/v1/workflows/run_approve1234/approve-spec")
				.contentType(MediaType.APPLICATION_JSON)
				.accept(MediaType.APPLICATION_JSON)
				.header("Idempotency-Key", "idem-approve-1234567890")
				.content("""
					{
					  "artifactId": "art_spec1234",
					  "artifactVersion": 6,
					  "contextVersion": 2,
					  "actorIdentity": "alex",
					  "actorType": "HUMAN",
					  "correlationId": "corr-approve-1"
					}
					"""))
			.andExpect(status().isConflict())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.code").value("APPROVAL_VERSION_MISMATCH"))
			.andExpect(jsonPath("$.retryable").value(false))
			.andExpect(jsonPath("$.details.expectedArtifactVersion").value(6))
			.andExpect(jsonPath("$.details.currentArtifactVersion").value(7));
	}

	@Test
	void missingRequiredHeadersBecomeStableInvalidCommandPayloadProblemDetails() throws Exception {
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
			.andExpect(jsonPath("$.code").value("INVALID_COMMAND_PAYLOAD"))
			.andExpect(jsonPath("$.details[0].field").value("Idempotency-Key"))
			.andExpect(jsonPath("$.details[0].constraint").value("required"));
	}

	@Test
	void enumBindingFailuresBecomeStableInvalidCommandPayloadProblemDetails() throws Exception {
		mockMvc.perform(post("/api/v1/workflows/submit-workflow")
				.contentType(MediaType.APPLICATION_JSON)
				.accept(MediaType.APPLICATION_JSON)
				.header("Idempotency-Key", "idem-submit-1234567890")
				.content("""
					{
					  "linearTicketReference": "LIN-123",
					  "actorIdentity": "alex",
					  "actorType": "NOT_A_REAL_TYPE",
					  "correlationId": "corr-submit-1"
					}
					"""))
			.andExpect(status().isBadRequest())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.code").value("INVALID_COMMAND_PAYLOAD"))
			.andExpect(jsonPath("$.details[0].field").exists())
			.andExpect(jsonPath("$.details[0].constraint").exists());
	}

	@Test
	void beanValidationFailuresBecomeStableInvalidCommandPayloadProblemDetails() throws Exception {
		mockMvc.perform(post("/api/v1/workflows/submit-workflow")
				.contentType(MediaType.APPLICATION_JSON)
				.accept(MediaType.APPLICATION_JSON)
				.header("Idempotency-Key", "idem-submit-1234567890")
				.content("""
					{
					  "linearTicketReference": " ",
					  "actorIdentity": " ",
					  "actorType": "HUMAN",
					  "correlationId": "corr-submit-1"
					}
					"""))
			.andExpect(status().isBadRequest())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.code").value("INVALID_COMMAND_PAYLOAD"))
			.andExpect(jsonPath("$.details[0].constraint").exists())
			.andExpect(jsonPath("$.details[0].field").exists());
	}

	@Test
	void malformedJsonBecomesStableInvalidCommandPayloadProblemDetails() throws Exception {
		mockMvc.perform(post("/api/v1/workflows/submit-workflow")
				.contentType(MediaType.APPLICATION_JSON)
				.accept(MediaType.APPLICATION_JSON)
				.header("Idempotency-Key", "idem-submit-1234567890")
				.content("""
					{
					  "linearTicketReference": "LIN-123",
					  "actorIdentity":
					}
					"""))
			.andExpect(status().isBadRequest())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.code").value("INVALID_COMMAND_PAYLOAD"))
			.andExpect(jsonPath("$.details[0].field").value("body"))
			.andExpect(jsonPath("$.details[0].constraint").value("malformedJson"));
	}

	@Test
	void unknownExceptionsFallBackToInternalErrorWithoutLeakingSensitiveInternals() throws Exception {
		when(workflowCommandService.submit(any())).thenThrow(
			new IllegalStateException("SQLSTATE 08006 at C:\\secrets\\prod.env in org.dradgo.adapters.rest.WorkflowController"));

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
			.andExpect(status().isInternalServerError())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
			.andExpect(jsonPath("$.status").value(500))
			.andExpect(jsonPath("$.retryable").value(false))
			.andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("SQLSTATE"))))
			.andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("WorkflowController"))))
			.andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("C:\\secrets"))));
	}
}
