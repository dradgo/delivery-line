package org.dradgo.application.artifact.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.dradgo.TestcontainersConfiguration;
import org.dradgo.application.artifact.ActorContext;
import org.dradgo.application.artifact.ArtifactChecksum;
import org.dradgo.application.artifact.ArtifactReconciliationService;
import org.dradgo.application.artifact.ArtifactRepairResult;
import org.dradgo.application.artifact.spi.ArtifactPayloadStore;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.DomainErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Story 4.16 (AC10) — real-Postgres coverage of the operator-driven repair path: per-category
 * repair (mark_corrupted / mark_operation_failed / mark_payload_unavailable / re_verify_checksum),
 * status flip + {@code resolved_at}/{@code resolved_by_action_id} set + a {@code recovery_actions}
 * row (widened {@code artifact_repair} CHECK accepted) + an {@code artifact.driftRepaired} event,
 * idempotent replay (no second row/event), {@code DRIFT_ALREADY_RESOLVED} on a re-repair with a
 * fresh key, and the re-verify resolve-on-match / leave-open-on-mismatch branches. Named {@code
 * *IT} so it runs under Failsafe (Testcontainers), never the no-Docker Surefire tier.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles({"test", "linear-mock"})
@Tag("integration")
class ArtifactDriftRepairIT {

  private static final ActorContext ACTOR =
      new ActorContext("operator-repair", ActorType.HUMAN, "corr-repair-it");

  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private ArtifactDriftDetectionService detectionService;
  @Autowired private ArtifactReconciliationService reconciliationService;
  @Autowired private ArtifactPayloadStore payloadStore;

  private final List<String> seededRuns = new java.util.ArrayList<>();

  @AfterEach
  void cleanUp() {
    jdbcTemplate.update("delete from artifact_drift_detected");
    for (String run : seededRuns) {
      Long runId =
          jdbcTemplate.queryForObject(
              "select id from workflow_runs where public_id = ?", Long.class, run);
      jdbcTemplate.update("delete from recovery_actions where workflow_run_id = ?", runId);
      jdbcTemplate.update("delete from artifact_operations where workflow_run_id = ?", runId);
      jdbcTemplate.update("delete from artifacts where workflow_run_id = ?", runId);
      jdbcTemplate.update("delete from integration_links where workflow_run_id = ?", runId);
      jdbcTemplate.update("delete from workflow_events where workflow_run_id = ?", runId);
      jdbcTemplate.update("delete from workflow_runs where public_id = ?", run);
    }
    seededRuns.clear();
  }

  @Test
  void markCorruptedFlipsStatusResolvesDriftAndRecordsRecoveryActionAndEvent() {
    Seed seed = seedRun("corrupt");
    long eventId = seedEvent(seed);
    String artifact = "art_corrupt" + seed.suffix;
    String ref =
        payloadStore.write(
            seed.runPublicId, artifact, 1, "spec.md", "corrupted".getBytes(StandardCharsets.UTF_8));
    // Stored checksum deliberately wrong -> detection records a checksum_mismatch drift.
    seedArtifact(seed, eventId, "available", artifact, ref, zeroChecksum());

    detectionService.detectDrift();
    String driftId = driftIdForArtifact(artifact);

    ArtifactRepairResult result =
        reconciliationService.markCorrupted(
            driftId, "confirmed corrupt on disk", ACTOR, "idem-corrupt-" + seed.suffix);

    assertThat(result.resolved()).isTrue();
    assertThat(result.replayed()).isFalse();
    assertThat(result.repairAction()).isEqualTo("mark_corrupted");

    // Artifact flipped to the new 'corrupted' status (V46 CHECK accepted it).
    assertThat(statusOf(artifact)).isEqualTo("corrupted");
    // Drift resolved, linked to the recovery action.
    assertThat(resolvedByActionOf(driftId)).isEqualTo(result.recoveryActionId());
    assertThat(resolvedAtIsSet(driftId)).isTrue();
    // recovery_actions row with the widened action_type.
    assertThat(actionTypeOf(result.recoveryActionId())).isEqualTo("artifact_repair");
    assertThat(resultStatusOf(result.recoveryActionId())).isEqualTo("succeeded");
    // One artifact.driftRepaired event.
    assertThat(driftRepairedEventCount(seed.runPublicId)).isEqualTo(1);
  }

