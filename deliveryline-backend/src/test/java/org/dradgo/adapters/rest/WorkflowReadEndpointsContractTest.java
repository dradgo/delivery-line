package org.dradgo.adapters.rest;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.Error;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Result;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.dradgo.TestcontainersConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Story 6.9 contract test for the localhost REST read surface. One boot exercises:
 *
 * <ul>
 *   <li>AC1/AC2 — list / detail / events shapes (camelCase, ISO-8601 UTC, prefixed ids, direct
 *       resource shapes with no {@code {data:…}} envelope);
 *   <li>AC2/TRAP2 — {@code GET /events} validates against the committed wire schema and strips
 *       server-only {@code details.idempotencyKey} while preserving open-map keys
 *       (artifactVariant);
 *   <li>AC5 — REST + actuator + springdoc are reachable on {@code 127.0.0.1} (loopback bind);
 *   <li>AC7 — Problem Details ({@code INVALID_ID_PREFIX} 400, {@code RUN_NOT_FOUND} 404);
 *   <li>AC8 — {@code GET /{id}} &lt; 2s and {@code GET /{id}/events} &lt; 5s;
 *   <li>AC10 — {@code X-Correlation-Id} echoed on success + error and surfaced in the problem body;
 *   <li>Task 7 — a successful read emits the expected INFO log line.
 * </ul>
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"test", "linear-mock"})
@Tag("contract")
@ExtendWith(OutputCaptureExtension.class)
class WorkflowReadEndpointsContractTest {

  private static final Path EVENTS_SCHEMA =
      Path.of(
          "src",
          "test",
          "resources",
          "fixture-event-streams",
          "schema",
          "workflow-events-response.schema.json");

  private static final String HAPPY_RUN = "run_read_happy0001";
  private static final String OTHER_RUN = "run_read_other0002";
  private static final String TICKET_REF = "DEL-7777";

  @Autowired private Environment environment;
  @Autowired private JdbcTemplate jdbcTemplate;

  private final ObjectMapper mapper = new ObjectMapper();
  private final HttpClient http = HttpClient.newHttpClient();

  /**
   * Order-independent reset of the shared singleton Testcontainers Postgres. Sibling contract-test
   * classes commit {@code artifacts}/{@code artifact_operations} rows whose {@code linked_event_id}
   * FK references {@code workflow_events} with {@code on delete restrict} and do not clean up, so a
   * plain {@code delete from workflow_events} here is blocked by {@code
   * fk_artifacts_workflow_events} ("Key (id)=… is still referenced from table artifacts"). Every
   * domain table FK-references {@code workflow_runs}, so {@code TRUNCATE … CASCADE} clears them all
   * in correct FK order and stays correct as new child tables are added.
   */
  private void wipeDomainTables() {
    jdbcTemplate.update("truncate table workflow_runs restart identity cascade");
  }

  @AfterEach
  void cleanUp() {
    wipeDomainTables();
  }

  @BeforeEach
  void seed() {
    wipeDomainTables();

    long happy = insertRun(HAPPY_RUN, "WaitingForSpecApproval");
    // Real runs always have a linked ticket (submit links one in-transaction); seed it so the list
    // summary's ticketRef (sourced from the active integration link) resolves.
    insertIntegrationLink("ilk_read_happy0001", happy, TICKET_REF);
    OffsetDateTime t0 =
        OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(10).truncatedTo(ChronoUnit.MILLIS);
    // Submit event carries the ticket ref AND a server-only idempotencyKey that MUST be stripped.
    insertEvent(
        happy,
        "evt_read_happy001",
        "workflow.stateChanged",
        null,
        "Inbox",
        "system",
        "system",
        "submitted",
        false,
        t0,
        Map.of(
            "linearTicketReference", TICKET_REF,
            "correlationId", "corr-read-seed",
            "idempotencyKey", "idem-MUST-BE-STRIPPED"));
    // Artifact event carries artifactVariant — an open-map key the schema/UI need (NOT in the CLI
    // allow-list); it must survive wire sanitization.
    insertEvent(
        happy,
        "evt_read_happy002",
        "artifact.draftCreated",
        null,
        null,
        "runner-codex-mock",
        "agent",
        null,
        false,
        t0.plusMinutes(1),
        Map.of(
            "correlationId", "corr-read-seed",
            "artifactId", "art_spec_read0001",
            "artifactVariant", "spec",
            "artifactVersion", 1));
    insertEvent(
        happy,
        "evt_read_happy003",
        "workflow.stateChanged",
        "Investigating",
        "WaitingForSpecApproval",
        "system",
        "system",
        "spec_drafted",
        false,
        t0.plusMinutes(2),
        Map.of("correlationId", "corr-read-seed"));

    long other = insertRun(OTHER_RUN, "Completed");
    insertEvent(
        other,
        "evt_read_other001",
        "workflow.stateChanged",
        null,
        "Inbox",
        "system",
        "system",
        "submitted",
        false,
        t0.plusSeconds(5),
        Map.of("linearTicketReference", "DEL-8888", "correlationId", "corr-other-seed"));
  }

