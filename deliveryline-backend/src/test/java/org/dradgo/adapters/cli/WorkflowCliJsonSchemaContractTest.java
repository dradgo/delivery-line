package org.dradgo.adapters.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import org.dradgo.application.artifact.ArtifactRecordSnapshot;
import org.dradgo.application.artifact.SpecificationArtifact;
import org.dradgo.application.idempotency.IdempotencyKeyValidator;
import org.dradgo.application.recovery.RecoveryService;
import org.dradgo.application.runner.ContextBundle;
import org.dradgo.application.security.LocalActorIdentityResolver;
import org.dradgo.application.workflow.WorkflowCommandService;
import org.dradgo.application.workflow.WorkflowInspectionService;
import org.dradgo.application.workflow.WorkflowInspectionService.ContextBundleLookupResult;
import org.dradgo.application.workflow.WorkflowInspectionService.LatestArtifactView;
import org.dradgo.application.workflow.WorkflowInspectionService.LinkedTicketView;
import org.dradgo.application.workflow.WorkflowInspectionService.SpecHistoryEntry;
import org.dradgo.application.workflow.WorkflowInspectionService.WorkflowEventView;
import org.dradgo.application.workflow.WorkflowInspectionService.WorkflowHistoryView;
import org.dradgo.application.workflow.WorkflowInspectionService.WorkflowStatusView;
import org.dradgo.domain.registry.ArtifactStatus;
import org.dradgo.domain.registry.ArtifactType;
import org.dradgo.domain.registry.DataClassification;
import org.dradgo.domain.registry.RunnerStage;
import org.dradgo.domain.registry.WorkflowState;
import org.junit.jupiter.api.Test;

class WorkflowCliJsonSchemaContractTest {

  private static final String STATUS_SCHEMA_LOCATION =
      "classpath:schemas/cli/workflow-status.v1.schema.json";
  private static final String STATUS_WITH_CONTEXT_BUNDLE_SCHEMA_LOCATION =
      "classpath:schemas/cli/workflow-status.v2.schema.json";
  private static final String HISTORY_SCHEMA_LOCATION =
      "classpath:schemas/cli/workflow-history.v1.schema.json";

