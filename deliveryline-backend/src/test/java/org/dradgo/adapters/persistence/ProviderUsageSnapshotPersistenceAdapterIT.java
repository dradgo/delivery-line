package org.dradgo.adapters.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import org.dradgo.TestcontainersConfiguration;
import org.dradgo.application.runner.ProviderUsageSnapshotView;
import org.dradgo.application.runner.spi.ProviderUsageSnapshotReadPort;
import org.dradgo.application.runner.spi.ProviderUsageSnapshotReadPort.SignalStateCounts;
import org.dradgo.application.runner.spi.ProviderUsageSnapshotWritePort;
import org.dradgo.application.runner.spi.ProviderUsageSnapshotWritePort.NewProviderUsageSnapshot;
import org.dradgo.domain.id.PublicIdPrefixes;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Story 3d-7 (FR69, AC3/AC4/AC6/AC7) — real-Postgres round-trip for {@link
 * ProviderUsageSnapshotPersistenceAdapter}: the {@code available} + {@code not_exposed} write/read
 * paths, latest-wins ordering, per-credential {@code account_reference} attribution (NON-SECRET,
 * NOT a project_credentials FK — Trap T1), the no-secret column sweep, and the signal-state counts
 * that back the observability gauge. Named {@code *IT} so Failsafe runs it.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles({"test", "linear-mock"})
@Tag("integration")
@Transactional
class ProviderUsageSnapshotPersistenceAdapterIT {

  @Autowired private ProviderUsageSnapshotWritePort writePort;
  @Autowired private ProviderUsageSnapshotReadPort readPort;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void availableSnapshotRoundTripsThroughFindLatest() {
    String runId = PublicIdPrefixes.WORKFLOW_RUN.next();
    String publicId = "pul_itav" + suffix();
    OffsetDateTime createdAt =
        writePort.insert(
            new NewProviderUsageSnapshot(
                publicId,
                runId,
                "rex_av" + suffix(),
                "claude:oauth",
                "available",
                0.62,
                62,
                100,
                OffsetDateTime.parse("2030-01-01T05:00:00Z"),
                0.18,
                126,
                700,
                OffsetDateTime.parse("2030-01-06T00:00:00Z"),
                OffsetDateTime.parse("2026-06-23T09:05:00Z")));
    assertThat(createdAt).isNotNull();

    ProviderUsageSnapshotView view = readPort.findLatestByWorkflowRunId(runId).orElseThrow();
    assertThat(view.publicId()).isEqualTo(publicId);
    assertThat(view.signalState()).isEqualTo("available");
    assertThat(view.accountReference()).isEqualTo("claude:oauth");
    assertThat(view.fiveHour().used()).isEqualTo(62);
    assertThat(view.fiveHour().limit()).isEqualTo(100);
    assertThat(view.fiveHour().usedFraction()).isEqualTo(0.62);
    assertThat(view.weekly().used()).isEqualTo(126);
    assertThat(view.asOf()).isEqualTo(OffsetDateTime.parse("2026-06-23T09:05:00Z"));
  }

  @Test
  void notExposedSnapshotPersistsNullWindowsAndIsReadBackAsEmpty() {
    String runId = PublicIdPrefixes.WORKFLOW_RUN.next();
    writePort.insert(
        new NewProviderUsageSnapshot(
            "pul_itne" + suffix(),
            runId,
            null,
            "codex:subscription",
            "not_exposed",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null));

    ProviderUsageSnapshotView view = readPort.findLatestByWorkflowRunId(runId).orElseThrow();
    assertThat(view.signalState()).isEqualTo("not_exposed");
    assertThat(view.accountReference()).isEqualTo("codex:subscription");
    assertThat(view.fiveHour().isEmpty()).isTrue();
    assertThat(view.weekly().isEmpty()).isTrue();
    assertThat(view.asOf()).isNull();
  }

  @Test
  void findLatestReturnsTheMostRecentSnapshotForTheRun() {
    String runId = PublicIdPrefixes.WORKFLOW_RUN.next();
    writePort.insert(snapshot("pul_itl1" + suffix(), runId, "claude:oauth", "not_exposed"));
    String latestId = "pul_itl2" + suffix();
    writePort.insert(snapshot(latestId, runId, "claude:api", "available"));

    ProviderUsageSnapshotView view = readPort.findLatestByWorkflowRunId(runId).orElseThrow();
    assertThat(view.publicId()).isEqualTo(latestId);
    assertThat(view.accountReference()).isEqualTo("claude:api");
  }

  @Test
  void perCredentialAttributionIsTheNonSecretLabelAndNoSecretColumnExists() {
    String runId = PublicIdPrefixes.WORKFLOW_RUN.next();
    String publicId = "pul_itns" + suffix();
    writePort.insert(snapshot(publicId, runId, "claude:oauth", "available"));

    Map<String, Object> row =
        jdbcTemplate.queryForMap(
            "select * from provider_usage_snapshots where public_id = ?", publicId);
    // Attribution is the non-secret account label (Trap T1) — never a project_credentials FK.
    assertThat(row.get("account_reference")).isEqualTo("claude:oauth");
    assertThat(row).doesNotContainKeys("token", "secret", "api_key", "credential_id");
    // No column value carries secret-shaped material — only the non-secret label + numbers.
    assertThat(row.values()).noneMatch(v -> v != null && v.toString().contains("ghp_"));
  }

  @Test
  void countActiveBySignalStateReflectsInsertedRows() {
    SignalStateCounts before = readPort.countActiveBySignalState();
    String runId = PublicIdPrefixes.WORKFLOW_RUN.next();
    writePort.insert(snapshot("pul_itc1" + suffix(), runId, "claude:oauth", "available"));
    writePort.insert(snapshot("pul_itc2" + suffix(), runId, "codex:api", "not_exposed"));

    SignalStateCounts after = readPort.countActiveBySignalState();
    assertThat(after.available()).isEqualTo(before.available() + 1);
    assertThat(after.notExposed()).isEqualTo(before.notExposed() + 1);
  }

  private static NewProviderUsageSnapshot snapshot(
      String publicId, String runId, String accountLabel, String signalState) {
    boolean available = "available".equals(signalState);
    return new NewProviderUsageSnapshot(
        publicId,
        runId,
        "rex_" + suffix(),
        accountLabel,
        signalState,
        available ? 0.5 : null,
        available ? 50 : null,
        available ? 100 : null,
        available ? OffsetDateTime.now(ZoneOffset.UTC) : null,
        null,
        null,
        null,
        null,
        available ? OffsetDateTime.now(ZoneOffset.UTC) : null);
  }

  private static String suffix() {
    return Long.toHexString(System.nanoTime());
  }
}