  @Test
  void listReturnsDirectArrayNewestFirstWithSummaryFields(CapturedOutput output) throws Exception {
    HttpResponse<String> response = get("/api/v1/workflows", null);
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(contentType(response)).contains("application/json");

    JsonNode body = mapper.readTree(response.body());
    assertThat(body.isArray()).as("list is a direct JSON array, not a {data:…} envelope").isTrue();
    assertThat(body).hasSize(2);

    JsonNode first = body.get(0);
    assertThat(first.fieldNames())
        .toIterable()
        .containsExactlyInAnyOrder(
            "workflowRunId",
            "currentState",
            "ticketRef",
            "lastEventAt",
            "lastEventType",
            "specRejectionLoopCount",
            "escalationMarker",
            "archivedAt",
            "parentRunId",
            "projectId",
            "projectName",
            "projectSlug",
            "ticketTitle");
    // Newest-first: OTHER_RUN was created after HAPPY_RUN.
    assertThat(first.get("workflowRunId").asText()).isEqualTo(OTHER_RUN);

    // Task 7 — successful read logs an INFO line with the result size.
    assertThat(output.getOut()).contains("REST list workflows success count=");
  }

  @Test
  void listFiltersByState() throws Exception {
    HttpResponse<String> response = get("/api/v1/workflows?state=WaitingForSpecApproval", null);
    assertThat(response.statusCode()).isEqualTo(200);
    JsonNode body = mapper.readTree(response.body());
    assertThat(body).hasSize(1);
    assertThat(body.get(0).get("workflowRunId").asText()).isEqualTo(HAPPY_RUN);
    assertThat(body.get(0).get("ticketRef").asText()).isEqualTo(TICKET_REF);
  }

  @Test
  void detailIsCamelCaseDirectShapeWithinTwoSeconds() throws Exception {
    long start = System.nanoTime();
    HttpResponse<String> response = get("/api/v1/workflows/" + HAPPY_RUN, null);
    long elapsedMs = (System.nanoTime() - start) / 1_000_000;

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(elapsedMs).as("AC8: GET /{id} < 2s").isLessThan(2_000);

    JsonNode body = mapper.readTree(response.body());
    assertThat(body.has("data")).as("no {data:…} envelope").isFalse();
    assertThat(body.get("workflowRunId").asText()).isEqualTo(HAPPY_RUN);
    assertThat(body.get("currentState").asText()).isEqualTo("WaitingForSpecApproval");
    assertThat(body.has("nextSafeAction")).isTrue();
  }

  @Test
  void eventsMatchCommittedSchemaStripIdempotencyKeyAndKeepArtifactVariantWithinFiveSeconds()
      throws Exception {
    long start = System.nanoTime();
    HttpResponse<String> response = get("/api/v1/workflows/" + HAPPY_RUN + "/events", null);
    long elapsedMs = (System.nanoTime() - start) / 1_000_000;

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(elapsedMs).as("AC8: GET /{id}/events < 5s").isLessThan(5_000);

    // Validate the LIVE response against the committed wire contract (TRAP 2 lock).
    Schema schema =
        SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
            .getSchema(Files.readString(EVENTS_SCHEMA), InputFormat.JSON);
    Result result = schema.walk(response.body(), InputFormat.JSON, true);
    List<Error> errors = result.getErrors();
    assertThat(errors)
        .as(
            "live /events response must validate against workflow-events-response.schema.json: %s",
            errors)
        .isEmpty();

    JsonNode body = mapper.readTree(response.body());
    assertThat(body.path("workflowRun").path("publicId").asText()).isEqualTo(HAPPY_RUN);
    assertThat(body.path("workflowRun").path("ticketRef").asText()).isEqualTo(TICKET_REF);
    assertThat(body.path("workflowRun").path("terminalState").asText())
        .isEqualTo("WaitingForSpecApproval");
    assertThat(body.path("workflowRun").path("createdAt").isTextual()).isTrue();

    JsonNode events = body.path("events");
    assertThat(events.isArray()).isTrue();
    boolean artifactVariantPreserved = false;
    for (JsonNode event : events) {
      assertThat(event.path("details").has("idempotencyKey"))
          .as("server-only idempotencyKey must never leak into the wire response")
          .isFalse();
      assertThat(event.path("workflowRunPublicId").asText()).isEqualTo(HAPPY_RUN);
      if (event.path("details").has("artifactVariant")) {
        artifactVariantPreserved = true;
        assertThat(event.path("details").path("artifactVariant").asText()).isEqualTo("spec");
      }
    }
    assertThat(artifactVariantPreserved)
        .as("open-map key artifactVariant must survive wire sanitization")
        .isTrue();
  }

