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
import java.util.List;
import org.dradgo.domain.integration.ticketsource.TicketQueryResult;
import org.dradgo.domain.integration.ticketsource.TicketRef;
import org.dradgo.domain.integration.ticketsource.TicketSummary;
import org.junit.jupiter.api.Test;

/**
 * Story 3i-2 (AC3) — {@code ticket-query.v1} schema stability contract. Validates the rendered JSON
 * against the classpath schema (mirror {@code AuditQueryJsonSchemaContractTest}); pins {@code
 * schemaVersion=1}, the nullable {@code summary}, and {@code additionalProperties:false} as the
 * defense-in-depth backstop.
 */
class TicketQueryJsonSchemaContractTest {

  private static final String SCHEMA_LOCATION = "classpath:schemas/cli/ticket-query.v1.schema.json";

  private final WorkflowCommandOutputs outputs = new WorkflowCommandOutputs(new ObjectMapper());
  private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
  private final SchemaRegistry schemaRegistry =
      SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);

  @Test
  void populatedResultValidatesAndPinsSchemaVersionOne() throws Exception {
    TicketQueryResult result =
        TicketQueryResult.complete(
            List.of(
                new TicketSummary(TicketRef.of("PROJ-1"), "Fix rounding", "Totals round wrong"),
                new TicketSummary(TicketRef.of("PROJ-2"), "Body-less ticket", null)));

    String json = outputs.renderTicketQueryJson(result);

    assertEquals(1, mapper.readTree(json).get("schemaVersion").asInt());
    assertEquals(2, mapper.readTree(json).get("shownCount").asInt());
    assertEquals(2, mapper.readTree(json).get("totalCount").asInt());
    assertFalse(mapper.readTree(json).get("truncated").asBoolean());
    assertEquals("PROJ-1", mapper.readTree(json).get("tickets").get(0).get("ticketRef").asText());
    // A ticket with no description serializes summary as JSON null, not an empty string.
    assertTrue(mapper.readTree(json).get("tickets").get(1).get("summary").isNull());
    assertSchemaValid(json);
  }

  /**
   * `totalCount` is the SOURCE's match count, not the row count — a script must be able to tell a
   * capped page from a complete one, which is exactly what `truncated` answers.
   */
  @Test
  void truncatedResultReportsTheSourceTotalDistinctFromTheRowCount() throws Exception {
    TicketQueryResult result =
        new TicketQueryResult(
            List.of(new TicketSummary(TicketRef.of("PROJ-1"), "Fix rounding", null)), 412);

    String json = outputs.renderTicketQueryJson(result);

    assertEquals(1, mapper.readTree(json).get("shownCount").asInt());
    assertEquals(412, mapper.readTree(json).get("totalCount").asInt());
    assertTrue(mapper.readTree(json).get("truncated").asBoolean());
    assertSchemaValid(json);
  }

  @Test
  void emptyResultValidates() throws Exception {
    String json = outputs.renderTicketQueryJson(TicketQueryResult.empty());

    assertEquals(0, mapper.readTree(json).get("shownCount").asInt());
    assertEquals(0, mapper.readTree(json).get("totalCount").asInt());
    assertFalse(mapper.readTree(json).get("truncated").asBoolean());
    assertTrue(mapper.readTree(json).get("tickets").isEmpty());
    assertSchemaValid(json);
  }

  @Test
  void schemaRejectsUnknownTopLevelPropertyDefenseInDepth() {
    String tampered =
        "{\"schemaVersion\":1,\"shownCount\":0,\"totalCount\":0,\"truncated\":false,"
            + "\"tickets\":[],\"leaked\":\"nope\"}";

    Schema schema = schemaRegistry.getSchema(SchemaLocation.of(SCHEMA_LOCATION));
    Result validation = schema.walk(tampered, InputFormat.JSON, true);
    assertFalse(
        validation.getErrors().isEmpty(),
        "ticket-query.v1 MUST reject an unknown top-level property");
  }

  @Test
  void textRenderQuotesTitleAndSummaryAndReportsTotal() {
    String text =
        outputs.renderTicketQueryText(
            TicketQueryResult.complete(
                List.of(new TicketSummary(TicketRef.of("PROJ-1"), "Fix \"rounding\"", null))),
            false);

    assertTrue(text.startsWith("shown: 1\ntotal: 1"), text);
    assertTrue(text.contains("PROJ-1"), text);
    // Embedded quotes are escaped so the key="value" shape stays parseable.
    assertTrue(text.contains("title=\"Fix \\\"rounding\\\"\""), text);
    // A null summary renders as an empty quoted value, never the literal "null".
    assertTrue(text.contains("summary=\"\""), text);
    // A complete page must not nag the operator to narrow a filter that is already showing all.
    assertFalse(text.contains("truncated:"), text);
  }

  /** A capped page tells the operator, in words, that they are not seeing everything. */
  @Test
  void textRenderAnnouncesTruncation() {
    String text =
        outputs.renderTicketQueryText(
            new TicketQueryResult(
                List.of(new TicketSummary(TicketRef.of("PROJ-1"), "Fix rounding", null)), 412),
            false);

    assertTrue(text.startsWith("shown: 1\ntotal: 412"), text);
    assertTrue(text.contains("truncated: yes"), text);
  }

  @Test
  void textRenderReportsNoneWhenEmpty() {
    assertEquals(
        "shown: 0\ntotal: 0\ntickets: (none)",
        outputs.renderTicketQueryText(TicketQueryResult.empty(), false));
  }

  private void assertSchemaValid(String json) {
    Schema schema = schemaRegistry.getSchema(SchemaLocation.of(SCHEMA_LOCATION));
    Result validation = schema.walk(json, InputFormat.JSON, true);
    assertTrue(
        validation.getErrors().isEmpty(),
        () -> "Schema validation errors: " + validation.getErrors());
  }
}
