package org.dradgo.application.artifact.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.dradgo.TestcontainersConfiguration;
import org.dradgo.application.artifact.ActorContext;
import org.dradgo.application.artifact.ArtifactReconciliationService;
import org.dradgo.application.artifact.ArtifactRepairResult;
import org.dradgo.application.artifact.spi.ArtifactPayloadStore;
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.DriftCategory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Story 4.25 (AC3b) — recovery-integration CI-tier SCENARIO: missing-payload artifact-drift fault
 * injection end-to-end on Testcontainers Postgres. Injects the fault by writing a REAL payload file
 * through {@link ArtifactPayloadStore} into the per-test {@link TempDir} {@code deliveryline.home},
 * then DELETING it from disk (nothing new is built for injection — the payload store IS the seam),
 * so an {@code available} artifact's {@code storage_ref} no longer resolves to a readable file. One
 * synchronous {@link ArtifactDriftDetectionService#detectDrift()} records the {@code
 * missing_payload} drift, then it is repaired via {@link
 * ArtifactReconciliationService#markPayloadUnavailable}: the artifact flips {@code available →
 * failed} (the adapter pairs a failure category + reason so the {@code
 * ck_artifacts_failure_reason_paired} CHECK holds), the drift resolves, and an {@code
 * artifact.driftRepaired} event + an {@code artifact_repair}/{@code succeeded} recovery action
 * land. Templates: {@link ArtifactDriftDetectionIT} + {@link ArtifactDriftRepairIT}.
 *
 * <p>Per-scenario isolation (AC9): fresh {@link TempDir} {@code deliveryline.home} + per-class
 * Testcontainers Postgres. Tagged {@code @Tag("recovery-integration")}.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles({"test", "linear-mock"})
@Tag("recovery-integration")
class ArtifactDriftMissingPayloadScenarioIT {

  private static final ActorContext ACTOR =
      new ActorContext("operator-scenario", ActorType.HUMAN, "corr-missing-scenario");
  private static final String RUN = "run_missingscn01";
  private static final String ARTIFACT = "art_missingscn01";

  @TempDir static Path deliverylineHome;

  @DynamicPropertySource
  static void deliverylineHome(DynamicPropertyRegistry registry) {
    registry.add("deliveryline.home", () -> deliverylineHome.toString());
  }

  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private ArtifactDriftDetectionService detectionService;
  @Autowired private ArtifactReconciliationService reconciliationService;
  @Autowired private ArtifactPayloadStore payloadStore;

  @AfterEach
  void cleanUp() {
    jdbcTemplate.update("delete from artifact_drift_detected");
    Long runId =
        jdbcTemplate.query(
            "select id from workflow_runs where public_id = ?",
            rs -> rs.next() ? rs.getLong(1) : null,
            RUN);
    if (runId != null) {
      jdbcTemplate.update("delete from recovery_actions where workflow_run_id = ?", runId);
      jdbcTemplate.update("delete from artifacts where workflow_run_id = ?", runId);
      jdbcTemplate.update("delete from workflow_events where workflow_run_id = ?", runId);
      jdbcTemplate.update("delete from workflow_runs where public_id = ?", RUN);
    }
  }

  @Test
  void deletedPayloadFileIsDetectedAsMissingAndRepairedViaMarkPayloadUnavailable()
      throws Exception {
    long runId = seedRun();
    long eventId = seedEvent(runId);

    // Inject: write a REAL payload, then delete the file from disk so it is no longer readable.
    String ref =
        payloadStore.write(RUN, ARTIFACT, 1, "spec.md", "payload".getBytes(StandardCharsets.UTF_8));
    seedAvailableArtifact(runId, eventId, ref, zeroChecksum());
    assertThat(payloadStore.isReadable(ref)).isTrue();
    Files.delete(deliverylineHome.toRealPath().resolve(ref));
    assertThat(payloadStore.isReadable(ref)).isFalse();

    detectionService.detectDrift();

    String driftId = driftIdForArtifact();
    assertThat(driftCategory(driftId)).isEqualTo(DriftCategory.MISSING_PAYLOAD.value());
    assertThat(driftDetectedEventCount(runId)).isEqualTo(1);

    // Repair: mark the payload unavailable → artifact fails, drift resolves, audited.
    ArtifactRepairResult result =
        reconciliationService.markPayloadUnavailable(
            driftId, "payload deleted upstream", ACTOR, "idem-missing-scn-1");
    assertThat(result.resolved()).isTrue();
    assertThat(result.repairAction()).isEqualTo("mark_payload_unavailable");
    assertThat(artifactStatus()).isEqualTo("failed");
    assertThat(resolvedAtIsSet(driftId)).isTrue();
    assertThat(actionType(result.recoveryActionId())).isEqualTo("artifact_repair");
    assertThat(resultStatus(result.recoveryActionId())).isEqualTo("succeeded");
    assertThat(driftRepairedEventCount(runId)).isEqualTo(1);
  }

