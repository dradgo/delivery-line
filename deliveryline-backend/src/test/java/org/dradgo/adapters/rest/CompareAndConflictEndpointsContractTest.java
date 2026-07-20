package org.dradgo.adapters.rest;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.dradgo.TestcontainersConfiguration;
import org.dradgo.application.artifact.ArtifactChecksum;
import org.dradgo.application.artifact.spi.ArtifactPayloadStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Story 4.26 (AC4, Task 7 / OQ-5) — full-context HTTP↔DB contract test for the Compare-Mode
 * revision delta ({@code GET /api/v1/artifacts/{a}/compare/{b}}) and the integration-conflict read
 * surface ({@code GET /api/v1/integration-conflicts} + {@code /{conflictId}}).
 *
 * <p>The audit (story 4.26) confirmed these two endpoints had ONLY {@code @WebMvcTest} slice
 * coverage ({@code ArtifactCompareControllerTest} / {@code IntegrationConflictControllerTest},
 * mocked services) plus service-layer Testcontainers ITs ({@code RevisionDeltaCompareIT} / {@code
 * IntegrationConflictListReadIT}, which invoke the services directly). Neither exercised the full
 * HTTP↔DB round trip — real Jackson serialization of the response DTOs over a booted server against
 * a real Postgres — the way the recovery-mutation endpoints do via {@code
 * WorkflowMutationEndpointsContractTest}. This closes that gap (OQ-5 = add).
 *
 * <p>It seeds real rows via {@link JdbcTemplate} + {@link ArtifactPayloadStore} (mirroring the
 * seeding in {@code RevisionDeltaCompareIT} / {@code IntegrationConflictListReadIT}), then drives
 * the real endpoints with a JDK {@link HttpClient} and asserts the serialized JSON + Problem
 * Details. Named {@code *ContractTest} so Failsafe runs it in the gate-reachable {@code
 * backend-contract-tests} tier (NOT {@code docker-runner-it}/{@code recovery-integration}). No
 * production code changes.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"test", "linear-mock"})
