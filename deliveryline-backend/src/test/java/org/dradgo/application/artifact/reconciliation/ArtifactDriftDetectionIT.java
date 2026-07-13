package org.dradgo.application.artifact.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.dradgo.TestcontainersConfiguration;
import org.dradgo.application.artifact.ArtifactReconciliationService;
import org.dradgo.application.artifact.ArtifactReconciliationService.DriftFilter;
import org.dradgo.application.artifact.ArtifactReconciliationService.DriftSummary;
import org.dradgo.application.artifact.ArtifactReconciliationService.RepairActionHint;
import org.dradgo.application.artifact.reconciliation.spi.ArtifactDriftReadPort;
import org.dradgo.application.artifact.spi.ArtifactPayloadStore;
import org.dradgo.domain.registry.DriftCategory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Story 4.15 (AC10) — real-Postgres coverage of the artifact-drift-detection sweep: the three
 * category detections against seeded artifacts/operations (orphan operation, missing payload,
 * checksum mismatch), one-event-per-row, idempotent re-scan (no new row/event), and the {@code
 * listUnresolvedDrift} filter + repair-hint enrichment. Named {@code *IT} so it runs under Failsafe
 * (Testcontainers), never the no-Docker Surefire tier.
 *
 * <p>Seeds with <strong>backdated</strong> {@code created_at} so both the stale-pending threshold
 * and the available-scan min-age window are satisfied without waiting. The sweep's
 * available-artifact scan is GLOBAL, so {@code @AfterEach} nukes every {@code
 * artifact_drift_detected} row (this is the only suite that writes them) before deleting the seeded
 * parents — otherwise a collateral drift row referencing another test's artifact would leak the ON
 * DELETE RESTRICT FK into that test's cleanup.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles({"test", "linear-mock"})
@Tag("integration")
class ArtifactDriftDetectionIT {

  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private ArtifactDriftDetectionService detectionService;
  @Autowired private ArtifactReconciliationService reconciliationService;
  @Autowired private ArtifactDriftReadPort driftReadPort;
  @Autowired private ArtifactPayloadStore payloadStore;

  private final List<String> seededRuns = new java.util.ArrayList<>();

  @AfterEach
  void cleanUp() {
    // Nuke ALL drift rows first (only this IT writes them) so collateral rows the global scan may
    // have recorded against other tests' available artifacts do not block their RESTRICT-FK
    // cleanup.
    jdbcTemplate.update("delete from artifact_drift_detected");
    for (String run : seededRuns) {
      Long runId =
          jdbcTemplate.queryForObject(
              "select id from workflow_runs where public_id = ?", Long.class, run);
      jdbcTemplate.update("delete from artifact_operations where workflow_run_id = ?", runId);
      jdbcTemplate.update("delete from artifacts where workflow_run_id = ?", runId);
      jdbcTemplate.update("delete from integration_links where workflow_run_id = ?", runId);
      jdbcTemplate.update("delete from workflow_events where workflow_run_id = ?", runId);
      jdbcTemplate.update("delete from workflow_runs where public_id = ?", run);
    }
    seededRuns.clear();
  }

  @Test
  void orphanOperationIsDetectedRecordedAndListed() {
    Seed seed = seedRun("orphan");
    long eventId = seedEvent(seed);
    // Orphan: a pending artifact whose payload never materialized + a stale pending operation.
    long artifactId =
        seedArtifact(seed, eventId, "pending", "art_orphan" + seed.suffix, null, null);
    String op = seedPendingOperation(seed, eventId, artifactId, "op_orphan" + seed.suffix);

    detectionService.detectDrift();

    assertThat(driftCategoryForOperation(op)).isEqualTo(DriftCategory.ORPHAN_OPERATION.value());
    assertThat(driftEventCount(seed.runPublicId)).isEqualTo(1);

    List<DriftSummary> listed =
        reconciliationService.listUnresolvedDrift(DriftFilter.forRun(seed.runPublicId));
    assertThat(listed)
        .anySatisfy(
            d -> {
              assertThat(d.artifactOperationId()).isEqualTo(op);
              assertThat(d.artifactId()).isNull();
              assertThat(d.driftCategory()).isEqualTo(DriftCategory.ORPHAN_OPERATION);
              assertThat(d.suggestedRepairAction())
                  .isEqualTo(RepairActionHint.MARK_FAILED_OR_COMPLETE);
            });
  }

