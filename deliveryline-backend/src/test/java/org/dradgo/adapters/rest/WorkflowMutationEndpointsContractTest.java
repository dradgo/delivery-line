package org.dradgo.adapters.rest;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import org.dradgo.TestcontainersConfiguration;
import org.dradgo.application.artifact.spi.ArtifactPayloadStore;
import org.dradgo.domain.registry.WorkflowState;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"test", "linear-mock"})
class WorkflowMutationEndpointsContractTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Autowired private Environment environment;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private ArtifactPayloadStore artifactPayloadStore;

  @Test
  void approveSpecPersistsFallbackActorAndEchoesCorrelationId() throws Exception {
    String suffix = uniqueSuffix();
    String runId = insertRun("run_appr_mut_" + suffix, WorkflowState.WAITING_FOR_SPEC_APPROVAL);
    String artifactId = "art_appr_mut_" + suffix;
    seedAvailableSpecArtifact(runId, artifactId);
    String correlationId = UUID.randomUUID().toString();

    HttpResponse<String> response =
        post(
            "/api/v1/workflows/" + runId + "/approve-spec",
            """
            {
              "artifactId": "%s",
              "expectedArtifactVersion": 1,
              "expectedContextBundleVersion": 1
            }
            """
                .formatted(artifactId),
            "idem-approve-mut-" + suffix,
            null,
            correlationId);

    assertThat(response.statusCode()).isEqualTo(200);
    JsonNode body = MAPPER.readTree(response.body());
    assertThat(response.headers().firstValue("X-Correlation-Id")).hasValue(correlationId);
    assertThat(body.path("correlationId").asText()).isEqualTo(correlationId);
    assertThat(body.path("currentState").asText()).isEqualTo("Executing");
    assertThat(latestApprovalActorIdentity(runId)).isEqualTo("local-operator");
  }

  @Test
  void rejectSpecPersistsFallbackActorAndGeneratesCorrelationIdWhenMissing() throws Exception {
    String suffix = uniqueSuffix();
    String runId = insertRun("run_rej_mut_" + suffix, WorkflowState.WAITING_FOR_SPEC_APPROVAL);
    String artifactId = "art_rej_mut_" + suffix;
    seedAvailableSpecArtifact(runId, artifactId);

    HttpResponse<String> response =
        post(
            "/api/v1/workflows/" + runId + "/reject-spec",
            """
            {
              "artifactId": "%s",
              "expectedArtifactVersion": 1,
              "expectedContextBundleVersion": 1,
              "taggedFeedback": "MISSING_SCOPE",
              "reasonText": "Spec still needs a scope section."
            }
            """
                .formatted(artifactId),
            "idem-reject-mut-" + suffix,
            null,
            null);

    assertThat(response.statusCode()).isEqualTo(200);
    JsonNode body = MAPPER.readTree(response.body());
    String generatedCorrelationId = response.headers().firstValue("X-Correlation-Id").orElseThrow();
    assertThat(UUID.fromString(generatedCorrelationId).version()).isEqualTo(7);
    assertThat(body.path("correlationId").asText()).isEqualTo(generatedCorrelationId);
    assertThat(body.path("currentState").asText()).isEqualTo("Investigating");
    assertThat(latestApprovalActorIdentity(runId)).isEqualTo("local-operator");
  }

  @Test
  void answerClarificationReplayReturnsIdenticalBodyWithoutDuplicateWrites() throws Exception {
    String suffix = uniqueSuffix();
    String runId = insertRun("run_ans_mut_" + suffix, WorkflowState.WAITING_FOR_SPEC_APPROVAL);
    String artifactId = "art_ans_mut_" + suffix;
    String clarificationId = "clr_ans_mut_" + suffix;
    seedAvailableSpecArtifact(runId, artifactId);
    seedOpenClarification(runId, artifactId, clarificationId);
    String correlationId = UUID.randomUUID().toString();
    String idempotencyKey = "idem-answer-mut-" + suffix;

    String body =
        """
        {
          "artifactId": "%s",
          "expectedArtifactVersion": 1,
          "answerText": "Confirmed: the workflow blocks until the PM clarifies the boundary."
        }
        """
            .formatted(artifactId);

    HttpResponse<String> first =
        post(
            "/api/v1/workflows/" + runId + "/clarifications/" + clarificationId + "/answer",
            body,
            idempotencyKey,
            null,
            correlationId);
    HttpResponse<String> second =
        post(
            "/api/v1/workflows/" + runId + "/clarifications/" + clarificationId + "/answer",
            body,
            idempotencyKey,
            null,
            correlationId);

    assertThat(first.statusCode()).isEqualTo(200);
    assertThat(second.statusCode()).isEqualTo(200);
    assertThat(first.body()).isEqualTo(second.body());
    assertThat(first.headers().firstValue("X-Correlation-Id")).hasValue(correlationId);
    assertThat(second.headers().firstValue("X-Correlation-Id")).hasValue(correlationId);

    JsonNode responseBody = MAPPER.readTree(first.body());
    assertThat(responseBody.path("clarificationStatus").asText()).isEqualTo("answered");
    assertThat(responseBody.path("currentState").asText()).isEqualTo("WaitingForSpecApproval");
    assertThat(responseBody.path("correlationId").asText()).isEqualTo(correlationId);
    assertThat(answeredByActor(clarificationId)).isEqualTo("local-operator");
    assertThat(countWorkflowEvents(runId, "clarification.answered")).isEqualTo(1);
    assertThat(countIdempotencyRecords(idempotencyKey)).isEqualTo(1);
  }

  @Test
  void mutationProblemDetailsEchoCorrelationIdOnError() throws Exception {
    String suffix = uniqueSuffix();
    String correlationId = UUID.randomUUID().toString();
    String runId = "run_missing_mut_" + suffix;

    HttpResponse<String> response =
        post(
            "/api/v1/workflows/" + runId + "/approve-spec",
            """
            {
              "artifactId": "art_missing_mut_%s",
              "expectedArtifactVersion": 1,
              "expectedContextBundleVersion": 1
            }
            """
                .formatted(suffix),
            "idem-approve-missing-" + suffix,
            "alex",
            correlationId);

    assertThat(response.statusCode()).isEqualTo(404);
    assertThat(response.headers().firstValue("X-Correlation-Id")).hasValue(correlationId);
    assertThat(response.headers().firstValue("Content-Type").orElse(""))
        .contains("application/problem+json");
    JsonNode body = MAPPER.readTree(response.body());
    assertThat(body.path("code").asText()).isEqualTo("ARTIFACT_RECORD_NOT_FOUND");
    assertThat(body.path("correlationId").asText()).isEqualTo(correlationId);
  }

  private HttpResponse<String> post(
      String path, String body, String idempotencyKey, String actorIdentity, String correlationId)
      throws IOException, InterruptedException {
    int port = environment.getRequiredProperty("local.server.port", Integer.class);
    HttpRequest.Builder builder =
        HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + port + path))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .header("Idempotency-Key", idempotencyKey)
            .POST(HttpRequest.BodyPublishers.ofString(body));
    if (actorIdentity != null) {
      builder.header("X-Actor-Identity", actorIdentity);
    }
    if (correlationId != null) {
      builder.header("X-Correlation-Id", correlationId);
    }
    return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  private String insertRun(String publicId, WorkflowState state) {
    jdbcTemplate.update(
        "insert into workflow_runs (public_id, current_state) values (?, ?)",
        publicId,
        state.value());
    return publicId;
  }

  private void seedAvailableSpecArtifact(String runPublicId, String artifactPublicId) {
    Long runId =
        jdbcTemplate.queryForObject(
            "select id from workflow_runs where public_id = ?", Long.class, runPublicId);

    byte[] payload =
        ("approval-eligible content for " + artifactPublicId)
            .getBytes(java.nio.charset.StandardCharsets.UTF_8);
    String storageRef =
        artifactPayloadStore.write(runPublicId, artifactPublicId, 1, "spec.md", payload);
    String checksum = sha256Hex(payload);

    String eventPublicId = "evt_seed_" + uniqueSuffix();
    Long linkedEventId =
        jdbcTemplate.queryForObject(
            "insert into workflow_events (public_id, workflow_run_id, event_type, actor_identity, actor_type) "
                + "values (?, ?, 'artifact.draftCreated', 'seed', 'system') returning id",
            Long.class,
            eventPublicId,
            runId);

    jdbcTemplate.update(
        "insert into artifacts (public_id, workflow_run_id, artifact_type, version, parent_artifact_id, "
            + "classification, status, storage_ref, checksum_algorithm, checksum_value, linked_event_id) "
            + "values (?, ?, 'spec', 1, null, 'shareable-redacted', 'available', ?, 'SHA-256', ?, ?)",
        artifactPublicId,
        runId,
        storageRef,
        checksum,
        linkedEventId);
  }

  private void seedOpenClarification(
      String runPublicId, String artifactPublicId, String clarificationPublicId) {
    Long runId =
        jdbcTemplate.queryForObject(
            "select id from workflow_runs where public_id = ?", Long.class, runPublicId);
    Long artifactId =
        jdbcTemplate.queryForObject(
            "select id from artifacts where public_id = ?", Long.class, artifactPublicId);
    jdbcTemplate.update(
        "insert into clarifications (public_id, workflow_run_id, artifact_id, artifact_version, "
            + "question_id, question_text, status, idempotency_key) "
            + "values (?, ?, ?, 1, 'Q1', 'What is the boundary?', 'open', ?)",
        clarificationPublicId,
        runId,
        artifactId,
        "seed-" + clarificationPublicId);
  }

  private String latestApprovalActorIdentity(String runPublicId) {
    return jdbcTemplate.queryForObject(
        """
        select actor_identity
        from approvals
        where workflow_run_id = (select id from workflow_runs where public_id = ?)
        order by id desc
        limit 1
        """,
        String.class,
        runPublicId);
  }

  private String answeredByActor(String clarificationPublicId) {
    return jdbcTemplate.queryForObject(
        "select answered_by_actor from clarifications where public_id = ?",
        String.class,
        clarificationPublicId);
  }

  private int countWorkflowEvents(String runPublicId, String eventType) {
    return jdbcTemplate.queryForObject(
        """
        select count(*)
        from workflow_events
        where workflow_run_id = (select id from workflow_runs where public_id = ?)
          and event_type = ?
        """,
        Integer.class,
        runPublicId,
        eventType);
  }

  private int countIdempotencyRecords(String idempotencyKey) {
    return jdbcTemplate.queryForObject(
        "select count(*) from idempotency_records where key = ?", Integer.class, idempotencyKey);
  }

  // Story 2.13 round-4 P-R4-9: monotonic counter instead of System.nanoTime() so two fast calls
  // within the same JVM-tick cannot collide and cause primary-key violations on seed inserts.
  private static final java.util.concurrent.atomic.AtomicLong SUFFIX_COUNTER =
      new java.util.concurrent.atomic.AtomicLong();

  private static String uniqueSuffix() {
    return Long.toUnsignedString(SUFFIX_COUNTER.incrementAndGet(), 36);
  }

  private static String sha256Hex(byte[] payload) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(payload));
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException("SHA-256 must be available", error);
    }
  }
}