  private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
  private final WorkflowInspectionService inspection = mock(WorkflowInspectionService.class);
  private final WorkflowCommands commands =
      new WorkflowCommands(
          mock(WorkflowCommandService.class),
          inspection,
          new WorkflowCommandOutputs(mapper),
          () -> false,
          () -> "01964c38-1c45-7000-8000-000000000000",
          () -> "01964c38-1c45-7000-8000-000000000001",
          new IdempotencyKeyValidator(),
          mock(RecoveryService.class),
          null,
          new LocalActorIdentityResolver("local-operator"),
          null);
  private final SchemaRegistry schemaRegistry =
      SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);

  @Test
  void statusJsonHasSchemaVersionOneAndValidatesAgainstSchema() throws Exception {
    WorkflowStatusView view =
        new WorkflowStatusView(
            "run_status12345",
            WorkflowState.EXECUTING,
            "alex",
            "human",
            "workflow.stateChanged",
            OffsetDateTime.parse("2026-05-13T10:00:00Z"),
            List.of(new LatestArtifactView("spec", 2, "available")),
            new LinkedTicketView("linear", "LIN-101", "linked"),
            null,
            null,
            null,
            null,
            null,
            "await_outcome",
            0,
            false);
    when(inspection.getStatus("run_status12345")).thenReturn(view);

    String json = commands.status("run_status12345", "json", "corr-status-1", false, false, false);
    JsonNode payload = mapper.readTree(json);

    assertEquals(1, payload.get("schemaVersion").asInt());
    assertTrue(payload.get("workflowRunId").asText().startsWith("run_"));
    assertFalse(payload.has("specRejectionLoopCount"));
    assertFalse(payload.has("escalationMarker"));
    assertSchemaValid(STATUS_SCHEMA_LOCATION, json);
  }

  @Test
  void statusJsonWithContextBundleUsesSchemaVersionTwoAndValidatesAgainstSchema() throws Exception {
    WorkflowStatusView view =
        new WorkflowStatusView(
            "run_status12345",
            WorkflowState.EXECUTING,
            "alex",
            "human",
            "workflow.stateChanged",
            OffsetDateTime.parse("2026-05-13T10:00:00Z"),
            List.of(new LatestArtifactView("spec", 2, "available")),
            new LinkedTicketView("linear", "LIN-101", "linked"),
            null,
            null,
            null,
            null,
            null,
            "await_outcome",
            3,
            true);
    when(inspection.getStatus("run_status12345")).thenReturn(view);
    SpecHistoryEntry latest =
        new SpecHistoryEntry(
            SpecificationArtifact.fromSnapshot(
                new ArtifactRecordSnapshot(
                    "art_spec00000002",
                    "run_status12345",
                    ArtifactType.SPEC,
                    2,
                    "art_spec00000001",
                    DataClassification.SHAREABLE_REDACTED,
                    "spec.md",
                    null,
                    null,
                    null,
                    null,
                    ArtifactStatus.AVAILABLE,
                    null,
                    false,
                    OffsetDateTime.parse("2026-05-13T10:00:00Z"))),
            "approved",
            "product_owner",
            null,
            OffsetDateTime.parse("2026-05-13T10:05:00Z"));
    when(inspection.getSpecHistory("run_status12345")).thenReturn(List.of(latest));
    when(inspection.getContextBundleLookupForArtifact("art_spec00000002"))
        .thenReturn(
            ContextBundleLookupResult.available(
                "art_spec00000002",
                new ContextBundle(
                    "run_status12345",
                    RunnerStage.INVESTIGATION,
                    "rex_status12345",
                    1,
                    DataClassification.SHAREABLE_REDACTED,
                    """
                    {"schemaVersion":1,"workflowRunId":"run_status12345","runnerExecutionId":"rex_status12345","classification":"shareable-redacted"}
                    """
                        .getBytes())));

    String json = commands.status("run_status12345", "json", "corr-status-2", false, true, false);
    JsonNode payload = mapper.readTree(json);

    assertEquals(2, payload.get("schemaVersion").asInt());
    assertEquals(3, payload.get("specRejectionLoopCount").asInt());
    assertTrue(payload.get("escalationMarker").asBoolean());
    assertEquals("available", payload.get("contextBundle").get("status").asText());
    assertEquals("art_spec00000002", payload.get("contextBundle").get("artifactId").asText());
    assertSchemaValid(STATUS_WITH_CONTEXT_BUNDLE_SCHEMA_LOCATION, json);
  }

  @Test
  void statusJsonValidatesPopulatedFailedRunFixtureWithAllFailureDiagnosticFields()
      throws Exception {
    WorkflowStatusView view =
        new WorkflowStatusView(
            "run_failed01234",
            WorkflowState.FAILED,
            "system",
            "system",
            "workflow.stateChanged",
            OffsetDateTime.parse("2026-05-13T10:00:00Z"),
            List.of(new LatestArtifactView("spec", 1, "available")),
            null,
            "execution",
            "Executing",
            OffsetDateTime.parse("2026-05-13T10:00:00Z"),
            "runner_timeout",
            OffsetDateTime.parse("2026-05-13T09:59:30Z"),
            "retry",
            0,
            false);
    when(inspection.getStatus("run_failed01234")).thenReturn(view);

    String json =
        commands.status("run_failed01234", "json", "corr-status-failed", false, false, false);
    JsonNode payload = mapper.readTree(json);

    assertEquals("execution", payload.get("failedStage").asText());
    assertEquals("Executing", payload.get("lastSuccessfulStage").asText());
    assertEquals("2026-05-13T10:00:00Z", payload.get("failureTimestamp").asText());
    assertEquals("runner_timeout", payload.get("failureCategory").asText());
    assertEquals("2026-05-13T09:59:30Z", payload.get("lastActivityTimestamp").asText());
    assertEquals("retry", payload.get("nextSafeAction").asText());
    assertSchemaValid(STATUS_SCHEMA_LOCATION, json);
  }

  @Test
  void historyJsonValidatesFailedAndRetriedTimelineFixture() throws Exception {
    WorkflowHistoryView view =
        new WorkflowHistoryView(
            "run_failretried",
            List.of(
                new WorkflowEventView(
                    "evt_failed-12345",
                    "workflow.stateChanged",
                    "Executing",
                    "Failed",
                    "system",
                    "system",
                    "runner failure",
                    "runner_timeout",
                    false,
                    OffsetDateTime.parse("2026-05-13T10:00:00Z"),
                    Map.of()),
                new WorkflowEventView(
                    "evt_runfail-1234",
                    "runner.failed",
                    null,
                    null,
                    "system",
                    "system",
                    null,
                    "runner_timeout",
                    false,
                    OffsetDateTime.parse("2026-05-13T10:00:01Z"),
                    Map.of()),
                new WorkflowEventView(
                    "evt_retried-1234",
                    "recovery.retried",
                    "Failed",
                    "Executing",
                    "alex",
                    "human",
                    "retry from failed execution",
                    null,
                    true,
                    OffsetDateTime.parse("2026-05-13T10:05:00Z"),
                    Map.of())));
    when(inspection.listHistory("run_failretried", null)).thenReturn(view);

    String json = commands.history("run_failretried", "json", null, "corr-failhist-1", false);
    JsonNode payload = mapper.readTree(json);

    assertEquals(3, payload.get("events").size());
    assertEquals("workflow.stateChanged", payload.get("events").get(0).get("eventType").asText());
    assertEquals("Failed", payload.get("events").get(0).get("resultingState").asText());
    assertEquals("runner_timeout", payload.get("events").get(0).get("failureCategory").asText());
    assertEquals("runner.failed", payload.get("events").get(1).get("eventType").asText());
    assertEquals("runner_timeout", payload.get("events").get(1).get("failureCategory").asText());
    assertEquals("recovery.retried", payload.get("events").get(2).get("eventType").asText());
    assertTrue(payload.get("events").get(2).get("interventionMarker").asBoolean());
    assertSchemaValid(HISTORY_SCHEMA_LOCATION, json);
  }

  @Test
  void statusJsonAcceptsNullActorEventAndLink() throws Exception {
    WorkflowStatusView view =
        new WorkflowStatusView(
            "run_emptystatus12345",
            WorkflowState.INBOX,
            null,
            null,
            null,
            null,
            List.of(),
            null,
            null,
            null,
            null,
            null,
            null,
            "await_outcome",
            0,
            false);
    when(inspection.getStatus("run_emptystatus12345")).thenReturn(view);

    String json = commands.status("run_emptystatus12345", "json", null, false, false, false);
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
    details.put("reviewerRole", "product_reviewer");
    details.put("taggedFeedback", "missing_scope");
    details.put("specRejectionLoopCount", 2);
    details.put("escalationMarker", false);
    WorkflowHistoryView view =
        new WorkflowHistoryView(
            "run_hist12345",
            List.of(
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

    String json = commands.history("run_hist12345", "json", null, "corr-hist-1", false);
    JsonNode payload = mapper.readTree(json);

    assertEquals(1, payload.get("schemaVersion").asInt());
    assertEquals("run_hist12345", payload.get("workflowRunId").asText());
    assertEquals(1, payload.get("events").size());
    JsonNode renderedDetails = payload.get("events").get(0).get("details");
    assertEquals("product_reviewer", renderedDetails.get("reviewerRole").asText());
    assertEquals("missing_scope", renderedDetails.get("taggedFeedback").asText());
    assertEquals(2, renderedDetails.get("specRejectionLoopCount").asInt());
    assertFalse(renderedDetails.get("escalationMarker").asBoolean());
    assertSchemaValid(HISTORY_SCHEMA_LOCATION, json);
  }

  @Test
  void historyJsonValidatesRecoveryDispatchFailedEventWithFullAuditKeySet() throws Exception {
    // Pin the schema + allow-list contract for recovery.dispatchFailed: every audit key the
    // service stamps must survive filtering and validate against workflow-history.v1.
    // (review F530)
    Map<String, Object> retriedDetails = new LinkedHashMap<>();
    retriedDetails.put("failedStage", "execution");
    retriedDetails.put("triggeringEventId", "evt_failure-aaaa1");
    retriedDetails.put("correlationId", "corr-disp-1");
    retriedDetails.put("reason", "transient broker outage cleared at 12:00");
    Map<String, Object> dispatchFailedDetails = new LinkedHashMap<>();
    dispatchFailedDetails.put("failedStage", "execution");
    dispatchFailedDetails.put("recoveryActionId", "rcv_recov-aaaaa");
    dispatchFailedDetails.put("recoveryRetriedEventId", "evt_recret-bbbbb");
    dispatchFailedDetails.put("errorCode", "RUNNER_CONTRACT_VIOLATION");
    dispatchFailedDetails.put("errorClass", "DomainException");
    dispatchFailedDetails.put("compensationFailed", true);
    dispatchFailedDetails.put("correlationId", "corr-disp-1");
    WorkflowHistoryView view =
        new WorkflowHistoryView(
            "run_dispatchfail",
            List.of(
                new WorkflowEventView(
                    "evt_retried-aaaaa",
                    "recovery.retried",
                    "Failed",
                    "Executing",
                    "alex",
                    "human",
                    "retry from failed execution",
                    null,
                    true,
                    OffsetDateTime.parse("2026-05-13T10:05:00Z"),
                    retriedDetails),
                new WorkflowEventView(
                    "evt_dispfail-bbbbb",
                    "recovery.dispatchFailed",
                    "Executing",
                    "Executing",
                    "alex",
                    "human",
                    "broker dispatch failed: RUNNER_CONTRACT_VIOLATION",
                    null,
                    true,
                    OffsetDateTime.parse("2026-05-13T10:05:01Z"),
                    dispatchFailedDetails)));
    when(inspection.listHistory("run_dispatchfail", null)).thenReturn(view);

    String json = commands.history("run_dispatchfail", "json", null, "corr-disp-1", false);
    JsonNode payload = mapper.readTree(json);

    JsonNode dispatchFailed = payload.get("events").get(1);
    assertEquals("recovery.dispatchFailed", dispatchFailed.get("eventType").asText());
    JsonNode renderedDetails = dispatchFailed.get("details");
    assertEquals("execution", renderedDetails.get("failedStage").asText());
    assertEquals("rcv_recov-aaaaa", renderedDetails.get("recoveryActionId").asText());
    assertEquals("evt_recret-bbbbb", renderedDetails.get("recoveryRetriedEventId").asText());
    assertEquals("RUNNER_CONTRACT_VIOLATION", renderedDetails.get("errorCode").asText());
    assertEquals("DomainException", renderedDetails.get("errorClass").asText());
    assertTrue(renderedDetails.get("compensationFailed").asBoolean());
    assertEquals("corr-disp-1", renderedDetails.get("correlationId").asText());
    assertSchemaValid(HISTORY_SCHEMA_LOCATION, json);
  }

  @Test
  void historyJsonAcceptsEmptyDetailsObject() throws Exception {
    WorkflowHistoryView view =
        new WorkflowHistoryView(
            "run_dropkey12345",
            List.of(
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

    String json = commands.history("run_dropkey12345", "json", null, null, false);
    JsonNode payload = mapper.readTree(json);
    assertTrue(payload.get("events").get(0).get("details").isObject());
    assertTrue(payload.get("events").get(0).get("resultingState").isNull());
    assertSchemaValid(HISTORY_SCHEMA_LOCATION, json);
  }

  @Test
  void historySchemaRejectsLeakedIdempotencyKeyAsAdditionalPropertyDefenseInDepth()
      throws Exception {
    // Defense-in-depth: `additionalProperties: false` on events[].details is the schema-level
    // safety net for the CLI render allow-list. If a future refactor regresses
    // WorkflowInspectionService.filterAllowedDetails and lets `idempotencyKey` leak through,
    // the schema must reject the payload before it reaches a CLI consumer. Simulate the
    // regression by constructing a view with idempotencyKey in details (bypassing the real
    // inspection-layer strip via the mock), serialize through `commands.history`, and assert
    // schema validation FAILS. (review A4 / 2026-05-16)
    Map<String, Object> leakingDetails = new LinkedHashMap<>();
    leakingDetails.put("correlationId", "corr-leak-1");
    leakingDetails.put("idempotencyKey", "idem-this-should-have-been-stripped");
    WorkflowHistoryView view =
        new WorkflowHistoryView(
            "run_schemaleak",
            List.of(
                new WorkflowEventView(
                    "evt_leakkey-aaaa1",
                    "recovery.retried",
                    "Failed",
                    "Executing",
                    "alex",
                    "human",
                    "retry from failed execution",
                    null,
                    true,
                    OffsetDateTime.parse("2026-05-13T10:05:00Z"),
                    leakingDetails)));
    when(inspection.listHistory("run_schemaleak", null)).thenReturn(view);

    String json = commands.history("run_schemaleak", "json", null, "corr-leak-1", false);

    Schema schema = schemaRegistry.getSchema(SchemaLocation.of(HISTORY_SCHEMA_LOCATION));
    Result validation = schema.walk(json, InputFormat.JSON, true);
    assertFalse(
        validation.getErrors().isEmpty(),
        "workflow-history.v1 schema MUST reject a leaked idempotencyKey in events[].details — "
            + "additionalProperties:false is the defense-in-depth backstop for the allow-list strip");
  }

  private void assertSchemaValid(String schemaLocation, String json) {
    Schema schema = schemaRegistry.getSchema(SchemaLocation.of(schemaLocation));
    Result validation = schema.walk(json, InputFormat.JSON, true);
    assertTrue(
        validation.getErrors().isEmpty(),
        () -> "Schema validation errors: " + validation.getErrors());
  }
}