  @Test
  void missingPayloadIsDetected() {
    Seed seed = seedRun("missing");
    long eventId = seedEvent(seed);
    // available artifact whose storage_ref points to a file that was never written.
    seedArtifact(
        seed,
        eventId,
        "available",
        "art_missing" + seed.suffix,
        "artifacts/gone/" + seed.suffix + "/v1/deleted.md",
        "0000000000000000000000000000000000000000000000000000000000000000");

    detectionService.detectDrift();

    List<DriftSummary> listed =
        reconciliationService.listUnresolvedDrift(DriftFilter.forRun(seed.runPublicId));
    assertThat(listed)
        .anySatisfy(
            d -> {
              assertThat(d.driftCategory()).isEqualTo(DriftCategory.MISSING_PAYLOAD);
              assertThat(d.artifactId()).isEqualTo("art_missing" + seed.suffix);
              assertThat(d.suggestedRepairAction())
                  .isEqualTo(RepairActionHint.RESTORE_FROM_BACKUP_OR_MARK_UNAVAILABLE);
            });
  }

  @Test
  void checksumMismatchIsDetectedButMatchingPayloadIsNot() {
    Seed matchSeed = seedRun("match");
    long matchEvent = seedEvent(matchSeed);
    String matchRef =
        payloadStore.write(
            matchSeed.runPublicId,
            "art_match" + matchSeed.suffix,
            1,
            "spec.md",
            "correct-content".getBytes(StandardCharsets.UTF_8));
    String matchChecksum =
        org.dradgo.application.artifact.ArtifactChecksum.digestHex(
                "SHA-256", "correct-content".getBytes(StandardCharsets.UTF_8))
            .orElseThrow();
    seedArtifact(
        matchSeed,
        matchEvent,
        "available",
        "art_match" + matchSeed.suffix,
        matchRef,
        matchChecksum);

    Seed mismatchSeed = seedRun("mismatch");
    long mismatchEvent = seedEvent(mismatchSeed);
    String mismatchRef =
        payloadStore.write(
            mismatchSeed.runPublicId,
            "art_corrupt" + mismatchSeed.suffix,
            1,
            "spec.md",
            "corrupted-content".getBytes(StandardCharsets.UTF_8));
    // Stored checksum deliberately does NOT match the on-disk bytes → recompute mismatch.
    seedArtifact(
        mismatchSeed,
        mismatchEvent,
        "available",
        "art_corrupt" + mismatchSeed.suffix,
        mismatchRef,
        "0000000000000000000000000000000000000000000000000000000000000000");

    detectionService.detectDrift();

    assertThat(
            reconciliationService.listUnresolvedDrift(DriftFilter.forRun(mismatchSeed.runPublicId)))
        .anySatisfy(
            d -> {
              assertThat(d.driftCategory()).isEqualTo(DriftCategory.CHECKSUM_MISMATCH);
              assertThat(d.suggestedRepairAction())
                  .isEqualTo(RepairActionHint.RE_VERIFY_OR_MARK_CORRUPTED);
            });
    // The matching payload produced no drift row.
    assertThat(reconciliationService.listUnresolvedDrift(DriftFilter.forRun(matchSeed.runPublicId)))
        .isEmpty();
  }

  @Test
  void secondScanIsIdempotentNoNewRowOrEvent() {
    Seed seed = seedRun("idem");
    long eventId = seedEvent(seed);
    long artifactId = seedArtifact(seed, eventId, "pending", "art_idem" + seed.suffix, null, null);
    String op = seedPendingOperation(seed, eventId, artifactId, "op_idem" + seed.suffix);

    detectionService.detectDrift();
    detectionService.detectDrift();

    Integer rows =
        jdbcTemplate.queryForObject(
            "select count(*) from artifact_drift_detected where artifact_operation_id = ?",
            Integer.class,
            op);
    assertThat(rows).isEqualTo(1);
    assertThat(driftEventCount(seed.runPublicId)).isEqualTo(1);
  }

  @Test
  void listUnresolvedDriftFiltersByCategory() {
    Seed seed = seedRun("filter");
    long eventId = seedEvent(seed);
    long artifactId =
        seedArtifact(seed, eventId, "pending", "art_filter" + seed.suffix, null, null);
    seedPendingOperation(seed, eventId, artifactId, "op_filter" + seed.suffix);

    detectionService.detectDrift();

    assertThat(
            reconciliationService.listUnresolvedDrift(
                new DriftFilter(
                    DriftCategory.ORPHAN_OPERATION.value(), null, seed.runPublicId, null, null)))
        .isNotEmpty();
    assertThat(
            reconciliationService.listUnresolvedDrift(
                new DriftFilter(
                    DriftCategory.CHECKSUM_MISMATCH.value(), null, seed.runPublicId, null, null)))
        .isEmpty();
    // The count-by-category read (gauge source) reflects the recorded orphan drift.
    assertThat(driftReadPort.countUnresolvedByCategory())
        .anySatisfy(
            c -> {
              assertThat(c.driftCategory()).isEqualTo(DriftCategory.ORPHAN_OPERATION);
              assertThat(c.count()).isGreaterThanOrEqualTo(1);
            });
  }