  @Test
  void markOperationFailedResolvesOrphanDrift() {
    Seed seed = seedRun("orphan");
    long eventId = seedEvent(seed);
    String artifact = "art_orphan" + seed.suffix;
    long artifactId = seedArtifact(seed, eventId, "pending", artifact, null, null);
    String op = seedPendingOperation(seed, eventId, artifactId, "op_orphan" + seed.suffix);

    detectionService.detectDrift();
    String driftId = driftIdForOperation(op);

    ArtifactRepairResult result =
        reconciliationService.markOperationFailed(
            driftId, "stale orphan", ACTOR, "idem-orphan-" + seed.suffix);

    assertThat(result.resolved()).isTrue();
    assertThat(operationStatusOf(op)).isEqualTo("failed");
    assertThat(resolvedAtIsSet(driftId)).isTrue();
  }

  @Test
  void markPayloadUnavailableFailsArtifactAndResolvesMissingPayloadDrift() {
    Seed seed = seedRun("payload");
    long eventId = seedEvent(seed);
    String artifact = "art_payload" + seed.suffix;
    // Available artifact whose payload was never written -> detection records a missing_payload
    // drift (storage_ref present but not readable).
    seedArtifact(seed, eventId, "available", artifact, "missing-ref-" + seed.suffix, null);

    detectionService.detectDrift();
    String driftId = driftIdForArtifact(artifact);

    ArtifactRepairResult result =
        reconciliationService.markPayloadUnavailable(
            driftId, "payload deleted upstream", ACTOR, "idem-payload-" + seed.suffix);

    assertThat(result.resolved()).isTrue();
    assertThat(result.repairAction()).isEqualTo("mark_payload_unavailable");
    // AVAILABLE -> FAILED via the new adapter path; the adapter pairs a failure category + reason
    // so
    // the ck_artifacts_failure_reason_paired CHECK is satisfied (the known trap).
    assertThat(statusOf(artifact)).isEqualTo("failed");
    assertThat(resolvedAtIsSet(driftId)).isTrue();
    assertThat(actionTypeOf(result.recoveryActionId())).isEqualTo("artifact_repair");
    assertThat(resultStatusOf(result.recoveryActionId())).isEqualTo("succeeded");
    assertThat(driftRepairedEventCount(seed.runPublicId)).isEqualTo(1);
  }

  @Test
  void secondRepairUnderSameKeyReplaysWithoutASecondRowOrEvent() {
    Seed seed = seedRun("replay");
    long eventId = seedEvent(seed);
    String artifact = "art_replay" + seed.suffix;
    String ref =
        payloadStore.write(
            seed.runPublicId, artifact, 1, "spec.md", "corrupted".getBytes(StandardCharsets.UTF_8));
    seedArtifact(seed, eventId, "available", artifact, ref, zeroChecksum());
    detectionService.detectDrift();
    String driftId = driftIdForArtifact(artifact);
    String key = "idem-replay-" + seed.suffix;

    ArtifactRepairResult first = reconciliationService.markCorrupted(driftId, "first", ACTOR, key);
    ArtifactRepairResult second =
        reconciliationService.markCorrupted(driftId, "second", ACTOR, key);

    assertThat(first.replayed()).isFalse();
    assertThat(second.replayed()).isTrue();
    assertThat(second.recoveryActionId()).isEqualTo(first.recoveryActionId());
    // Exactly one recovery_actions row + one event despite two calls.
    assertThat(recoveryActionCount(seed.runPublicId)).isEqualTo(1);
    assertThat(driftRepairedEventCount(seed.runPublicId)).isEqualTo(1);
  }

  @Test
  void reRepairingAResolvedDriftWithAFreshKeyIsRejected() {
    Seed seed = seedRun("resolved");
    long eventId = seedEvent(seed);
    String artifact = "art_resolved" + seed.suffix;
    String ref =
        payloadStore.write(
            seed.runPublicId, artifact, 1, "spec.md", "corrupted".getBytes(StandardCharsets.UTF_8));
    seedArtifact(seed, eventId, "available", artifact, ref, zeroChecksum());
    detectionService.detectDrift();
    String driftId = driftIdForArtifact(artifact);

    reconciliationService.markCorrupted(driftId, "first", ACTOR, "idem-resolved-1-" + seed.suffix);

    assertThatThrownBy(
            () ->
                reconciliationService.markCorrupted(
                    driftId, "again", ACTOR, "idem-resolved-2-" + seed.suffix))
        .isInstanceOfSatisfying(
            DomainException.class,
            e -> assertThat(e.errorCode()).isEqualTo(DomainErrorCode.DRIFT_ALREADY_RESOLVED));
  }