@Tag("contract")
class CompareAndConflictEndpointsContractTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Autowired private Environment environment;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private ArtifactPayloadStore payloadStore;

  private final List<String> seededRuns = new ArrayList<>();
  private final List<String> seededConflicts = new ArrayList<>();
  private final List<String> seededLinks = new ArrayList<>();
  private int seq;

  @AfterEach
  void cleanup() {
    // Per-context container gives a fresh schema per class, but rows persist across methods in the
    // class; delete the seeded rows so the list/count assertions never see cross-method bleed.
    for (String c : seededConflicts) {
      jdbcTemplate.update("delete from integration_conflicts where public_id = ?", c);
    }
    for (String l : seededLinks) {
      jdbcTemplate.update("delete from integration_links where public_id = ?", l);
    }
    for (String run : seededRuns) {
      jdbcTemplate.update(
          "delete from artifacts where workflow_run_id ="
              + " (select id from workflow_runs where public_id = ?)",
          run);
      jdbcTemplate.update(
          "delete from workflow_events where workflow_run_id ="
              + " (select id from workflow_runs where public_id = ?)",
          run);
      jdbcTemplate.update("delete from integration_conflicts where workflow_run_id = ?", run);
      jdbcTemplate.update(
          "delete from integration_links where workflow_run_id ="
              + " (select id from workflow_runs where public_id = ?)",
          run);
      jdbcTemplate.update("delete from workflow_runs where public_id = ?", run);
    }
    seededConflicts.clear();
    seededLinks.clear();
    seededRuns.clear();
  }

  // ---- Compare endpoint ---------------------------------------------------------------------

  @Test
  void compareReturnsDeltaOverRealLineageWithSerializedResponse() throws Exception {
    String run = seedRun();
    long v1 = seedSpecArtifact(run, 1, null, "# Overview\n\nOriginal requirement text.\n");
    seedSpecArtifact(
        run, 2, v1, "# Overview\n\nRewritten requirement text.\n\n# New\n\nAn added section.\n");

    HttpResponse<String> response =
        get(
            "/api/v1/artifacts/"
                + artifactPublicId(run, 1)
                + "/compare/"
                + artifactPublicId(run, 2));

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.headers().firstValue("Content-Type").orElse(""))
        .contains("application/json");
    JsonNode body = MAPPER.readTree(response.body());
    assertThat(body.path("artifactType").asText()).isEqualTo("spec");
    assertThat(body.path("revisionA").path("version").asInt()).isEqualTo(1);
    assertThat(body.path("revisionB").path("version").asInt()).isEqualTo(2);
    assertThat(body.path("noMeaningfulDiff").asBoolean()).isFalse();
    assertThat(body.path("summary").path("changedRegionCount").asInt()).isGreaterThan(0);
    assertThat(body.path("changes").isArray()).isTrue();
    assertThat(body.path("changes")).isNotEmpty();
  }

  @Test
  void compareRejectsCrossTypeArtifactsWith400() throws Exception {
    String run = seedRun();
    // Same run, DIFFERENT artifact types (spec vs implementationPlan) — exercises the
    // `different_run_or_type` branch of RevisionDeltaService.requireSameLineage.
    seedSpecArtifact(run, 1, null, "# A\n\nRoot one.\n");
    long otherId = seedSpecArtifactTyped(run, "implementationPlan", 1, null, "{\"steps\":[]}");

    HttpResponse<String> response =
        get(
            "/api/v1/artifacts/"
                + artifactPublicId(run, 1)
                + "/compare/"
                + artifactPublicIdTyped(run, "implementationPlan", 1));
    // Guard against an unused-variable warning while keeping the seeded id meaningful.
    assertThat(otherId).isPositive();

    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(response.headers().firstValue("Content-Type").orElse(""))
        .contains("application/problem+json");
    assertThat(MAPPER.readTree(response.body()).path("code").asText())
        .isEqualTo("ARTIFACT_LINEAGE_MISMATCH");
  }

  @Test
  void compareRejectsSameTypeDisjointLineageWith400() throws Exception {
    String run = seedRun();
    // Same run, SAME type (spec), but two independent parent-null roots (v2's parent is NOT v1) —
    // neither is an ancestor of the other. This exercises the `not_on_parent_chain` branch of
    // RevisionDeltaService.requireSameLineage (the parent-chain walk), which the cross-type case
    // above does NOT reach — it short-circuits on the run/type check first.
    seedSpecArtifact(run, 1, null, "# A\n\nRoot one.\n");
    seedSpecArtifact(run, 2, null, "# B\n\nA disjoint second root.\n");

    HttpResponse<String> response =
        get(
            "/api/v1/artifacts/"
                + artifactPublicId(run, 1)
                + "/compare/"
                + artifactPublicId(run, 2));

    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(response.headers().firstValue("Content-Type").orElse(""))
        .contains("application/problem+json");
    assertThat(MAPPER.readTree(response.body()).path("code").asText())
        .isEqualTo("ARTIFACT_LINEAGE_MISMATCH");
  }

  @Test
  void compareUnknownArtifactReturns404() throws Exception {
    String run = seedRun();
    seedSpecArtifact(run, 1, null, "# A\n\nRoot.\n");

    HttpResponse<String> response =
        get("/api/v1/artifacts/" + artifactPublicId(run, 1) + "/compare/art_does_not_exist_9999");

    assertThat(response.statusCode()).isEqualTo(404);
    assertThat(MAPPER.readTree(response.body()).path("code").asText())
        .isEqualTo("ARTIFACT_RECORD_NOT_FOUND");
  }

  // ---- Conflict endpoints -------------------------------------------------------------------

  @Test
  void conflictListReturnsSeededRowsAndTotals() throws Exception {
    String run = seedRun();
    String link = seedLink(run, "github_pr", "octo/repo#7");
    String c1 = seedConflict(run, link, "external_state_advanced", null, null, null);
    seedConflict(run, link, "metadata_drift", null, null, null);

    HttpResponse<String> response = get("/api/v1/integration-conflicts?workflowRunId=" + run);

    assertThat(response.statusCode()).isEqualTo(200);
    JsonNode body = MAPPER.readTree(response.body());
    assertThat(body.path("conflicts").isArray()).isTrue();
    List<String> ids = new ArrayList<>();
    body.path("conflicts").forEach(n -> ids.add(n.path("conflictId").asText()));
    assertThat(ids).contains(c1);
    // Exactly the two run-scoped seeds - an exact count catches an endpoint that
    // ignores the workflowRunId filter and returns a global unresolved total.
    assertThat(body.path("totalUnresolved").asLong()).isEqualTo(2);
    assertThat(ids).hasSize(2);
    // Each summary carries the neutral fields the operator queue reads, and every
    // returned row is scoped to the queried run (bidirectional filter proof).
    JsonNode first = body.path("conflicts").get(0);
    assertThat(first.path("conflictCategory").asText()).isNotBlank();
    body.path("conflicts")
        .forEach(n -> assertThat(n.path("workflowRunId").asText()).isEqualTo(run));
  }

  @Test
  void conflictDetailReturnsSnapshotsAndSuggestedDecisions() throws Exception {
    String run = seedRun();
    String link = seedLink(run, "github_pr", "octo/repo#8");
    String conflict =
        seedConflict(
            run,
            link,
            "external_state_advanced",
            "{\"state\":\"open\",\"branch\":\"feat/x\"}",
            "{\"state\":\"merged\",\"commitSha\":\"abc123\"}",
            null);

    HttpResponse<String> response = get("/api/v1/integration-conflicts/" + conflict);

    assertThat(response.statusCode()).isEqualTo(200);
    JsonNode body = MAPPER.readTree(response.body());
    assertThat(body.path("conflictId").asText()).isEqualTo(conflict);
    assertThat(body.path("workflowRunId").asText()).isEqualTo(run);
    assertThat(body.path("internalStateSnapshot").asText()).contains("feat/x");
    assertThat(body.path("externalStateSnapshot").asText()).contains("merged");
    // suggestedDecisions are derived from the category by ConflictReconciliationSuggester
    // (safe-first).
    assertThat(body.path("suggestedDecisions").isArray()).isTrue();
    assertThat(body.path("suggestedDecisions")).isNotEmpty();
    JsonNode firstSuggestion = body.path("suggestedDecisions").get(0);
    assertThat(firstSuggestion.path("decision").asText()).isNotBlank();
    assertThat(firstSuggestion.path("safety").asText()).isIn("safe", "risky");
  }

  @Test
  void conflictDetailUnknownReturns404() throws Exception {
    HttpResponse<String> response = get("/api/v1/integration-conflicts/icf_does_not_exist_9999");

    assertThat(response.statusCode()).isEqualTo(404);
    assertThat(MAPPER.readTree(response.body()).path("code").asText())
        .isEqualTo("CONFLICT_NOT_FOUND");
  }

  // ---- HTTP + seeding helpers ---------------------------------------------------------------

  private HttpResponse<String> get(String path) throws IOException, InterruptedException {
    int port = environment.getRequiredProperty("local.server.port", Integer.class);
    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + port + path))
            .header("Accept", "application/json")
            .GET()
            .build();
    return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
  }

  private String nextSfx() {
    // %08x left-pads to a fixed width, then take the low 4 hex digits - identity
    // hashes below 0x1000 produce a <4-char toHexString and would crash substring(0,4).
    String hex = String.format("%08x", System.identityHashCode(this));
    return "cce" + hex.substring(hex.length() - 4) + (seq++);
  }

  private String seedRun() {
    String run = "run_" + nextSfx();
    jdbcTemplate.update(
        "insert into workflow_runs (public_id, current_state) values (?, 'Executing')", run);
    seededRuns.add(run);
    return run;
  }

  private long runDbId(String run) {
    return jdbcTemplate.queryForObject(
        "select id from workflow_runs where public_id = ?", Long.class, run);
  }

  private String artifactPublicId(String run, int version) {
    return artifactPublicIdTyped(run, "spec", version);
  }

  private String artifactPublicIdTyped(String run, String type, int version) {
    return "art_" + run.substring(4) + type.substring(0, 2) + "v" + version;
  }

  private long seedSpecArtifact(String run, int version, Long parentDbId, String payload) {
    return seedSpecArtifactTyped(run, "spec", version, parentDbId, payload);
  }

  private long seedSpecArtifactTyped(
      String run, String type, int version, Long parentDbId, String payload) {
    long runId = runDbId(run);
    String artifactPublicId = artifactPublicIdTyped(run, type, version);
    String evt = "evt_" + run.substring(4) + type.substring(0, 2) + "v" + version;
    jdbcTemplate.update(
        "insert into workflow_events (public_id, workflow_run_id, event_type, actor_identity,"
            + " actor_type) values (?, ?, 'artifact.draftCreated', 'alex', 'human')",
        evt,
        runId);
    long eventId =
        jdbcTemplate.queryForObject(
            "select id from workflow_events where public_id = ?", Long.class, evt);

    byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
    String storageRef = payloadStore.write(run, artifactPublicId, version, ext(type), bytes);
    String checksum = ArtifactChecksum.digestHex("SHA-256", bytes).orElseThrow();

    jdbcTemplate.update(
        "insert into artifacts (public_id, workflow_run_id, artifact_type, version,"
            + " parent_artifact_id, classification, storage_ref, checksum_algorithm, checksum_value,"
            + " status, linked_event_id, created_at) values (?, ?, ?, ?, ?, 'shareable-redacted',"
            + " ?, 'SHA-256', ?, 'available', ?, now())",
        artifactPublicId,
        runId,
        type,
        version,
        parentDbId,
        storageRef,
        checksum,
        eventId);
    return jdbcTemplate.queryForObject(
        "select id from artifacts where public_id = ?", Long.class, artifactPublicId);
  }

  private static String ext(String type) {
    return "spec".equals(type) ? "spec.md" : type + ".json";
  }

  private String seedLink(String run, String integrationType, String externalRef) {
    long runId = runDbId(run);
    String link = "ilk_" + nextSfx();
    jdbcTemplate.update(
        "insert into integration_links (public_id, workflow_run_id, integration_type, external_ref,"
            + " external_metadata, sync_status) values (?, ?, ?, ?, cast('{}' as jsonb), 'synced')",
        link,
        runId,
        integrationType,
        externalRef);
    seededLinks.add(link);
    return link;
  }

  private String seedConflict(
      String run,
      String link,
      String category,
      String internalSnapshot,
      String externalSnapshot,
      String resolvedAt) {
    String conflict = "icf_" + nextSfx();
    jdbcTemplate.update(
        "insert into integration_conflicts (public_id, integration_link_id, workflow_run_id,"
            + " conflict_category, detected_at, internal_state_snapshot, external_state_snapshot,"
            + " resolved_at) values (?, ?, ?, ?, now(), cast(? as jsonb), cast(? as jsonb), ?)",
        conflict,
        link,
        run,
        category,
        internalSnapshot == null ? "{}" : internalSnapshot,
        externalSnapshot == null ? "{}" : externalSnapshot,
        resolvedAt);
    seededConflicts.add(conflict);
    return conflict;
  }
}