  @Test
  void detailCarriesRunLevelTotalTokensSummedOverReportedSteps(CapturedOutput output)
      throws Exception {
    // Story 3g-4 (AC2) — two token-bearing terminal executions roll up to their non-null sum on
    // the detail; a third with null tokens contributes nothing (never a 0).
    long happyId = runDbId(HAPPY_RUN);
    OffsetDateTime base = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(5);
    insertRunnerExecution(
        "rex_read_tok0001", happyId, "execution", "completed", base, 100, 200, 300);
    insertRunnerExecution(
        "rex_read_tok0002", happyId, "review", "completed", base.plusMinutes(1), 3, 8, 11);
    insertRunnerExecution(
        "rex_read_tok0003", happyId, "execution", "running", base.plusMinutes(2), null, null, null);

    HttpResponse<String> response = get("/api/v1/workflows/" + HAPPY_RUN, null);
    assertThat(response.statusCode()).isEqualTo(200);
    JsonNode body = mapper.readTree(response.body());
    assertThat(body.get("totalTokens").asInt()).isEqualTo(311);
    // Logging contract — getStatus success line reports token presence (non-secret boolean).
    assertThat(output.getOut()).contains("totalTokensPresent=true");
  }

  @Test
  void detailTotalTokensNullWhenNoStepReportedTokens() throws Exception {
    // A run whose only execution reported no tokens → totalTokens is JSON null (never 0/absent).
    long otherId = runDbId(OTHER_RUN);
    insertRunnerExecution(
        "rex_read_tok0004",
        otherId,
        "execution",
        "completed",
        OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(5),
        null,
        null,
        null);

    HttpResponse<String> response = get("/api/v1/workflows/" + OTHER_RUN, null);
    assertThat(response.statusCode()).isEqualTo(200);
    JsonNode body = mapper.readTree(response.body());
    assertThat(body.has("totalTokens")).isTrue();
    assertThat(body.get("totalTokens").isNull())
        .as("null when no step reported tokens (never 0)")
        .isTrue();
  }

  @Test
  void detailCiFieldsRenderNullForNeverPushedRun() throws Exception {
    // Story 3h-5 (AC3) — the CI detail fields are present; a never-pushed/never-polled run renders
    // ciStatus/ciHeadSha as JSON null (not absent, not an error) and ciFixLoopCount 0.
    HttpResponse<String> response = get("/api/v1/workflows/" + OTHER_RUN, null);
    assertThat(response.statusCode()).isEqualTo(200);
    JsonNode body = mapper.readTree(response.body());
    assertThat(body.has("ciStatus")).isTrue();
    assertThat(body.get("ciStatus").isNull()).as("ciStatus null for a never-pushed run").isTrue();
    assertThat(body.has("ciHeadSha")).isTrue();
    assertThat(body.get("ciHeadSha").isNull()).isTrue();
    assertThat(body.get("ciFixLoopCount").asInt()).isZero();
    assertThat(body.has("ciChecksEnforced")).isTrue();
    assertThat(body.get("ciChecksEnforced").isBoolean()).isTrue();
  }

