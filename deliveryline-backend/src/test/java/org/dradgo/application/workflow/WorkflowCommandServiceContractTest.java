package org.dradgo.application.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.dradgo.TestcontainersConfiguration;
import org.dradgo.application.workflow.commands.ApproveSpecCommand;
import org.dradgo.application.workflow.commands.RejectSpecCommand;
import org.dradgo.application.workflow.commands.RetryWorkflowCommand;
import org.dradgo.application.workflow.commands.SubmitWorkflowCommand;
import org.dradgo.application.workflow.commands.TakeoverWorkflowCommand;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.WorkflowEventType;
import org.dradgo.domain.registry.WorkflowState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles("test")
class WorkflowCommandServiceContractTest {

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private WorkflowCommandService service;

	private final ObjectMapper objectMapper = new ObjectMapper();

	@AfterEach
	void cleanDatabase() {
		jdbcTemplate.update("delete from workflow_events");
		jdbcTemplate.update("delete from workflow_runs");
	}

	@Test
	void submitCreatesInboxRunAndInitialWorkflowEvent() throws IOException {
		SubmitWorkflowResult result = service.submit(new SubmitWorkflowCommand(
			"alex",
			ActorType.HUMAN,
			"idem-submit-1234567890",
			"corr-submit-1",
			"LIN-123"));

		assertTrue(result.workflowRunId().startsWith("run_"));
		assertEquals(WorkflowState.INBOX, result.currentState());
		assertEquals("corr-submit-1", result.correlationId());

		assertEquals(
			WorkflowState.INBOX.value(),
			jdbcTemplate.queryForObject(
				"select current_state from workflow_runs where public_id = ?",
				String.class,
				result.workflowRunId()));

		Map<String, Object> event = jdbcTemplate.queryForMap(
			"""
				select event_type, prior_state, resulting_state, actor_identity, actor_type, reason, details::text as details
				from workflow_events
				where workflow_run_id = (select id from workflow_runs where public_id = ?)
				""",
			result.workflowRunId());
		assertEquals(WorkflowEventType.WORKFLOW_STATE_CHANGED.value(), event.get("event_type"));
		assertNull(event.get("prior_state"));
		assertEquals(WorkflowState.INBOX.value(), event.get("resulting_state"));
		assertEquals("alex", event.get("actor_identity"));
		assertEquals(ActorType.HUMAN.value(), event.get("actor_type"));
		assertEquals("workflow submitted", event.get("reason"));

		Map<String, Object> details = objectMapper.readValue(
			event.get("details").toString(),
			new TypeReference<>() {
			});
		assertEquals("LIN-123", details.get("linearTicketReference"));
		assertEquals("idem-submit-1234567890", details.get("idempotencyKey"));
		assertEquals("corr-submit-1", details.get("correlationId"));
		assertEquals("SubmitWorkflowCommand", details.get("commandType"));
	}

	@Test
	void invalidCommandPayloadsRaiseStableDomainErrorsWithFieldDetails() {
		DomainException error = assertThrows(
			DomainException.class,
			() -> service.submit(new SubmitWorkflowCommand(
				" ",
				null,
				" ",
				"corr-submit-2",
				" ")));

		assertEquals(DomainErrorCode.INVALID_COMMAND_PAYLOAD, error.errorCode());
		assertEquals("SubmitWorkflowCommand", error.details().get("commandType"));
		assertFieldErrors(error, "actorIdentity", "actorType", "idempotencyKey", "linearTicketReference");
	}

	@Test
	void approveSpecTransitionsWaitingForSpecApprovalToExecutingAndCarriesMetadata() throws IOException {
		String runId = insertRun("run_approve1234", WorkflowState.WAITING_FOR_SPEC_APPROVAL);

		WorkflowStateChangeResult result = service.approveSpec(new ApproveSpecCommand(
			runId,
			"art_spec1234",
			3,
			2,
			"alex",
			ActorType.HUMAN,
			"idem-approve-1234567890",
			"corr-approve-1"));

		assertEquals(runId, result.workflowRunId());
		assertEquals(WorkflowState.EXECUTING, result.currentState());
		assertEquals("corr-approve-1", result.correlationId());
		assertEquals(WorkflowState.EXECUTING.value(), currentState(runId));
		assertEquals("corr-approve-1", latestDetail(runId, "correlationId"));
		assertEquals("ApproveSpecCommand", latestDetail(runId, "commandType"));
	}