  // ---- seed + assertion helpers -----------------------------------------------------------------

  private static String zeroChecksum() {
    return "0000000000000000000000000000000000000000000000000000000000000000";
  }

  private long seedRun() {
    jdbcTemplate.update(
        "insert into workflow_runs (public_id, current_state) values (?, 'Executing')", RUN);
    return jdbcTemplate.queryForObject(
        "select id from workflow_runs where public_id = ?", Long.class, RUN);
  }

  private long seedEvent(long runId) {
    String evt = "evt_missingscn01";
    jdbcTemplate.update(
        "insert into workflow_events (public_id, workflow_run_id, event_type, actor_identity,"
            + " actor_type) values (?, ?, 'artifact.draftCreated', 'system', 'system')",
        evt,
        runId);
    return jdbcTemplate.queryForObject(
        "select id from workflow_events where public_id = ?", Long.class, evt);
  }

  private void seedAvailableArtifact(long runId, long eventId, String storageRef, String checksum) {
    jdbcTemplate.update(
        "insert into artifacts (public_id, workflow_run_id, artifact_type, version, classification,"
            + " storage_ref, checksum_algorithm, checksum_value, status, linked_event_id,"
            + " created_at) values (?, ?, 'spec', 1, 'shareable-redacted', ?, 'SHA-256', ?,"
            + " 'available', ?, now() - interval '1 hour')",
        ARTIFACT,
        runId,
        storageRef,
        checksum,
        eventId);
  }

  private String driftIdForArtifact() {
    return jdbcTemplate.queryForObject(
        "select public_id from artifact_drift_detected where artifact_id = ? order by id desc"
            + " limit 1",
        String.class,
        ARTIFACT);
  }

  private String driftCategory(String driftId) {
    return jdbcTemplate.queryForObject(
        "select drift_category from artifact_drift_detected where public_id = ?",
        String.class,
        driftId);
  }

  private boolean resolvedAtIsSet(String driftId) {
    Integer n =
        jdbcTemplate.queryForObject(
            "select count(*) from artifact_drift_detected where public_id = ? and resolved_at is not"
                + " null",
            Integer.class,
            driftId);
    return n != null && n > 0;
  }

  private String artifactStatus() {
    return jdbcTemplate.queryForObject(
        "select status from artifacts where public_id = ?", String.class, ARTIFACT);
  }

  private String actionType(String recoveryActionId) {
    return jdbcTemplate.queryForObject(
        "select action_type from recovery_actions where public_id = ?",
        String.class,
        recoveryActionId);
  }

  private String resultStatus(String recoveryActionId) {
    return jdbcTemplate.queryForObject(
        "select result_status from recovery_actions where public_id = ?",
        String.class,
        recoveryActionId);
  }

  private int driftDetectedEventCount(long runId) {
    Integer count =
        jdbcTemplate.queryForObject(
            "select count(*) from workflow_events where event_type = 'artifact.driftDetected'"
                + " and workflow_run_id = ?",
            Integer.class,
            runId);
    return count == null ? 0 : count;
  }

  private int driftRepairedEventCount(long runId) {
    Integer count =
        jdbcTemplate.queryForObject(
            "select count(*) from workflow_events where event_type = 'artifact.driftRepaired'"
                + " and workflow_run_id = ?",
            Integer.class,
            runId);
    return count == null ? 0 : count;
  }
}