  @Test
  void stepsReturnsPerExecutionTokensOldestFirst(CapturedOutput output) throws Exception {
    // Story 3g-4 (AC1) — /steps returns each runner execution oldest-first with its nullable token
    // counts. Seed out of createdAt order to prove the service sorts.
    long happyId = runDbId(HAPPY_RUN);
    OffsetDateTime base = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(5);
    insertRunnerExecution(
        "rex_step_newer1", happyId, "review", "completed", base.plusMinutes(2), 5, 6, 11);
    insertRunnerExecution(
        "rex_step_older1", happyId, "execution", "completed", base, 100, 200, 300);
    insertRunnerExecution(
        "rex_step_noreps", happyId, "execution", "running", base.plusMinutes(1), null, null, null);

    HttpResponse<String> response = get("/api/v1/workflows/" + HAPPY_RUN + "/steps", null);
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(contentType(response)).contains("application/json");

    JsonNode body = mapper.readTree(response.body());
    assertThat(body.isArray()).as("/steps is a direct JSON array").isTrue();
    assertThat(body).hasSize(3);

    // Oldest-first ordering.
    assertThat(body.get(0).get("runnerExecutionId").asText()).isEqualTo("rex_step_older1");
    assertThat(body.get(1).get("runnerExecutionId").asText()).isEqualTo("rex_step_noreps");
    assertThat(body.get(2).get("runnerExecutionId").asText()).isEqualTo("rex_step_newer1");

    JsonNode oldest = body.get(0);
    assertThat(oldest.fieldNames())
        .toIterable()
        .containsExactlyInAnyOrder(
            "runnerExecutionId",
            "stage",
            "status",
            "createdAt",
            "inputTokens",
            "outputTokens",
            "totalTokens");
    assertThat(oldest.get("stage").asText()).isEqualTo("execution");
    assertThat(oldest.get("status").asText()).isEqualTo("completed");
    assertThat(oldest.get("inputTokens").asInt()).isEqualTo(100);
    assertThat(oldest.get("outputTokens").asInt()).isEqualTo(200);
    assertThat(oldest.get("totalTokens").asInt()).isEqualTo(300);
    assertThat(oldest.get("createdAt").asText()).endsWith("Z");

    // The no-report step surfaces null token counts (never 0).
    JsonNode noReport = body.get(1);
    assertThat(noReport.get("inputTokens").isNull()).isTrue();
    assertThat(noReport.get("outputTokens").isNull()).isTrue();
    assertThat(noReport.get("totalTokens").isNull()).isTrue();

    // Logging contract — the /steps success line carries the result count.
    assertThat(output.getOut()).contains("REST list step-executions success");
    assertThat(output.getOut()).contains("count=3");
  }

  @Test
  void stepsUnknownRunYieldsRunNotFound() throws Exception {
    HttpResponse<String> response = get("/api/v1/workflows/run_doesnotexist999/steps", null);
    assertThat(response.statusCode()).isEqualTo(404);
    JsonNode body = mapper.readTree(response.body());
    assertThat(body.get("code").asText()).isEqualTo("RUN_NOT_FOUND");
  }

  @Test
  void malformedIdYieldsInvalidIdPrefixProblem() throws Exception {
    HttpResponse<String> response = get("/api/v1/workflows/not_a_run_id", null);
    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(contentType(response)).contains("application/problem+json");
    JsonNode body = mapper.readTree(response.body());
    assertThat(body.get("code").asText()).isEqualTo("INVALID_ID_PREFIX");
  }

  @Test
  void unknownRunYieldsRunNotFoundProblemWithCorrelationId() throws Exception {
    String correlationId = UUID.randomUUID().toString();
    HttpResponse<String> response =
        get("/api/v1/workflows/run_doesnotexist999/events", correlationId);

    assertThat(response.statusCode()).isEqualTo(404);
    assertThat(contentType(response)).contains("application/problem+json");
    JsonNode body = mapper.readTree(response.body());
    assertThat(body.get("code").asText()).isEqualTo("RUN_NOT_FOUND");

    // AC10 — correlation id echoed on the response header AND surfaced in the problem body.
    assertThat(response.headers().firstValue("X-Correlation-Id")).hasValue(correlationId);
    assertThat(body.get("correlationId").asText()).isEqualTo(correlationId);
    // RFC 9457 instance stays the request path (story 1.19 contract, preserved by 6.9).
    assertThat(body.get("instance").asText())
        .isEqualTo("/api/v1/workflows/run_doesnotexist999/events");
  }

