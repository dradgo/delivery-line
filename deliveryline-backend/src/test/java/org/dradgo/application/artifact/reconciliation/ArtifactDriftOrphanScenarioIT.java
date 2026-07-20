package org.dradgo.application.artifact.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import org.dradgo.TestcontainersConfiguration;
import org.dradgo.application.artifact.ActorContext;
import org.dradgo.application.artifact.ArtifactReconciliationService;
import org.dradgo.application.artifact.ArtifactRepairResult;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.DomainErrorCode;
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
 * Story 4.25 (AC3a) — recovery-integration CI-tier SCENARIO: orphan-operation artifact-drift fault
 * injection end-to-end on Testcontainers Postgres. Injects the fault by seeding a stale {@code
 * pending} {@code artifact_operations} row with a BACKDATED {@code created_at} (past the
 * stale-pending threshold — nothing new is built for injection), drives one synchronous {@link
 * ArtifactDriftDetectionService#detectDrift()} (AC3 "within one cycle" == one call), and asserts
 * the {@code orphan_operation} drift is recorded, then repaired via {@link
 * ArtifactReconciliationService#markOperationFailed}: the operation flips to {@code failed}, the
 * drift resolves (linked to an {@code artifact_repair}/{@code succeeded} recovery action), an
 * {@code artifact.driftRepaired} event lands, the repair replays under the same key, and a
 * fresh-key re-repair is rejected {@code DRIFT_ALREADY_RESOLVED}. Templates: {@link
 * ArtifactDriftDetectionIT} + {@link ArtifactDriftRepairIT}.
 *
 * <p>Per-scenario isolation (AC9): fresh {@link TempDir} {@code deliveryline.home} + per-class
 * Testcontainers Postgres. Tagged {@code @Tag("recovery-integration")}.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles({"test", "linear-mock"})
@Tag("recovery-integration")
class ArtifactDriftOrphanScenarioIT {

  private static final ActorContext ACTOR =
      new ActorContext("operator-scenario", ActorType.HUMAN, "corr-orphan-scenario");
  private static final String RUN = "run_orphanscn01";

  @TempDir static Path deliverylineHome;

  @DynamicPropertySource
  static void deliverylineHome(DynamicPropertyRegistry registry) {
    registry.add("deliveryline.home", () -> deliverylineHome.toString());
  }

  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private ArtifactDriftDetectionService detectionService;
  @Autowired private ArtifactReconciliationService reconciliationService;

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
      jdbcTemplate.update("delete from artifact_operations where workflow_run_id = ?", runId);
      jdbcTemplate.update("delete from artifacts where workflow_run_id = ?", runId);
      jdbcTemplate.update("delete from workflow_events where workflow_run_id = ?", runId);
      jdbcTemplate.update("delete from workflow_runs where public_id = ?", RUN);
    }
  }

  @Test
  void staleOrphanOperationIsDetectedAndRepairedViaMarkOperationFailed() {
    long runId = seedRun();
    long eventId = seedEvent(runId);
    long artifactId = seedPendingArtifact(runId, eventId);
    String op = seedStalePendingOperation(runId, eventId, artifactId);

    // Inject == detect: one synchronous sweep flags the backdated pending op as an orphan.
    detectionService.detectDrift();

    String driftId =
        jdbcTemplate.queryForObject(
            "select public_id from artifact_drift_detected where artifact_operation_id = ?"
                + " order by id desc limit 1",
            String.class,
            op);
    assertThat(driftCategory(driftId)).isEqualTo(DriftCategory.ORPHAN_OPERATION.value());
    assertThat(driftDetectedEventCount(runId)).isEqualTo(1);

    // Repair: mark the stale operation failed → drift resolves, audited.
    ArtifactRepairResult result =
        reconciliationService.markOperationFailed(
            driftId, "stale orphan operation", ACTOR, "idem-orphan-scn-1");
    assertThat(result.resolved()).isTrue();
    assertThat(result.replayed()).isFalse();
    assertThat(result.repairAction()).isEqualTo("mark_operation_failed");
    assertThat(operationStatus(op)).isEqualTo("failed");
    assertThat(resolvedAtIsSet(driftId)).isTrue();
    assertThat(resolvedByAction(driftId)).isEqualTo(result.recoveryActionId());
    assertThat(actionType(result.recoveryActionId())).isEqualTo("artifact_repair");
    assertThat(resultStatus(result.recoveryActionId())).isEqualTo("succeeded");
    assertThat(driftRepairedEventCount(runId)).isEqualTo(1);

    // Idempotent replay under the same key — no second row/event.
    ArtifactRepairResult replay =
        reconciliationService.markOperationFailed(
            driftId, "stale orphan operation", ACTOR, "idem-orphan-scn-1");
    assertThat(replay.replayed()).isTrue();
    assertThat(replay.recoveryActionId()).isEqualTo(result.recoveryActionId());
    assertThat(artifactRepairActionCount(runId)).isEqualTo(1);
    assertThat(driftRepairedEventCount(runId)).isEqualTo(1);

    // A fresh-key re-repair of an already-resolved drift is rejected.
    assertThatThrownBy(
            () ->
                reconciliationService.markOperationFailed(
                    driftId, "again", ACTOR, "idem-orphan-scn-2"))
        .isInstanceOfSatisfying(
            DomainException.class,
            e -> assertThat(e.errorCode()).isEqualTo(DomainErrorCode.DRIFT_ALREADY_RESOLVED));
  }

  // ---- seed + assertion helpers -----------------------------------------------------------------

  private long seedRun() {
    jdbcTemplate.update(
        "insert into workflow_runs (public_id, current_state) values (?, 'Executing')", RUN);
    return jdbcTemplate.queryForObject(
        "select id from workflow_runs where public_id = ?", Long.class, RUN);
  }

  private long seedEvent(long runId) {
    String evt = "evt_orphanscn01";
    jdbcTemplate.update(
        "insert into workflow_events (public_id, workflow_run_id, event_type, actor_identity,"
            + " actor_type) values (?, ?, 'artifact.draftCreated', 'system', 'system')",
        evt,
        runId);
    return jdbcTemplate.queryForObject(
        "select id from workflow_events where public_id = ?", Long.class, evt);
  }

  private long seedPendingArtifact(long runId, long eventId) {
    jdbcTemplate.update(
        "insert into artifacts (public_id, workflow_run_id, artifact_type, version, classification,"
            + " storage_ref, checksum_algorithm, checksum_value, status, linked_event_id,"
            + " created_at) values ('art_orphanscn01', ?, 'spec', 1, 'shareable-redacted', null,"
            + " null, null, 'pending', ?, now() - interval '1 hour')",
        runId,
        eventId);
    return jdbcTemplate.queryForObject(
        "select id from artifacts where public_id = 'art_orphanscn01'", Long.class);
  }

  private String seedStalePendingOperation(long runId, long eventId, long artifactId) {
    String op = "op_orphanscn01";
    jdbcTemplate.update(
        "insert into artifact_operations (public_id, workflow_run_id, artifact_id, artifact_type,"
            + " linked_event_id, operation_type, status, idempotency_key, created_at)"
            + " values (?, ?, ?, 'spec', ?, 'create', 'pending', ?, now() - interval '1 hour')",
        op,
        runId,
        artifactId,
        eventId,
        "idem_" + op);
    return op;
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

  private String resolvedByAction(String driftId) {
    return jdbcTemplate.queryForObject(
        "select resolved_by_action_id from artifact_drift_detected where public_id = ?",
        String.class,
        driftId);
  }

  private String operationStatus(String op) {
    return jdbcTemplate.queryForObject(
        "select status from artifact_operations where public_id = ?", String.class, op);
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

  private int artifactRepairActionCount(long runId) {
    Integer count =
        jdbcTemplate.queryForObject(
            "select count(*) from recovery_actions where action_type = 'artifact_repair'"
                + " and workflow_run_id = ?",
            Integer.class,
            runId);
    return count == null ? 0 : count;
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
