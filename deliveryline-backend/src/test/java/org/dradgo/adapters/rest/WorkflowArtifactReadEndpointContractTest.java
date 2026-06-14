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
 * Story 3a-9 (Gate 3, AC3/AC4/AC5/AC7/AC8) contract test for the artifact-read endpoint {@code GET
 * /api/v1/workflows/{workflowRunId}/artifacts/{artifactId}}. One boot exercises:
 *
 * <ul>
 *   <li>AC3 — happy 200 returns the typed DTO with the REDACTED body as a UTF-8 markdown string
 *       (not base64) + short-form checksum;
 *   <li>AC4 — cross-run guard (404 ARTIFACT_RECORD_NOT_FOUND), missing run (404 RUN_NOT_FOUND),
 *       missing artifact (404 ARTIFACT_RECORD_NOT_FOUND), malformed id (400 INVALID_ID_PREFIX);
 *   <li>AC5 — classification guard (a local-only artifact is never served → 404) + adversarial: the
 *       served body carries no Linear/GitHub token, absolute host path, or .env content;
 *   <li>Task 6 — a successful read emits the expected INFO log line.
 * </ul>
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"test", "linear-mock"})
@Tag("contract")
@ExtendWith(OutputCaptureExtension.class)
class WorkflowArtifactReadEndpointContractTest {

  private static final String HAPPY_RUN = "run_artread_happy01";
  private static final String OTHER_RUN = "run_artread_other02";
  private static final String SPEC_ARTIFACT = "art_artread_spec01";
  private static final String OTHER_ARTIFACT = "art_artread_other02";
  private static final String LOCAL_ONLY_ARTIFACT = "art_artread_local03";

  // A realistic ALREADY-REDACTED spec body (redaction happens at write time per 1.10/2.24); the
  // endpoint serves these bytes verbatim, so the redaction markers prove no raw secret is served.
  private static final String REDACTED_BODY =
      "# Specification — FIN-18\n\n"
          + "Connect using token [REDACTED] and the configured endpoint.\n"
          + "Workspace path: [REDACTED]\n";

  @Autowired private Environment environment;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private ArtifactPayloadStore artifactPayloadStore;

  private final ObjectMapper mapper = new ObjectMapper();
  private final HttpClient http = HttpClient.newHttpClient();

  @BeforeEach
  void seed() {
    wipe();
    long happy = insertRun(HAPPY_RUN, "WaitingForSpecApproval");
    long other = insertRun(OTHER_RUN, "WaitingForSpecApproval");
    // Distinct versions per run to satisfy uq_artifacts(workflow_run_id, artifact_type, version).
    seedArtifact(HAPPY_RUN, happy, SPEC_ARTIFACT, 1, "shareable-redacted", REDACTED_BODY);
    seedArtifact(OTHER_RUN, other, OTHER_ARTIFACT, 1, "shareable-redacted", "# Other run spec\n");
    seedArtifact(HAPPY_RUN, happy, LOCAL_ONLY_ARTIFACT, 2, "local-only", "# Local only\n");
  }

  @AfterEach
  void cleanUp() {
    wipe();
  }

  @Test
  void happyPathReturnsRedactedBodyAndMetadata(CapturedOutput output) throws Exception {
    HttpResponse<String> response =
        get("/api/v1/workflows/" + HAPPY_RUN + "/artifacts/" + SPEC_ARTIFACT, null);

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(contentType(response)).contains("application/json");

    JsonNode body = mapper.readTree(response.body());
    assertThat(body.get("artifactId").asText()).isEqualTo(SPEC_ARTIFACT);
    assertThat(body.get("artifactType").asText()).isEqualTo("spec");
    assertThat(body.get("version").asInt()).isEqualTo(1);
    assertThat(body.get("status").asText()).isEqualTo("available");
    assertThat(body.get("classification").asText()).isEqualTo("shareable-redacted");
    assertThat(body.has("createdAt")).isTrue();
    // Short-form checksum: <algorithm>:<first 12 hex> — never the full digest.
    assertThat(body.get("checksum").asText()).startsWith("SHA-256:");
    assertThat(body.get("checksum").asText().length()).isLessThan("SHA-256:".length() + 13);
    // Body is a UTF-8 markdown STRING (not base64) and equals the persisted redacted bytes.
    assertThat(body.get("body").asText()).isEqualTo(REDACTED_BODY);

    // Task 6 — successful read logs the INFO success line.
    assertThat(output.getOut()).contains("REST get artifact success");
  }