  @Test
  void successResponsesEchoCorrelationIdHeader() throws Exception {
    String correlationId = UUID.randomUUID().toString();
    HttpResponse<String> response = get("/api/v1/workflows/" + HAPPY_RUN, correlationId);
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.headers().firstValue("X-Correlation-Id")).hasValue(correlationId);
  }

  @Test
  void restAndManagementSurfacesAreReachableOnLoopback() throws Exception {
    // AC5 — the embedded server binds the committed loopback default (server.address=127.0.0.1)
    // and all of these requests are issued to 127.0.0.1 explicitly, so a 200 proves the REST API,
    // springdoc, and actuator all answer on loopback.
    assertThat(get("/api/v1/workflows", null).statusCode()).isEqualTo(200);
    assertThat(get("/v3/api-docs", null).statusCode()).isEqualTo(200);
    assertThat(get("/actuator/health", null).statusCode()).isEqualTo(200);
  }

  // --- helpers --------------------------------------------------------------

  private HttpResponse<String> get(String path, String correlationId)
      throws IOException, InterruptedException {
    int port = environment.getRequiredProperty("local.server.port", Integer.class);
    HttpRequest.Builder builder =
        HttpRequest.newBuilder().uri(URI.create("http://127.0.0.1:" + port + path)).GET();
    if (correlationId != null) {
      builder.header("X-Correlation-Id", correlationId);
    }
    return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  private static String contentType(HttpResponse<String> response) {
    return response.headers().firstValue("content-type").orElse("");
  }

  private long insertRun(String publicId, String state) {
    jdbcTemplate.update(
        "insert into workflow_runs (public_id, current_state) values (?, ?)", publicId, state);
    Long id =
        jdbcTemplate.queryForObject(
            "select id from workflow_runs where public_id = ?", Long.class, publicId);
    if (id == null) {
      throw new AssertionError("failed to insert workflow_run " + publicId);
    }
    return id;
  }

  private long runDbId(String publicId) {
    Long id =
        jdbcTemplate.queryForObject(
            "select id from workflow_runs where public_id = ?", Long.class, publicId);
    if (id == null) {
      throw new AssertionError("no workflow_run " + publicId);
    }
    return id;
  }

  /**
   * Seeds a runner_executions row with 3g-3's token columns. A terminal status stamps completed_at
   * (the {@code ck_runner_executions_completed_correlation} biconditional); a non-terminal status
   * leaves it null. timeout_at is pinned {@code >= last_activity_at} ({@code
   * ck_runner_executions_timeout_after_activity}). Tokens are nullable and passed through verbatim.
   */
  private void insertRunnerExecution(
      String publicId,
      long runId,
      String stage,
      String status,
      OffsetDateTime createdAt,
      Integer inputTokens,
      Integer outputTokens,
      Integer totalTokens) {
    boolean terminal =
        List.of("completed", "failed", "timed_out", "orphaned", "cancelled_for_takeover")
            .contains(status);
    jdbcTemplate.update(
        """
        insert into runner_executions
          (public_id, workflow_run_id, stage, status, context_bundle_version,
           last_activity_at, timeout_at, completed_at, created_at,
           input_tokens, output_tokens, total_tokens)
        values (?, ?, ?, ?, 1, ?, ?, ?, ?, ?, ?, ?)
        """,
        publicId,
        runId,
        stage,
        status,
        createdAt,
        createdAt.plusMinutes(30),
        terminal ? createdAt : null,
        createdAt,
        inputTokens,
        outputTokens,
        totalTokens);
  }

  private void insertIntegrationLink(String publicId, long runId, String externalRef) {
    jdbcTemplate.update(
        """
        insert into integration_links
          (public_id, workflow_run_id, integration_type, external_ref, external_metadata,
           sync_status, created_at)
        values (?, ?, 'linear', ?, '{}'::jsonb, 'linked', ?)
        """,
        publicId,
        runId,
        externalRef,
        OffsetDateTime.now(ZoneOffset.UTC));
  }

  private void insertEvent(
      long runId,
      String publicId,
      String eventType,
      String priorState,
      String resultingState,
      String actorIdentity,
      String actorType,
      String reason,
      boolean interventionMarker,
      OffsetDateTime createdAt,
      Map<String, Object> details) {
    jdbcTemplate.update(
        """
        insert into workflow_events
          (public_id, workflow_run_id, event_type, prior_state, resulting_state,
           actor_identity, actor_type, reason, intervention_marker, details, created_at)
        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
        """,
        publicId,
        runId,
        eventType,
        priorState,
        resultingState,
        actorIdentity,
        actorType,
        reason,
        interventionMarker,
        toJson(details),
        createdAt);
  }

  private String toJson(Map<String, Object> details) {
    try {
      return mapper.writeValueAsString(details);
    } catch (JsonProcessingException e) {
      throw new AssertionError("failed to serialize event details fixture", e);
    }
  }
}