	@Test
	void rejectSpecTransitionsWaitingForSpecApprovalToInvestigating() throws IOException {
		String runId = insertRun("run_reject1234", WorkflowState.WAITING_FOR_SPEC_APPROVAL);

		WorkflowStateChangeResult result = service.rejectSpec(new RejectSpecCommand(
			runId,
			"art_spec1234",
			3,
			2,
			"alex",
			ActorType.HUMAN,
			"idem-reject-1234567890",
			"corr-reject-1",
			"Needs more detail"));

		assertEquals(runId, result.workflowRunId());
		assertEquals(WorkflowState.INVESTIGATING, result.currentState());
		assertEquals(WorkflowState.INVESTIGATING.value(), currentState(runId));
		assertEquals("RejectSpecCommand", latestDetail(runId, "commandType"));
	}

	@Test
	void retryWorkflowTransitionsFailedToExecuting() throws IOException {
		String runId = insertRun("run_retry1234", WorkflowState.FAILED);

		WorkflowStateChangeResult result = service.retryWorkflow(new RetryWorkflowCommand(
			runId,
			"alex",
			ActorType.HUMAN,
			"idem-retry-1234567890",
			"corr-retry-1",
			"retry failed run"));

		assertEquals(runId, result.workflowRunId());
		assertEquals(WorkflowState.EXECUTING, result.currentState());
		assertEquals(WorkflowState.EXECUTING.value(), currentState(runId));
		assertEquals("RetryWorkflowCommand", latestDetail(runId, "commandType"));
	}

	@Test
	void takeoverWorkflowTransitionsNonTerminalRunToTakenOver() throws IOException {
		String runId = insertRun("run_takeover1234", WorkflowState.PLANNED);

		WorkflowStateChangeResult result = service.takeoverWorkflow(new TakeoverWorkflowCommand(
			runId,
			"alex",
			ActorType.HUMAN,
			"idem-takeover-1234567890",
			"corr-takeover-1",
			"manual takeover"));

		assertEquals(runId, result.workflowRunId());
		assertEquals(WorkflowState.TAKEN_OVER, result.currentState());
		assertEquals(WorkflowState.TAKEN_OVER.value(), currentState(runId));
		assertEquals("TakeoverWorkflowCommand", latestDetail(runId, "commandType"));
	}

	private void assertFieldErrors(DomainException error, String... expectedFields) {
		Object rawFieldErrors = error.details().get("fieldErrors");
		assertNotNull(rawFieldErrors);
		List<?> fieldErrors = assertInstanceOf(List.class, rawFieldErrors);
		List<String> fieldNames = fieldErrors.stream()
			.map(Map.class::cast)
			.map(errorEntry -> errorEntry.get("field").toString())
			.toList();
		for (String expectedField : expectedFields) {
			assertTrue(
				fieldNames.contains(expectedField),
				() -> "Expected field error for " + expectedField + " but got " + fieldNames);
		}
	}

	private String insertRun(String publicId, WorkflowState state) {
		jdbcTemplate.update(
			"insert into workflow_runs (public_id, current_state) values (?, ?)",
			publicId,
			state.value());
		return publicId;
	}

	private String currentState(String runId) {
		return jdbcTemplate.queryForObject(
			"select current_state from workflow_runs where public_id = ?",
			String.class,
			runId);
	}

	private String latestDetail(String runId, String detailKey) throws IOException {
		String detailsJson = jdbcTemplate.queryForObject(
			"""
				select details::text
				from workflow_events
				where workflow_run_id = (select id from workflow_runs where public_id = ?)
				order by id desc
				limit 1
				""",
			String.class,
			runId);
		Map<String, Object> details = objectMapper.readValue(detailsJson, new TypeReference<>() {
		});
		return details.get(detailKey).toString();
	}
}