  @Test
  void servedBodyCarriesNoRawSecretOrHostPath() throws Exception {
    HttpResponse<String> response =
        get("/api/v1/workflows/" + HAPPY_RUN + "/artifacts/" + SPEC_ARTIFACT, null);
    assertThat(response.statusCode()).isEqualTo(200);
    String served = mapper.readTree(response.body()).get("body").asText();

    // AC5 adversarial — the redacted payload contains the placeholder, never raw secrets/paths.
    assertThat(served).contains("[REDACTED]");
    assertThat(served).doesNotContainPattern("ghp_[A-Za-z0-9]{20,}");
    assertThat(served).doesNotContainPattern("lin_api_[A-Za-z0-9]{20,}");
    assertThat(served).doesNotContain(".env");
    assertThat(served).doesNotContainPattern("(?m)^/(home|Users|var|etc)/");
  }

  @Test
  void crossRunArtifactIsNotFound() throws Exception {
    // OTHER_ARTIFACT belongs to OTHER_RUN; requesting it under HAPPY_RUN must 404 (no leak).
    HttpResponse<String> response =
        get("/api/v1/workflows/" + HAPPY_RUN + "/artifacts/" + OTHER_ARTIFACT, null);
    assertThat(response.statusCode()).isEqualTo(404);
    assertThat(contentType(response)).contains("application/problem+json");
    assertThat(mapper.readTree(response.body()).get("code").asText())
        .isEqualTo("ARTIFACT_RECORD_NOT_FOUND");
  }

  @Test
  void localOnlyArtifactIsNotServed() throws Exception {
    HttpResponse<String> response =
        get("/api/v1/workflows/" + HAPPY_RUN + "/artifacts/" + LOCAL_ONLY_ARTIFACT, null);
    assertThat(response.statusCode()).isEqualTo(404);
    assertThat(mapper.readTree(response.body()).get("code").asText())
        .isEqualTo("ARTIFACT_RECORD_NOT_FOUND");
  }

  @Test
  void missingRunYieldsRunNotFound() throws Exception {
    String correlationId = UUID.randomUUID().toString();
    HttpResponse<String> response =
        get("/api/v1/workflows/run_artread_missing9/artifacts/" + SPEC_ARTIFACT, correlationId);
    assertThat(response.statusCode()).isEqualTo(404);
    JsonNode body = mapper.readTree(response.body());
    assertThat(body.get("code").asText()).isEqualTo("RUN_NOT_FOUND");
    assertThat(response.headers().firstValue("X-Correlation-Id")).hasValue(correlationId);
  }

  @Test
  void missingArtifactYieldsArtifactRecordNotFound() throws Exception {
    HttpResponse<String> response =
        get("/api/v1/workflows/" + HAPPY_RUN + "/artifacts/art_artread_absent99", null);
    assertThat(response.statusCode()).isEqualTo(404);
    assertThat(mapper.readTree(response.body()).get("code").asText())
        .isEqualTo("ARTIFACT_RECORD_NOT_FOUND");
  }

  @Test
  void malformedArtifactIdYieldsInvalidIdPrefix() throws Exception {
    HttpResponse<String> response =
        get("/api/v1/workflows/" + HAPPY_RUN + "/artifacts/not_an_artifact", null);
    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(contentType(response)).contains("application/problem+json");
    assertThat(mapper.readTree(response.body()).get("code").asText())
        .isEqualTo("INVALID_ID_PREFIX");
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

  private void seedArtifact(
      String runPublicId,
      long runId,
      String artifactPublicId,
      int version,
      String classification,
      String body) {
    byte[] payload = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    String storageRef =
        artifactPayloadStore.write(runPublicId, artifactPublicId, version, "spec.md", payload);
    String checksum = sha256Hex(payload);
    String evtPublicId = "evt_artread" + System.nanoTime();
    Long linkedEventId =
        jdbcTemplate.queryForObject(
            "insert into workflow_events (public_id, workflow_run_id, event_type, actor_identity,"
                + " actor_type) values (?, ?, 'artifact.draftCreated', 'seed', 'system') returning id",
            Long.class,
            evtPublicId,
            runId);
    jdbcTemplate.update(
        "insert into artifacts (public_id, workflow_run_id, artifact_type, version, parent_artifact_id, "
            + "classification, status, storage_ref, checksum_algorithm, checksum_value, linked_event_id) "
            + "values (?, ?, 'spec', ?, null, ?, 'available', ?, 'SHA-256', ?, ?)",
        artifactPublicId,
        runId,
        version,
        classification,
        storageRef,
        checksum,
        linkedEventId);
  }

  private void wipe() {
    jdbcTemplate.update("truncate table workflow_runs restart identity cascade");
  }

  private static String sha256Hex(byte[] payload) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
    } catch (NoSuchAlgorithmException e) {
      throw new AssertionError("SHA-256 must be available", e);
    }
  }
}