  @Test
  void listUnresolvedDriftFiltersByTimeSince() {
    Seed seed = seedRun("since");
    long eventId = seedEvent(seed);
    long artifactId = seedArtifact(seed, eventId, "pending", "art_since" + seed.suffix, null, null);
    seedPendingOperation(seed, eventId, artifactId, "op_since" + seed.suffix);

    detectionService.detectDrift();
    // Backdate the recorded drift so a narrow time-since window excludes it and a wide one keeps
    // it.
    jdbcTemplate.update(
        "update artifact_drift_detected set detected_at = now() - interval '10 minutes'"
            + " where workflow_run_id = ?",
        seed.runPublicId);

    assertThat(
            reconciliationService.listUnresolvedDrift(
                new DriftFilter(null, java.time.Duration.ofHours(1), seed.runPublicId, null, null)))
        .as("a 1-hour window includes a drift detected 10 minutes ago")
        .isNotEmpty();
    assertThat(
            reconciliationService.listUnresolvedDrift(
                new DriftFilter(
                    null, java.time.Duration.ofMinutes(1), seed.runPublicId, null, null)))
        .as("a 1-minute window excludes a drift detected 10 minutes ago")
        .isEmpty();
  }

  @Test
  void listUnresolvedDriftFiltersByTicketReference() {
    Seed seed = seedRun("ticket");
    long eventId = seedEvent(seed);
    long artifactId =
        seedArtifact(seed, eventId, "pending", "art_ticket" + seed.suffix, null, null);
    String op = seedPendingOperation(seed, eventId, artifactId, "op_ticket" + seed.suffix);
    seedLinearLink(seed, "ADR-4-15");

    detectionService.detectDrift();

    assertThat(
            reconciliationService.listUnresolvedDrift(
                new DriftFilter(null, null, null, "ADR-4-15", null)))
        .as("the drift surfaces when filtered by the run's linear ticket ref")
        .anySatisfy(d -> assertThat(d.artifactOperationId()).isEqualTo(op));
    assertThat(
            reconciliationService.listUnresolvedDrift(
                new DriftFilter(null, null, null, "NO-SUCH-TICKET", null)))
        .as("no run carries this ticket ref, so the filter returns empty")
        .isEmpty();
  }

  // ---- seeding + assertion helpers --------------------------------------------------------------

  private record Seed(String runPublicId, long runId, String suffix) {}

  private Seed seedRun(String label) {
    String suffix = label + Integer.toHexString(System.identityHashCode(label)) + seededRuns.size();
    String run = "run_adrit" + suffix;
    jdbcTemplate.update(
        "insert into workflow_runs (public_id, current_state) values (?, 'Executing')", run);
    seededRuns.add(run);
    Long runId =
        jdbcTemplate.queryForObject(
            "select id from workflow_runs where public_id = ?", Long.class, run);
    return new Seed(run, runId, suffix);
  }

  private long seedEvent(Seed seed) {
    String evt = "evt_adrit" + seed.suffix;
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
    // Backdate created_at so both the stale threshold and the available-scan min-age window pass.
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
    return jdbcTemplate.queryForObject(
        "select id from artifacts where public_id = ?", Long.class, artifactPublicId);
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

  private void seedLinearLink(Seed seed, String externalRef) {
    jdbcTemplate.update(
        "insert into integration_links"
            + " (public_id, workflow_run_id, integration_type, external_ref, sync_status)"
            + " values (?, ?, 'linear', ?, 'linked')",
        "ilk_adrit" + seed.suffix,
        seed.runId,
        externalRef);
  }

  private String driftCategoryForOperation(String op) {
    return jdbcTemplate.queryForObject(
        "select drift_category from artifact_drift_detected where artifact_operation_id = ?"
            + " order by detected_at desc limit 1",
        String.class,
        op);
  }

  private int driftEventCount(String run) {
    Integer count =
        jdbcTemplate.queryForObject(
            "select count(*) from workflow_events"
                + " where event_type = 'artifact.driftDetected'"
                + "   and workflow_run_id = (select id from workflow_runs where public_id = ?)",
            Integer.class,
            run);
    return count == null ? 0 : count;
  }
}