  @Test
  void reVerifyChecksumResolvesWhenChecksumMatchesAndLeavesOpenOtherwise() {
    // Match case: available artifact whose stored checksum ACTUALLY matches the on-disk payload;
    // the drift is inserted directly (a transient false positive) and re-verify clears it.
    Seed matchSeed = seedRun("reverify-match");
    long matchEvent = seedEvent(matchSeed);
    String matchArtifact = "art_rvmatch" + matchSeed.suffix;
    byte[] good = "good-content".getBytes(StandardCharsets.UTF_8);
    String matchRef = payloadStore.write(matchSeed.runPublicId, matchArtifact, 1, "spec.md", good);
    String matchChecksum = ArtifactChecksum.digestHex("SHA-256", good).orElseThrow();
    seedArtifact(matchSeed, matchEvent, "available", matchArtifact, matchRef, matchChecksum);
    long matchArtifactId = artifactIdOf(matchArtifact);
    String matchDrift = insertChecksumMismatchDrift(matchSeed, matchArtifactId, matchArtifact);

    ArtifactRepairResult matchResult =
        reconciliationService.reVerifyChecksum(
            matchDrift, ACTOR, "idem-rvmatch-" + matchSeed.suffix);
    assertThat(matchResult.resolved()).isTrue();
    assertThat(statusOf(matchArtifact)).isEqualTo("available"); // no status change on match
    assertThat(resolvedAtIsSet(matchDrift)).isTrue();

    // Mismatch case: stored checksum wrong -> re-verify still mismatches -> drift stays open.
    Seed mismatchSeed = seedRun("reverify-mismatch");
    long mismatchEvent = seedEvent(mismatchSeed);
    String mismatchArtifact = "art_rvmiss" + mismatchSeed.suffix;
    String mismatchRef =
        payloadStore.write(
            mismatchSeed.runPublicId,
            mismatchArtifact,
            1,
            "spec.md",
            "corrupt".getBytes(StandardCharsets.UTF_8));
    seedArtifact(
        mismatchSeed, mismatchEvent, "available", mismatchArtifact, mismatchRef, zeroChecksum());
    detectionService.detectDrift();
    String mismatchDrift = driftIdForArtifact(mismatchArtifact);

    ArtifactRepairResult mismatchResult =
        reconciliationService.reVerifyChecksum(
            mismatchDrift, ACTOR, "idem-rvmiss-" + mismatchSeed.suffix);
    assertThat(mismatchResult.resolved()).isFalse();
    assertThat(resolvedAtIsSet(mismatchDrift)).isFalse();
    // The attempt is still audited: a recovery_actions row + event exist.
    assertThat(recoveryActionCount(mismatchSeed.runPublicId)).isEqualTo(1);
  }

  // ---- seeding + assertion helpers --------------------------------------------------------------

  private record Seed(String runPublicId, long runId, String suffix) {}

  private Seed seedRun(String label) {
    String suffix = label + Integer.toHexString(System.identityHashCode(label)) + seededRuns.size();
    String run = "run_arprit" + suffix;
    jdbcTemplate.update(
        "insert into workflow_runs (public_id, current_state) values (?, 'Executing')", run);
    seededRuns.add(run);
    Long runId =
        jdbcTemplate.queryForObject(
            "select id from workflow_runs where public_id = ?", Long.class, run);
    return new Seed(run, runId, suffix);
  }

  private long seedEvent(Seed seed) {
    String evt = "evt_arprit" + seed.suffix;
    jdbcTemplate.update(
        "insert into workflow_events (public_id, workflow_run_id, event_type, actor_identity,"
            + " actor_type) values (?, ?, 'artifact.draftCreated', 'system', 'system')",
        evt,
        seed.runId);
    return jdbcTemplate.queryForObject(
        "select id from workflow_events where public_id = ?", Long.class, evt);
  }

