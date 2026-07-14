package org.dradgo.adapters.rest;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.dradgo.TestcontainersConfiguration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;

/**
 * Story 6.9 AC6 — OpenAPI snapshot drift gate.
 *
 * <p>Boots the app, fetches the live springdoc document from {@code /v3/api-docs}, canonicalizes it
 * (recursively key-sorted, {@code "\n"} line endings, trailing newline — platform-independent) and
 * asserts it is byte-identical to the committed reference snapshot {@code
 * src/main/resources/openapi/openapi.json}. This is the deterministic, cross-platform replacement
 * for the previous {@code springdoc:generate} CI step (which could not run standalone without a
 * live app). Story 2.6's generated TypeScript client is built from the committed snapshot, so a
 * silent backend contract change must fail here.
 *
 * <p>Bootstrap / regeneration: when the snapshot file is absent, or when {@code
 * -Dopenapi.snapshot.write=true} is passed, the canonical document is written to the source path
 * and the test fails asking the developer to review + commit, then re-run. This keeps a missing or
 * intentionally-changed snapshot from silently passing.
 *
 * <p>Also pins AC9 / TRAP 1: the document MUST still expose the pre-existing POST command
 * operations (submit/approve-spec/reject-spec/retry/takeover) alongside the new GET reads — story
 * 6.9 is read-only-additive and must not delete the command surface.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"test", "linear-mock"})
@Tag("contract")
class OpenApiSnapshotContractTest {

  private static final Path SNAPSHOT_PATH =
      Path.of("src", "main", "resources", "openapi", "openapi.json");
  private static final String WRITE_FLAG = "openapi.snapshot.write";

  private static final ObjectMapper MAPPER =
      new ObjectMapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
  private static final ObjectWriter CANONICAL_WRITER =
      MAPPER.writer(
          new DefaultPrettyPrinter()
              .withObjectIndenter(new DefaultIndenter("  ", "\n"))
              .withArrayIndenter(new DefaultIndenter("  ", "\n")));

  @Autowired private Environment environment;

  @Test
  void liveOpenApiDocMatchesCommittedSnapshot() throws IOException, InterruptedException {
    int port = environment.getRequiredProperty("local.server.port", Integer.class);
    // Hit 127.0.0.1 explicitly: a successful response also proves the connector bound loopback.
    HttpResponse<String> response =
        HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + port + "/v3/api-docs"))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).as("/v3/api-docs must be served by springdoc").isEqualTo(200);

    String canonical = canonicalize(response.body());

    // AC9 / TRAP 1 — the read endpoints are additive; the command surface must survive.
    assertThat(canonical)
        .as("OpenAPI must keep the pre-existing POST command operations (story 6.9 is additive)")
        .contains("submitWorkflow")
        .contains("approveSpec")
        .contains("rejectSpec")
        .contains("acceptImplementation")
        .contains("rejectImplementation")
        .contains("retryWorkflow")
        .contains("takeoverWorkflow");
    assertThat(canonical)
        .as("OpenAPI must expose the new read operations")
        .contains("listWorkflows")
        .contains("getWorkflow")
        .contains("getWorkflowEvents");
    assertThat(canonical)
        .as("OpenAPI must expose the story 4.2 operator fleet-view read operation")
        .contains("listOperatorRuns");
    assertThat(canonical)
        .as("OpenAPI must expose the story 4.3 audit-query read operations")
        .contains("queryAuditByTicket")
        .contains("queryAuditByRun");
    assertThat(canonical)
        .as(
            "OpenAPI must expose the story 4.4 failure-diagnostics + runner-log download operations")
        .contains("getFailureDiagnostics")
        .contains("downloadRunnerLog");
    assertThat(canonical)
        .as("OpenAPI must expose the story 3c-8 project operations")
        .contains("listProjects")
        .contains("createProject")
        .contains("getProject")
        .contains("updateProject")
        .contains("disableProject")
        .contains("enableProject")
        .contains("setProjectCredential")
        .contains("testProjectConnection");
    assertThat(canonical)
        .as("OpenAPI must expose the story 3i-2 filtered ticket-intake browse operation")
        .contains("queryProjectTickets");
    assertThat(canonical)
        .as("OpenAPI must expose the story 4.18 integration-conflict inspection operations")
        .contains("listIntegrationConflicts")
        .contains("getIntegrationConflict");

    JsonNode document = MAPPER.readTree(canonical);
    assertThat(document.at(pointer("servers")).isArray()).isTrue();
    assertThat(document.at(pointer("servers", "0", "url")).asText())
        .isEqualTo("http://127.0.0.1:8080");
    assertProblemDetailsSchema(document, "/api/v1/workflows/{workflowRunId}", "400");
    assertProblemDetailsSchema(document, "/api/v1/workflows/{workflowRunId}", "404");
    assertProblemDetailsSchema(document, "/api/v1/workflows/{workflowRunId}/events", "400");
    assertProblemDetailsSchema(document, "/api/v1/workflows/{workflowRunId}/events", "404");
    assertProblemDetailsDetailsSchema(document);
    assertWorkflowEventsSchema(document);

    boolean writeRequested = Boolean.getBoolean(WRITE_FLAG);
    if (writeRequested || !Files.isRegularFile(SNAPSHOT_PATH)) {
      Files.createDirectories(SNAPSHOT_PATH.getParent());
      Files.writeString(SNAPSHOT_PATH, canonical, StandardCharsets.UTF_8);
      org.junit.jupiter.api.Assertions.fail(
          "OpenAPI snapshot (re)generated at "
              + SNAPSHOT_PATH.toAbsolutePath()
              + " — review the diff, commit it, then re-run. (Triggered by "
              + (writeRequested ? "-Dopenapi.snapshot.write=true" : "missing snapshot file")
              + ".)");
    }

    String committed = Files.readString(SNAPSHOT_PATH, StandardCharsets.UTF_8);
    assertThat(canonical)
        .as(
            "OpenAPI drift: live /v3/api-docs differs from committed %s. "
                + "Regenerate with -Dopenapi.snapshot.write=true, review, and commit.",
            SNAPSHOT_PATH)
        .isEqualTo(committed);
  }

  /**
   * Recursively key-sorted, "\n"-terminated pretty JSON — stable across runs and platforms.
   * Deserializing to {@code Object} (LinkedHashMap/List) lets {@code ORDER_MAP_ENTRIES_BY_KEYS}
   * sort every nested object on write (it does not reorder Jackson {@code ObjectNode}s).
   */
  private static String canonicalize(String rawJson) throws IOException {
    Object tree = MAPPER.readValue(rawJson, Object.class);
    return CANONICAL_WRITER.writeValueAsString(tree) + "\n";
  }

  private static void assertProblemDetailsSchema(
      JsonNode document, String path, String responseCode) {
    JsonNode schemaRef =
        document.at(
            pointer(
                "paths",
                path,
                "get",
                "responses",
                responseCode,
                "content",
                "application/problem+json",
                "schema",
                "$ref"));
    assertThat(schemaRef.isTextual())
        .as(
            "OpenAPI %s %s response must reference the typed ProblemDetailsResponse schema",
            path, responseCode)
        .isTrue();
    assertThat(schemaRef.asText()).isEqualTo("#/components/schemas/ProblemDetailsResponse");
  }

  private static void assertWorkflowEventsSchema(JsonNode document) {
    JsonNode responseSchema =
        document.at(pointer("components", "schemas", "WorkflowEventsResponse"));
    assertRequiredProperties(responseSchema, "workflowRun", "events");
    assertThat(responseSchema.at("/properties/events/minItems").asInt()).isEqualTo(1);

    JsonNode runHeader = document.at(pointer("components", "schemas", "WorkflowRunRef"));
    assertRequiredProperties(runHeader, "publicId", "ticketRef", "createdAt", "terminalState");

    JsonNode event = document.at(pointer("components", "schemas", "WorkflowEvent"));
    assertThat(event.at("/properties/publicId/pattern").asText())
        .isEqualTo("^evt_[A-Za-z0-9_]{4,}$");
    assertThat(event.at("/properties/workflowRunPublicId/pattern").asText())
        .isEqualTo("^run_[A-Za-z0-9_]{4,}$");
    assertRequiredProperties(
        event,
        "publicId",
        "workflowRunPublicId",
        "eventType",
        "actorIdentity",
        "actorType",
        "interventionMarker",
        "createdAt",
        "details");
    JsonNode details = event.at("/properties/details");
    assertThat(details.path("type").asText()).isEqualTo("object");
    assertThat(details.at("/not/required/0").asText()).isEqualTo("idempotencyKey");
    assertThat(details.at("/properties/artifactVersion/type").asText()).isEqualTo("integer");
    assertThat(details.at("/properties/artifactVariant/enum/0").asText()).isEqualTo("spec");
    assertThat(details.at("/properties/recoveryActionId/pattern").asText())
        .isEqualTo("^rcv_[A-Za-z0-9_]{4,}$");
  }

  private static void assertProblemDetailsDetailsSchema(JsonNode document) {
    JsonNode details =
        document.at(
            pointer("components", "schemas", "ProblemDetailsResponse", "properties", "details"));
    assertThat(details.path("anyOf").isArray()).isTrue();
    assertThat(details.path("anyOf")).hasSize(2);
    assertThat(details.at("/anyOf/0/type").asText()).isEqualTo("object");
    assertThat(details.at("/anyOf/1/type").asText()).isEqualTo("array");
  }

  private static void assertRequiredProperties(JsonNode schemaNode, String... expectedNames) {
    List<String> actual = new ArrayList<>();
    schemaNode.path("required").forEach(node -> actual.add(node.asText()));
    assertThat(actual).containsExactlyInAnyOrder(expectedNames);
  }

  private static String pointer(String... segments) {
    StringBuilder pointer = new StringBuilder();
    for (String segment : segments) {
      pointer.append('/').append(segment.replace("~", "~0").replace("/", "~1"));
    }
    return pointer.toString();
  }
}
