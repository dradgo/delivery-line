package org.dradgo.application.integration.conflict;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.dradgo.TestcontainersConfiguration;
import org.dradgo.application.integration.conflict.IntegrationConflictService.ConflictListResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Story 4.18 (AC2/AC10) — real-Postgres coverage of the NEW keyset-paginated conflict read path:
 * three-valued {@code resolved} filter, {@code (detected_at, id)} cursor pagination, the global
 * unresolved/resolved counts + breakdowns, and {@code unresolvedCountByRun}. These only exercise on
 * PG (the cursor subquery, the {@code resolved} boolean casts, {@code make_interval}).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles({"test", "linear-mock"})
@Tag("integration")
class IntegrationConflictListReadIT {

  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private IntegrationConflictService conflictService;

  private static final String RUN = "run_lreadit0001";
  private static final String GH_LINK = "ilk_lread_gh01";
  private static final String LIN_LINK = "ilk_lread_lin1";

  @AfterEach
  void cleanUp() {
    jdbcTemplate.update("delete from integration_conflicts where workflow_run_id = ?", RUN);
    jdbcTemplate.update(
        "delete from integration_links where public_id in (?, ?)", GH_LINK, LIN_LINK);
    jdbcTemplate.update("delete from workflow_runs where public_id = ?", RUN);
  }

  @Test
  void listPaginatesFiltersByResolvedAndReportsCounts() {
    seed();

    // resolved=false → the three unresolved conflicts, newest-first (c3, c2, c1).
    ConflictListResult unresolved = conflictService.listConflicts(filter(false, 50, null));
    assertThat(unresolved.conflicts()).hasSize(3);
    assertThat(unresolved.conflicts().get(0).conflictId()).isEqualTo("icf_lread0003");
    assertThat(unresolved.conflicts().get(2).conflictId()).isEqualTo("icf_lread0001");
    assertThat(unresolved.totalUnresolved()).isEqualTo(3);
    assertThat(unresolved.totalResolved()).isEqualTo(1);
    assertThat(unresolved.totalUnresolvedByCategory())
        .containsEntry("external_state_advanced", 1L)
        .containsEntry("metadata_drift", 1L)
        .containsEntry("link_broken", 1L);
    assertThat(unresolved.totalUnresolvedByIntegration())
        .containsEntry("github", 2L)
        .containsEntry("linear", 1L);
    assertThat(unresolved.nextCursor()).isNull();

    // resolved=true → only the one resolved conflict.
    ConflictListResult resolved = conflictService.listConflicts(filter(true, 50, null));
    assertThat(resolved.conflicts()).hasSize(1);
    assertThat(resolved.conflicts().get(0).conflictId()).isEqualTo("icf_lread0004");

    // resolved=null → both resolved + unresolved (4 total).
    ConflictListResult both = conflictService.listConflicts(filter(null, 50, null));
    assertThat(both.conflicts()).hasSize(4);

    // Keyset pagination over the unresolved set: page 1 (limit 2) → c3,c2 + nextCursor.
    ConflictListResult page1 = conflictService.listConflicts(filter(false, 2, null));
    assertThat(page1.conflicts()).hasSize(2);
    assertThat(page1.conflicts().get(0).conflictId()).isEqualTo("icf_lread0003");
    assertThat(page1.conflicts().get(1).conflictId()).isEqualTo("icf_lread0002");
    assertThat(page1.nextCursor()).isNotNull();

    // page 2 (cursor) → c1, no further pages.
    ConflictListResult page2 = conflictService.listConflicts(filter(false, 2, page1.nextCursor()));
    assertThat(page2.conflicts()).hasSize(1);
    assertThat(page2.conflicts().get(0).conflictId()).isEqualTo("icf_lread0001");
    assertThat(page2.nextCursor()).isNull();

    // Batch per-run unresolved count.
    assertThat(conflictService.unresolvedCountByRun(List.of(RUN))).containsEntry(RUN, 3);
  }

  private static ConflictFilter filter(Boolean resolved, int limit, String cursor) {
    return new ConflictFilter(null, null, null, RUN, null, resolved, limit, cursor);
  }

  private void seed() {
    jdbcTemplate.update(
        "insert into workflow_runs (public_id, current_state) values (?, 'WaitingForReview')", RUN);
    seedLink(GH_LINK, "github_pr", "octo/repo#900");
    seedLink(LIN_LINK, "linear", "LIN-900");
    Instant base = Instant.parse("2026-07-14T10:00:00Z");
    // c1 (oldest) .. c3 (newest) — all unresolved, distinct categories/links.
    seedConflict("icf_lread0001", GH_LINK, "external_state_advanced", base, null);
    seedConflict("icf_lread0002", GH_LINK, "metadata_drift", base.plusSeconds(60), null);
    seedConflict("icf_lread0003", LIN_LINK, "link_broken", base.plusSeconds(120), null);
    // c4 — resolved (resolved_at set; resolved_by_action_id nullable).
    seedConflict(
        "icf_lread0004",
        GH_LINK,
        "external_state_reverted",
        base.plusSeconds(30),
        base.plusSeconds(200));
  }

  private void seedLink(String publicId, String integrationType, String externalRef) {
    Long runId =
        jdbcTemplate.queryForObject(
            "select id from workflow_runs where public_id = ?", Long.class, RUN);
    jdbcTemplate.update(
        "insert into integration_links"
            + " (public_id, workflow_run_id, integration_type, external_ref, external_metadata,"
            + " sync_status) values (?, ?, ?, ?, cast('{}' as jsonb), 'synced')",
        publicId,
        runId,
        integrationType,
        externalRef);
  }

  private void seedConflict(
      String publicId, String link, String category, Instant detectedAt, Instant resolvedAt) {
    jdbcTemplate.update(
        "insert into integration_conflicts"
            + " (public_id, integration_link_id, workflow_run_id, conflict_category, detected_at,"
            + " resolved_at) values (?, ?, ?, ?, ?, ?)",
        publicId,
        link,
        RUN,
        category,
        Timestamp.from(detectedAt),
        resolvedAt == null ? null : Timestamp.from(resolvedAt));
  }
}
