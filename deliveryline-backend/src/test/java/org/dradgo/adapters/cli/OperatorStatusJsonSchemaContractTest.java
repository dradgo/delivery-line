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
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.dradgo.application.workflow.WorkflowInspectionService.OperatorRunRow;
import org.dradgo.application.workflow.WorkflowInspectionService.OperatorRunSummary;
import org.dradgo.domain.registry.FailureCategory;
import org.dradgo.domain.registry.WorkflowState;
import org.junit.jupiter.api.Test;

/**
 * Story 4.1 (AC5) — {@code operator-run-summary.v1} schema stability contract. Validates the
 * rendered JSON against the classpath schema (mirror {@code WorkflowCliJsonSchemaContractTest});
 * pins {@code schemaVersion=1}, the nullable row fields, and {@code additionalProperties:false} as
 * the defense-in-depth backstop.
 */
class OperatorStatusJsonSchemaContractTest {

  private static final String SCHEMA_LOCATION =
      "classpath:schemas/cli/operator-run-summary.v1.schema.json";

  private final WorkflowCommandOutputs outputs = new WorkflowCommandOutputs(new ObjectMapper());
  private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
  private final SchemaRegistry schemaRegistry =
      SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);

  @Test
  void populatedSummaryValidatesAndPinsSchemaVersionOne() throws Exception {
    OffsetDateTime t = OffsetDateTime.parse("2026-07-05T10:00:00Z");
    Map<WorkflowState, Integer> byState = new EnumMap<>(WorkflowState.class);
    byState.put(WorkflowState.FAILED, 2);
    byState.put(WorkflowState.TAKEN_OVER, 1);
    Map<FailureCategory, Integer> byFailure = new EnumMap<>(FailureCategory.class);
    byFailure.put(FailureCategory.ORPHAN, 1);
    OperatorRunSummary view =
        new OperatorRunSummary(
            3,
            byState,
            byFailure,
            t,
            List.of(
                new OperatorRunRow(
                    "run_orphan001",
                    WorkflowState.FAILED,
                    "orphan",
                    t,
                    "system",
                    "LIN-101",
                    "octo/repo#7",
                    true,
                    t,
                    "ORPHANED",
                    null,
                    0),
                new OperatorRunRow(
                    "run_active002",
                    WorkflowState.EXECUTING,
                    null,
                    t,
                    null,
                    null,
                    null,
                    false,
                    null,
                    "STALLED",
                    null,
                    0)),
            null);

    String json = outputs.renderOperatorSummaryJson(view);

    assertEquals(1, mapper.readTree(json).get("schemaVersion").asInt());
    assertTrue(mapper.readTree(json).get("runs").get(1).get("failureCategory").isNull());
    assertTrue(mapper.readTree(json).get("runs").get(1).get("oldestEventAt").isNull());
    assertSchemaValid(json);
  }

  @Test
  void emptySummaryValidates() throws Exception {
    OperatorRunSummary view = new OperatorRunSummary(0, Map.of(), Map.of(), null, List.of(), null);

    String json = outputs.renderOperatorSummaryJson(view);

    assertEquals(0, mapper.readTree(json).get("total").asInt());
    assertTrue(mapper.readTree(json).get("oldestEntryAt").isNull());
    assertSchemaValid(json);
  }

  @Test
  void schemaRejectsUnknownTopLevelPropertyDefenseInDepth() throws Exception {
    // additionalProperties:false must reject a leaked/unexpected top-level field before a consumer
    // sees it — the schema-level backstop for the renderer.
    String tampered =
        "{\"schemaVersion\":1,\"total\":0,\"oldestEntryAt\":null,\"byState\":{},"
            + "\"byFailureCategory\":{},\"runs\":[],\"leaked\":\"nope\"}";

    Schema schema = schemaRegistry.getSchema(SchemaLocation.of(SCHEMA_LOCATION));
    Result validation = schema.walk(tampered, InputFormat.JSON, true);
    assertFalse(
        validation.getErrors().isEmpty(),
        "operator-run-summary.v1 MUST reject an unknown top-level property");
  }

  private void assertSchemaValid(String json) {
    Schema schema = schemaRegistry.getSchema(SchemaLocation.of(SCHEMA_LOCATION));
    Result validation = schema.walk(json, InputFormat.JSON, true);
    assertTrue(
        validation.getErrors().isEmpty(),
        () -> "Schema validation errors: " + validation.getErrors());
  }
}
