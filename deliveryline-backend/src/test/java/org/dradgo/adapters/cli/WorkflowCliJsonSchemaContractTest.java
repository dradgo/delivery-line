package org.dradgo.adapters.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Result;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.dradgo.application.idempotency.IdempotencyKeyValidator;
import org.dradgo.application.workflow.WorkflowCommandService;
import org.dradgo.application.workflow.WorkflowInspectionService;
import org.dradgo.application.workflow.WorkflowInspectionService.LatestArtifactView;
import org.dradgo.application.workflow.WorkflowInspectionService.LinkedTicketView;
import org.dradgo.application.workflow.WorkflowInspectionService.WorkflowEventView;
import org.dradgo.application.workflow.WorkflowInspectionService.WorkflowHistoryView;
import org.dradgo.application.workflow.WorkflowInspectionService.WorkflowStatusView;
import org.dradgo.domain.registry.WorkflowState;
import org.junit.jupiter.api.Test;

class WorkflowCliJsonSchemaContractTest {

	private static final String STATUS_SCHEMA_LOCATION = "classpath:schemas/cli/workflow-status.v1.schema.json";
	private static final String HISTORY_SCHEMA_LOCATION = "classpath:schemas/cli/workflow-history.v1.schema.json";

	private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
	private final WorkflowInspectionService inspection = mock(WorkflowInspectionService.class);
	private final WorkflowCommands commands = new WorkflowCommands(
		mock(WorkflowCommandService.class),
		inspection,
		new WorkflowCommandOutputs(mapper),
		() -> false,
		() -> "01964c38-1c45-7000-8000-000000000000",
		() -> "01964c38-1c45-7000-8000-000000000001",
		new IdempotencyKeyValidator());
	private final SchemaRegistry schemaRegistry = SchemaRegistry.withDefaultDialect(
		SpecificationVersion.DRAFT_2020_12);

	@Test
	void statusJsonHasSchemaVersionOneAndValidatesAgainstSchema() throws Exception {
		WorkflowStatusView view = new WorkflowStatusView(
			"run_status12345",
			WorkflowState.EXECUTING,
			"alex",
			"human",
			"workflow.stateChanged",
			OffsetDateTime.parse("2026-05-13T10:00:00Z"),
			List.of(new LatestArtifactView("spec", 2, "available")),
			new LinkedTicketView("linear", "LIN-101", "linked"),
			"pending (story 1.18)");
		when(inspection.getStatus("run_status12345")).thenReturn(view);

		String json = commands.status("run_status12345", "json", "corr-status-1");
		JsonNode payload = mapper.readTree(json);

		assertEquals(1, payload.get("schemaVersion").asInt());
		assertTrue(payload.get("workflowRunId").asText().startsWith("run_"));
		assertSchemaValid(STATUS_SCHEMA_LOCATION, json);
	}

	@Test
	void statusJsonAcceptsNullActorEventAndLink() throws Exception {
		WorkflowStatusView view = new WorkflowStatusView(
			"run_emptystatus12345",
			WorkflowState.INBOX,
			null,
			null,
			null,
			null,
			List.of(),
			null,
			"pending (story 1.18)");
		when(inspection.getStatus("run_emptystatus12345")).thenReturn(view);

		String json = commands.status("run_emptystatus12345", "json", null);
		JsonNode payload = mapper.readTree(json);

		assertTrue(payload.get("currentActor").isNull());
		assertTrue(payload.get("lastEvent").isNull());
		assertTrue(payload.get("linkedTicket").isNull());
		assertSchemaValid(STATUS_SCHEMA_LOCATION, json);
	}

	@Test
	void historyJsonHasRequiredEventFieldsAndAllowListedDetailsKeys() throws Exception {
		Map<String, Object> details = new LinkedHashMap<>();
		details.put("linearTicketReference", "LIN-101");
		details.put("correlationId", "corr-1");
		WorkflowHistoryView view = new WorkflowHistoryView("run_hist12345", List.of(
			new WorkflowEventView(
				"evt_e1abc1234",
				"workflow.stateChanged",
				null,
				"Inbox",
				"alex",
				"human",
				"workflow submitted",
				null,
				false,
				OffsetDateTime.parse("2026-05-13T10:00:00Z"),
				details)));
		when(inspection.listHistory("run_hist12345", null)).thenReturn(view);

		String json = commands.history("run_hist12345", "json", null, "corr-hist-1");
		JsonNode payload = mapper.readTree(json);

		assertEquals(1, payload.get("schemaVersion").asInt());
		assertEquals("run_hist12345", payload.get("workflowRunId").asText());
		assertEquals(1, payload.get("events").size());
		assertSchemaValid(HISTORY_SCHEMA_LOCATION, json);
	}

	@Test
	void historyJsonAcceptsEmptyDetailsObject() throws Exception {
		WorkflowHistoryView view = new WorkflowHistoryView("run_dropkey12345", List.of(
			new WorkflowEventView(
				"evt_e2def1234",
				"artifact.failed",
				null,
				null,
				"alex",
				"human",
				null,
				"runner_timeout",
				false,
				OffsetDateTime.parse("2026-05-13T10:01:00Z"),
				Map.of())));
		when(inspection.listHistory("run_dropkey12345", null)).thenReturn(view);

		String json = commands.history("run_dropkey12345", "json", null, null);
		JsonNode payload = mapper.readTree(json);
		assertTrue(payload.get("events").get(0).get("details").isObject());
		assertTrue(payload.get("events").get(0).get("resultingState").isNull());
		assertSchemaValid(HISTORY_SCHEMA_LOCATION, json);
	}

	private void assertSchemaValid(String schemaLocation, String json) {
		Schema schema = schemaRegistry.getSchema(SchemaLocation.of(schemaLocation));
		Result validation = schema.walk(json, InputFormat.JSON, true);
		assertTrue(
			validation.getErrors().isEmpty(),
			() -> "Schema validation errors: " + validation.getErrors());
	}
}
