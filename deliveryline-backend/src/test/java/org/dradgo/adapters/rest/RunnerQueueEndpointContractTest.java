package org.dradgo.adapters.rest;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.dradgo.TestcontainersConfiguration;
import org.dradgo.domain.id.PublicIdPrefixes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Story 3.19 (AC4/AC9) — REST contract for {@code GET /api/v1/runner-queue/status}. House pattern:
 * {@code @SpringBootTest} RANDOM_PORT + a real {@link HttpClient} (NOT MockMvc). Asserts the 200 +
 * camelCase body shape, the {@code ?batchId} scoping over seeded rows, and a malformed batchId →
 * 400 {@code INVALID_ID_PREFIX}.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"test", "linear-mock"})
class RunnerQueueEndpointContractTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Autowired private Environment environment;
  @Autowired private JdbcTemplate jdbcTemplate;

  private String seededBatchId;

  @AfterEach
  void tearDown() {
    if (seededBatchId != null) {
      jdbcTemplate.update(
          "delete from runner_executions where batch_submission_id = ?", seededBatchId);
      seededBatchId = null;
    }
  }

  @Test
  void statusReturns200WithDocumentedCamelCaseShape() throws Exception {
    HttpResponse<String> response = get("/api/v1/runner-queue/status");

    assertThat(response.statusCode()).isEqualTo(200);
    JsonNode body = MAPPER.readTree(response.body());
    // The full documented field set is present (types, not exact global counts — shared DB).
    assertThat(body.has("poolSize")).isTrue();
    assertThat(body.path("poolSize").asInt()).isGreaterThanOrEqualTo(1);
    assertThat(body.has("activeWorkers")).isTrue();
    assertThat(body.has("idleWorkers")).isTrue();
    assertThat(body.has("queueDepth")).isTrue();
    assertThat(body.has("oldestQueuedAgeSeconds")).isTrue();
    assertThat(body.has("inFlightExecutions")).isTrue();
    assertThat(body.has("recentThroughputPerMinute")).isTrue();
    assertThat(body.has("staleQueuedCount")).isTrue();
    assertThat(body.has("staleDispatchedCount")).isTrue();
    assertThat(body.path("workers").isArray()).isTrue();
  }

  @Test
  void batchIdScopesTheCountsToThatBatch() throws Exception {
    seededBatchId = "bat_rest" + uniqueSuffix();
    String runId = seedRun();
    seedQueued(runId, "rex_rest1" + uniqueSuffix().substring(0, 6), seededBatchId);
    seedQueued(runId, "rex_rest2" + uniqueSuffix().substring(0, 6), seededBatchId);

    HttpResponse<String> response = get("/api/v1/runner-queue/status?batchId=" + seededBatchId);

    assertThat(response.statusCode()).isEqualTo(200);
    JsonNode body = MAPPER.readTree(response.body());
    assertThat(body.path("queueDepth").asLong()).isEqualTo(2L);
    assertThat(body.path("poolSize").asInt()).isGreaterThanOrEqualTo(1); // global, not batch-scoped
  }

  @Test
  void malformedBatchIdReturns400InvalidIdPrefix() throws Exception {
    HttpResponse<String> response = get("/api/v1/runner-queue/status?batchId=not-a-batch");

    assertThat(response.statusCode()).isEqualTo(400);
    JsonNode body = MAPPER.readTree(response.body());
    assertThat(body.path("code").asText()).isEqualTo("INVALID_ID_PREFIX");
  }

  private String seedRun() {
    String runId = PublicIdPrefixes.WORKFLOW_RUN.next();
    jdbcTemplate.update(
        "insert into workflow_runs (public_id, current_state) values (?, 'Inbox')", runId);
    return runId;
  }

  private void seedQueued(String runId, String publicId, String batchId) {
    jdbcTemplate.update(
        """
        insert into runner_executions
          (public_id, workflow_run_id, stage, status, context_bundle_version,
           last_activity_at, timeout_at, queue_priority, queue_attempt_count, created_at,
           batch_submission_id)
        values (?, (select id from workflow_runs where public_id = ?),
                'investigation', 'queued', 1, now(), now() + interval '10 minutes', 100, 0, now(),
                ?)
        """,
        publicId,
        runId,
        batchId);
  }

  private HttpResponse<String> get(String path) throws IOException, InterruptedException {
    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create(baseUrl() + path))
            .header("Accept", "application/json")
            .GET()
            .build();
    return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
  }

  private String baseUrl() {
    return "http://127.0.0.1:" + environment.getRequiredProperty("local.server.port");
  }

  private static String uniqueSuffix() {
    return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
  }
}