  private long seedArtifact(
      Seed seed,
      long eventId,
      String status,
      String artifactPublicId,
      String storageRef,
      String checksumValue) {
    String algo = checksumValue == null ? null : "SHA-256";
    jdbcTemplate.update(
        "insert into artifacts (public_id, workflow_run_id, artifact_type, version, classification,"
            + " storage_ref, checksum_algorithm, checksum_value, status, linked_event_id,"
            + " created_at) values (?, ?, 'spec', 1, 'shareable-redacted', ?, ?, ?, ?, ?,"
            + " now() - interval '1 hour')",
        artifactPublicId,
        seed.runId,
        storageRef,
        algo,
        checksumValue,
        status,
        eventId);
    return artifactIdOf(artifactPublicId);
  }

  private String seedPendingOperation(
      Seed seed, long eventId, long artifactId, String operationPublicId) {
    jdbcTemplate.update(
        "insert into artifact_operations (public_id, workflow_run_id, artifact_id, artifact_type,"
            + " linked_event_id, operation_type, status, idempotency_key, created_at)"
            + " values (?, ?, ?, 'spec', ?, 'create', 'pending', ?, now() - interval '1 hour')",
        operationPublicId,
        seed.runId,
        artifactId,
        eventId,
        "idem_" + operationPublicId);
    return operationPublicId;
  }

  private String insertChecksumMismatchDrift(Seed seed, long artifactId, String artifactPublicId) {
    String driftId = "adr_rvm" + seed.suffix;
    jdbcTemplate.update(
        "insert into artifact_drift_detected (public_id, workflow_run_id, artifact_id,"
            + " drift_category, last_known_state) values (?, ?, ?, 'checksum_mismatch', '{}'::jsonb)",
        driftId,
        seed.runPublicId,
        artifactPublicId);
    return driftId;
  }

  private static String zeroChecksum() {
    return "0000000000000000000000000000000000000000000000000000000000000000";
  }

  private long artifactIdOf(String artifactPublicId) {
    return jdbcTemplate.queryForObject(
        "select id from artifacts where public_id = ?", Long.class, artifactPublicId);
  }

  private String statusOf(String artifactPublicId) {
    return jdbcTemplate.queryForObject(
        "select status from artifacts where public_id = ?", String.class, artifactPublicId);
  }

  private String operationStatusOf(String op) {
    return jdbcTemplate.queryForObject(
        "select status from artifact_operations where public_id = ?", String.class, op);
  }

  private String driftIdForArtifact(String artifactPublicId) {
    return jdbcTemplate.queryForObject(
        "select public_id from artifact_drift_detected where artifact_id = ? order by id desc"
            + " limit 1",
        String.class,
        artifactPublicId);
  }

  private String driftIdForOperation(String op) {
    return jdbcTemplate.queryForObject(
        "select public_id from artifact_drift_detected where artifact_operation_id = ? order by id"
            + " desc limit 1",
        String.class,
        op);
  }

  private String resolvedByActionOf(String driftId) {
    return jdbcTemplate.queryForObject(
        "select resolved_by_action_id from artifact_drift_detected where public_id = ?",
        String.class,
        driftId);
  }

  private boolean resolvedAtIsSet(String driftId) {
    Integer n =
        jdbcTemplate.queryForObject(
            "select count(*) from artifact_drift_detected where public_id = ? and resolved_at is"
                + " not null",
            Integer.class,
            driftId);
    return n != null && n > 0;
  }

  private String actionTypeOf(String recoveryActionId) {
    return jdbcTemplate.queryForObject(
        "select action_type from recovery_actions where public_id = ?",
        String.class,
        recoveryActionId);
  }

  private String resultStatusOf(String recoveryActionId) {
    return jdbcTemplate.queryForObject(
        "select result_status from recovery_actions where public_id = ?",
        String.class,
        recoveryActionId);
  }

  private int recoveryActionCount(String run) {
    Integer count =
        jdbcTemplate.queryForObject(
            "select count(*) from recovery_actions where action_type = 'artifact_repair'"
                + "   and workflow_run_id = (select id from workflow_runs where public_id = ?)",
            Integer.class,
            run);
    return count == null ? 0 : count;
  }

  private int driftRepairedEventCount(String run) {
    Integer count =
        jdbcTemplate.queryForObject(
            "select count(*) from workflow_events where event_type = 'artifact.driftRepaired'"
                + "   and workflow_run_id = (select id from workflow_runs where public_id = ?)",
            Integer.class,
            run);
    return count == null ? 0 : count;
  }
}
