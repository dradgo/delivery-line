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
import org.dradgo.application.audit.AuditQueryService.AuditEventRow;
import org.dradgo.application.audit.AuditQueryService.AuditQueryResult;
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.FailureCategory;
import org.dradgo.domain.registry.WorkflowEventType;
import org.dradgo.domain.registry.WorkflowState;
import org.junit.jupiter.api.Test;

/**
 * Story 4.3 (AC4) — {@code audit-query.v1} schema stability contract. Validates the rendered JSON
 * against the classpath schema (mirror {@code OperatorStatusJsonSchemaContractTest}); pins {@code
 * schemaVersion=1}, the nullable row fields, and {@code additionalProperties:false} as the
 * defense-in-depth backstop.
 */
class AuditQueryJsonSchemaContractTest {

  private static final String SCHEMA_LOCATION = "classpath:schemas/cli/audit-query.v1.schema.json";

  private final WorkflowCommandOutputs outputs = new WorkflowCommandOutputs(new ObjectMapper());
  private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
  private final SchemaRegistry schemaRegistry =
      SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);

  @Test
  void populatedResultValidatesAndPinsSchemaVersionOne() throws Exception {
    OffsetDateTime t = OffsetDateTime.parse("2026-07-06T10:00:00Z");
    AuditQueryResult view =
        new AuditQueryResult(
            List.of(
                new AuditEventRow(
                    "evt_fail0001",
                    WorkflowEventType.RUNNER_FAILED.value(),
                    "run_abc123",
                    "system",
                    ActorType.SYSTEM,
                    t,
                    WorkflowState.EXECUTING,
                    WorkflowState.FAILED,
                    FailureCategory.ORPHAN,
                    "runner orphaned",
                    "cor_xyz",
                    "art_100"),
                new AuditEventRow(
                    "evt_state002",
                    WorkflowEventType.WORKFLOW_STATE_CHANGED.value(),
                    "run_abc123",
                    "alex",
                    ActorType.HUMAN,
                    t,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null)),
            2,
            "cursor_next");

    String json = outputs.renderAuditQueryJson(view);

    assertEquals(1, mapper.readTree(json).get("schemaVersion").asInt());
    assertEquals(2, mapper.readTree(json).get("totalCount").asInt());
    assertTrue(mapper.readTree(json).get("events").get(1).get("priorState").isNull());
    assertTrue(mapper.readTree(json).get("events").get(1).get("failureCategory").isNull());
    assertTrue(mapper.readTree(json).get("events").get(1).get("reason").isNull());
    assertSchemaValid(json);
  }

  @Test
  void emptyResultValidates() throws Exception {
    AuditQueryResult view = new AuditQueryResult(List.of(), 0, null);

    String json = outputs.renderAuditQueryJson(view);

    assertEquals(0, mapper.readTree(json).get("totalCount").asInt());
    assertTrue(mapper.readTree(json).get("nextCursor").isNull());
    assertSchemaValid(json);
  }

  @Test
  void schemaRejectsUnknownTopLevelPropertyDefenseInDepth() {
    String tampered =
        "{\"schemaVersion\":1,\"totalCount\":0,\"nextCursor\":null,\"events\":[],"
            + "\"leaked\":\"nope\"}";

    Schema schema = schemaRegistry.getSchema(SchemaLocation.of(SCHEMA_LOCATION));
    Result validation = schema.walk(tampered, InputFormat.JSON, true);
    assertFalse(
        validation.getErrors().isEmpty(),
        "audit-query.v1 MUST reject an unknown top-level property");
  }

  private void assertSchemaValid(String json) {
    Schema schema = schemaRegistry.getSchema(SchemaLocation.of(SCHEMA_LOCATION));
    Result validation = schema.walk(json, InputFormat.JSON, true);
    assertTrue(
        validation.getErrors().isEmpty(),
        () -> "Schema validation errors: " + validation.getErrors());
  }
}
