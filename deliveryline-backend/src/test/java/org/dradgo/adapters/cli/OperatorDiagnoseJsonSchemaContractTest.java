package org.dradgo.adapters.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Result;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.time.OffsetDateTime;
import java.util.List;
import org.dradgo.application.workflow.WorkflowInspectionService.FailureDiagnostics;
import org.dradgo.application.workflow.WorkflowInspectionService.IntegrationSyncStatusView;
import org.dradgo.application.workflow.WorkflowInspectionService.RecommendedActionView;
import org.dradgo.application.workflow.WorkflowInspectionService.RunnerLogReferenceView;
import org.dradgo.domain.registry.WorkflowState;
import org.junit.jupiter.api.Test;

/**
 * Story 4.4 (AC3) — {@code operator-diagnose.v1} schema stability contract. Validates the rendered
 * JSON against the classpath schema (mirror {@code OperatorStatusJsonSchemaContractTest}); pins
 * {@code schemaVersion=1}, the nullable fields, the {@code integrationSyncStatus} pair, and {@code
 * additionalProperties:false} as the defense-in-depth backstop.
 */
class OperatorDiagnoseJsonSchemaContractTest {

  private static final String SCHEMA_LOCATION =
      "classpath:schemas/cli/operator-diagnose.v1.schema.json";

  private final WorkflowCommandOutputs outputs = new WorkflowCommandOutputs(new ObjectMapper());
  private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
  private final SchemaRegistry schemaRegistry =
      SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);

  @Test
  void populatedDiagnosticsValidatesAndPinsSchemaVersionOne() throws Exception {
    OffsetDateTime t = OffsetDateTime.parse("2026-07-05T10:00:00Z");
    FailureDiagnostics view =
        new FailureDiagnostics(
            WorkflowState.FAILED,
            "execution",
            "Executing",
            "runner_timeout",
            "boom (redacted)",
            t,
            t,
            "corr_abc123",
            "Executing",
            null,
            "retry",
            "local-operator",
            new RunnerLogReferenceView(
                "rex_log001",
                "/home/deliveryline/runner-logs/rex_log001",
                128L,
                "shareable-redacted",
                2),
            new IntegrationSyncStatusView("linear", "LIN-101", "synced", t),
            null,
            List.of(
                new RecommendedActionView(
                    "retry", "safe", "Retry from the failed stage.", "workspace intact")));

    String json = outputs.renderDiagnoseJson(view);

    assertEquals(1, mapper.readTree(json).get("schemaVersion").asInt());
    assertEquals("Failed", mapper.readTree(json).get("currentState").asText());
    assertTrue(mapper.readTree(json).get("integrationSyncStatus").get("github").isNull());
    assertEquals(
        "rex_log001",
        mapper.readTree(json).get("runnerLogReference").get("runnerExecutionId").asText());
    assertSchemaValid(json);
  }

  @Test
  void benignNonFailedDiagnosticsValidates() throws Exception {
    FailureDiagnostics view =
        new FailureDiagnostics(
            WorkflowState.COMPLETED,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            "view_only",
            "system",
            null,
            null,
            null,
            List.of());

    String json = outputs.renderDiagnoseJson(view);

    assertTrue(mapper.readTree(json).get("runnerLogReference").isNull());
    assertTrue(mapper.readTree(json).get("integrationSyncStatus").get("linear").isNull());
    assertTrue(mapper.readTree(json).get("recommendedRecoveryActions").isEmpty());
    assertSchemaValid(json);
  }

  @Test
  void schemaRejectsUnknownTopLevelPropertyDefenseInDepth() throws Exception {
    String tampered =
        "{\"schemaVersion\":1,\"currentState\":\"Failed\",\"failedStage\":null,"
            + "\"lastSuccessfulStage\":null,\"failureCategory\":null,\"failureReason\":null,"
            + "\"failureTimestamp\":null,\"lastActivityTimestamp\":null,\"correlationId\":null,"
            + "\"lastGoodState\":null,\"currentBlockingReason\":null,\"nextSafeAction\":null,"
            + "\"lastActorIdentity\":\"system\",\"runnerLogReference\":null,"
            + "\"integrationSyncStatus\":{\"linear\":null,\"github\":null},"
            + "\"recommendedRecoveryActions\":[],\"leaked\":\"nope\"}";

    Schema schema = schemaRegistry.getSchema(SchemaLocation.of(SCHEMA_LOCATION));
    Result validation = schema.walk(tampered, InputFormat.JSON, true);
    assertFalse(
        validation.getErrors().isEmpty(),
        "operator-diagnose.v1 MUST reject an unknown top-level property");
  }

  private void assertSchemaValid(String json) {
    Schema schema = schemaRegistry.getSchema(SchemaLocation.of(SCHEMA_LOCATION));
    Result validation = schema.walk(json, InputFormat.JSON, true);
    assertTrue(
        validation.getErrors().isEmpty(),
        () -> "Schema validation errors: " + validation.getErrors());
  }
}
