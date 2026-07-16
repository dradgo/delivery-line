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
import org.dradgo.application.artifact.LineageReconciliationResult;
import org.junit.jupiter.api.Test;

/**
 * Story 4.16a (AC9 / Review P1) — {@code operator-reconcile-lineage.v1} schema stability contract.
 * Validates the CLI JSON render ({@code deliveryline operator reconcile-lineage --format json})
 * against the classpath schema (mirror {@code OperatorDiagnoseJsonSchemaContractTest}); pins {@code
 * schemaVersion=1}, the nullable {@code lineageReferenceArtifactId}/{@code correlationId}/{@code
 * reconciledEventId} fields, and {@code additionalProperties:false} as the defense-in-depth
 * backstop against silent wire drift.
 */
class OperatorReconcileLineageJsonSchemaContractTest {

  private static final String SCHEMA_LOCATION =
      "classpath:schemas/cli/operator-reconcile-lineage.v1.schema.json";

  private final WorkflowCommandOutputs outputs = new WorkflowCommandOutputs(new ObjectMapper());
  private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
  private final SchemaRegistry schemaRegistry =
      SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);

  @Test
  void reattachResultValidatesAndPinsSchemaVersionOne() throws Exception {
    LineageReconciliationResult result =
        new LineageReconciliationResult(
            "art_target01",
            "reattach_to_existing_lineage",
            "rcv_action01",
            "evt_rec01",
            "art_parent01",
            "corr_lineage01",
            false);

    String json = outputs.renderOperatorReconcileLineageJson(result);

    assertEquals(1, mapper.readTree(json).get("schemaVersion").asInt());
    assertEquals(
        "reattach_to_existing_lineage", mapper.readTree(json).get("lineageAction").asText());
    assertEquals("art_parent01", mapper.readTree(json).get("lineageReferenceArtifactId").asText());
    assertFalse(mapper.readTree(json).get("replayed").asBoolean());
    assertSchemaValid(json);
  }

  @Test
  void terminateReplayWithNullReferenceAndCorrelationValidates() throws Exception {
    LineageReconciliationResult result =
        new LineageReconciliationResult(
            "art_target02",
            "terminate_ambiguous_lineage",
            "rcv_action02",
            "evt_rec02",
            null,
            null,
            true);

    String json = outputs.renderOperatorReconcileLineageJson(result);

    assertTrue(mapper.readTree(json).get("lineageReferenceArtifactId").isNull());
    assertTrue(mapper.readTree(json).get("correlationId").isNull());
    assertTrue(mapper.readTree(json).get("replayed").asBoolean());
    assertSchemaValid(json);
  }

  @Test
  void schemaRejectsUnknownTopLevelPropertyDefenseInDepth() throws Exception {
    String tampered =
        "{\"schemaVersion\":1,\"targetArtifactId\":\"art_x\","
            + "\"lineageAction\":\"terminate_ambiguous_lineage\",\"recoveryActionId\":\"rcv_x\","
            + "\"reconciledEventId\":\"evt_x\",\"lineageReferenceArtifactId\":null,"
            + "\"correlationId\":null,\"replayed\":false,\"leaked\":\"nope\"}";

    Schema schema = schemaRegistry.getSchema(SchemaLocation.of(SCHEMA_LOCATION));
    Result validation = schema.walk(tampered, InputFormat.JSON, true);
    assertFalse(
        validation.getErrors().isEmpty(),
        "operator-reconcile-lineage.v1 MUST reject an unknown top-level property");
  }

  private void assertSchemaValid(String json) {
    Schema schema = schemaRegistry.getSchema(SchemaLocation.of(SCHEMA_LOCATION));
    Result validation = schema.walk(json, InputFormat.JSON, true);
    assertTrue(
        validation.getErrors().isEmpty(),
        () -> "Schema validation errors: " + validation.getErrors());
  }
}
