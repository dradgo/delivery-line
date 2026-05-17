package org.dradgo.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;
import org.dradgo.domain.registry.WorkflowEventDetailKeys;
import org.junit.jupiter.api.Test;

/**
 * Pins {@link WorkflowEventDetailKeys} as the single source of truth for keys flowing through
 * {@code workflow_events.details}: producer, allow-list, JSON schema, and the PostgreSQL-native
 * filter. A rename in any one surface would break a different surface silently before this test
 * existed (story 1.18 review batch 3, finding D1).
 */
class WorkflowEventDetailKeysContractTest {

  private static final String HISTORY_SCHEMA_RESOURCE =
      "/schemas/cli/workflow-history.v1.schema.json";
  private static final Path REPOSITORY_PATH =
      Path.of(
          "src/main/java/org/dradgo/adapters/persistence/repository/WorkflowEventRepository.java");

  @Test
  void allowListedKeysAreDeclaredAsPropertiesInWorkflowHistorySchema() throws IOException {
    JsonNode schema = readSchema();
    JsonNode detailsProperties =
        schema
            .path("properties")
            .path("events")
            .path("items")
            .path("properties")
            .path("details")
            .path("properties");
    assertTrue(
        detailsProperties.isObject(),
        "workflow-history.v1.schema.json must declare an object under events[].details.properties");

    Set<String> schemaKeys = new LinkedHashSet<>();
    Iterator<String> names = detailsProperties.fieldNames();
    while (names.hasNext()) {
      schemaKeys.add(names.next());
    }

    assertEquals(
        Set.copyOf(WorkflowEventDetailKeys.ALLOW_LISTED_KEYS),
        schemaKeys,
        "Every allow-listed detail key in WorkflowEventDetailKeys MUST appear as a property in "
            + "workflow-history.v1.schema.json (and vice versa). A mismatch means the CLI "
            + "history allow-list and the JSON schema disagree on what may flow to operators.");
  }

  @Test
  void serverOnlyKeysAreAbsentFromHistorySchemaProperties() throws IOException {
    JsonNode schema = readSchema();
    JsonNode detailsProperties =
        schema
            .path("properties")
            .path("events")
            .path("items")
            .path("properties")
            .path("details")
            .path("properties");
    for (String serverOnly : WorkflowEventDetailKeys.SERVER_ONLY_KEYS) {
      assertFalse(
          detailsProperties.has(serverOnly),
          () ->
              "Server-only key '"
                  + serverOnly
                  + "' MUST NOT be declared in "
                  + "workflow-history.v1.schema.json properties — additionalProperties:false "
                  + "must reject it from the rendered view.");
    }
  }

  @Test
  void detailsSchemaSetsAdditionalPropertiesFalseSoStrippedKeysCannotLeakThroughTheView()
      throws IOException {
    JsonNode schema = readSchema();
    JsonNode detailsNode =
        schema.path("properties").path("events").path("items").path("properties").path("details");
    assertTrue(
        detailsNode.has("additionalProperties"),
        "events[].details must declare additionalProperties (false) so unlisted keys are rejected.");
    assertFalse(
        detailsNode.get("additionalProperties").asBoolean(true),
        "events[].details.additionalProperties must be false — otherwise server-only keys "
            + "could pass schema validation and the allow-list strip becomes the only guard.");
  }

  @Test
  void repositoryNativeQueryReferencesCorrelationIdLiteralMatchingTheHolderConstant()
      throws IOException {
    // The PostgreSQL-native @Query annotation in WorkflowEventRepository cannot reference a
    // Java constant at compile time. This test scans the source for the exact literal
    // `details->>'<correlationId>'` and asserts the embedded key matches the holder, so a
    // rename of WorkflowEventDetailKeys.CORRELATION_ID forces an intentional update to the SQL.
    String repositorySource = Files.readString(REPOSITORY_PATH, StandardCharsets.UTF_8);
    String expectedLiteral = "details->>'" + WorkflowEventDetailKeys.CORRELATION_ID + "'";
    assertTrue(
        repositorySource.contains(expectedLiteral),
        () ->
            "WorkflowEventRepository's native query MUST contain the literal "
                + expectedLiteral
                + " sourced from WorkflowEventDetailKeys.CORRELATION_ID. "
                + "If the holder constant was renamed, update the @Query annotation accordingly.");
  }

  private static JsonNode readSchema() throws IOException {
    try (InputStream stream =
        WorkflowEventDetailKeysContractTest.class.getResourceAsStream(HISTORY_SCHEMA_RESOURCE)) {
      assertNotNull(stream, "workflow-history.v1.schema.json not found on classpath");
      return new ObjectMapper().readTree(stream);
    }
  }
}
