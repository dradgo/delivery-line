package org.dradgo.application.integration.conflict;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.dradgo.TestcontainersConfiguration;
import org.dradgo.application.integration.conflict.IntegrationConflictTerminalRunReconciliationSweepService.SweepResult;
import org.dradgo.application.integration.conflict.spi.IntegrationConflictReadPort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Story 4.30 (AC2/AC3/AC6) — real-Postgres coverage of the terminal-run reconciliation sweep: a
 * conflict stranded on a terminalized ({@code Reconciled}) run is SYSTEM-resolved (resolved_at +
 * resolved_by_action_id set, a system-actor recovery_actions row + a RECOVERY_RECONCILED event
 * written) and thereafter excluded from {@code findUnresolvedConflictsOnTerminalRuns}; a conflict
 * on a NON-terminal run is left untouched. Named {@code *IT} so it runs under Failsafe
 * (Testcontainers), never the no-Docker Surefire tier
 * ([[springboot-testcontainers-test-must-be-IT]]).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles("test")
@Tag("integration")
class IntegrationConflictTerminalRunSweepIT {

  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private IntegrationConflictTerminalRunReconciliationSweepService sweepService;
  @Autowired private IntegrationConflictReadPort readPort;

  private final List<String> seededRuns = new java.util.ArrayList<>();
  private final List<String> seededLinks = new java.util.ArrayList<>();

  @AfterEach
  void cleanUp() {
    // FK order: conflicts (→ recovery_actions/links/runs) → recovery_actions (→ events/runs) →
    // events → links → runs.
    for (String link : seededLinks) {
      jdbcTemplate.update("delete from integration_conflicts where integration_link_id = ?", link);
    }
    for (String run : seededRuns) {
      jdbcTemplate.update(
          "delete from recovery_actions where workflow_run_id = "
              + "(select id from workflow_runs where public_id = ?)",
          run);
      jdbcTemplate.update(
          "delete from workflow_events where workflow_run_id = "
              + "(select id from workflow_runs where public_id = ?)",
          run);
    }
    for (String link : seededLinks) {
      jdbcTemplate.update("delete from integration_links where public_id = ?", link);
    }
    for (String run : seededRuns) {
      jdbcTemplate.update("delete from workflow_runs where public_id = ?", run);
    }
    seededRuns.clear();
    seededLinks.clear();
  }

  @Test
  void clearsConflictStrandedOnTerminalRunAndExcludesItThereafter() {
    String run = seedRun("Reconciled");
    String link = seedLink(run, "octo/repo#4301");
    String conflict = seedConflict(run, link, "external_state_advanced");

    // Precondition: the read query surfaces the strand.
    assertThat(readPort.findUnresolvedConflictsOnTerminalRuns(100))
        .anyMatch(c -> c.conflictId().equals(conflict));

    SweepResult result = sweepService.sweep();

    assertThat(result.found()).isGreaterThanOrEqualTo(1);
    assertThat(result.cleared()).isGreaterThanOrEqualTo(1);

    // AC2 — the row is resolved and the read query no longer returns it.
    assertThat(resolvedAt(conflict)).isNotNull();
    assertThat(resolvedByActionId(conflict)).isNotNull();
    assertThat(readPort.findUnresolvedConflictsOnTerminalRuns(100))
        .noneMatch(c -> c.conflictId().equals(conflict));

    // AC3 — a SYSTEM-actor recovery_actions row (reconcile / system / succeeded / reviewer=system)
    // AND a RECOVERY_RECONCILED audit event exist for the run.
    assertThat(systemReconcileActionCount(run)).isEqualTo(1);
    assertThat(recoveryReconciledEventCount(run)).isEqualTo(1);
    // The resolved_by_action_id points at the SYSTEM recovery action.
    assertThat(resolvedByActionId(conflict)).isEqualTo(latestReconcileActionId(run));
  }

  @Test
  void leavesConflictOnNonTerminalRunUntouched() {
    String run = seedRun("WaitingForReview");
    String link = seedLink(run, "octo/repo#4302");
    String conflict = seedConflict(run, link, "metadata_drift");

    // The read query never surfaces a non-terminal-run conflict.
    assertThat(readPort.findUnresolvedConflictsOnTerminalRuns(100))
        .noneMatch(c -> c.conflictId().equals(conflict));

    sweepService.sweep();

    // The row is still unresolved; no system action / recovery event was written.
    assertThat(resolvedAt(conflict)).isNull();
    assertThat(systemReconcileActionCount(run)).isZero();
    assertThat(recoveryReconciledEventCount(run)).isZero();
  }

  // ---- seeding + assertion helpers --------------------------------------------------------------

  private String seedRun(String state) {
    String run =
        "run_trs" + Integer.toHexString(System.identityHashCode(new Object())) + seededRuns.size();
    jdbcTemplate.update(
        "insert into workflow_runs (public_id, current_state) values (?, ?)", run, state);
    seededRuns.add(run);
    return run;
  }

  private String seedLink(String run, String externalRef) {
    Long runId =
        jdbcTemplate.queryForObject(
            "select id from workflow_runs where public_id = ?", Long.class, run);
    String link = "ilk_trs" + seededLinks.size() + Integer.toHexString(externalRef.hashCode());
    jdbcTemplate.update(
        "insert into integration_links"
            + " (public_id, workflow_run_id, integration_type, external_ref, external_metadata,"
            + " sync_status) values (?, ?, 'github_pr', ?, cast(? as jsonb), 'synced')",
        link,
        runId,
        externalRef,
        "{\"prState\":\"open\"}");
    seededLinks.add(link);
    return link;
  }

  private String seedConflict(String run, String link, String category) {
    String conflict = "icf_trs" + seededLinks.size() + Integer.toHexString(link.hashCode());
    jdbcTemplate.update(
        "insert into integration_conflicts"
            + " (public_id, integration_link_id, workflow_run_id, conflict_category)"
            + " values (?, ?, ?, ?)",
        conflict,
        link,
        run,
        category);
    return conflict;
  }

  private java.sql.Timestamp resolvedAt(String conflict) {
    return jdbcTemplate.queryForObject(
        "select resolved_at from integration_conflicts where public_id = ?",
        java.sql.Timestamp.class,
        conflict);
  }

  private String resolvedByActionId(String conflict) {
    return jdbcTemplate.queryForObject(
        "select resolved_by_action_id from integration_conflicts where public_id = ?",
        String.class,
        conflict);
  }

  private int systemReconcileActionCount(String run) {
    Integer count =
        jdbcTemplate.queryForObject(
            "select count(*) from recovery_actions"
                + " where workflow_run_id = (select id from workflow_runs where public_id = ?)"
                + "   and action_type = 'reconcile' and actor_type = 'system'"
                + "   and reviewer_role = 'system' and result_status = 'succeeded'",
            Integer.class,
            run);
    return count == null ? 0 : count;
  }

  private String latestReconcileActionId(String run) {
    return jdbcTemplate.queryForObject(
        "select public_id from recovery_actions"
            + " where workflow_run_id = (select id from workflow_runs where public_id = ?)"
            + "   and action_type = 'reconcile' and actor_type = 'system'"
            + " order by id desc limit 1",
        String.class,
        run);
  }

  private int recoveryReconciledEventCount(String run) {
    Integer count =
        jdbcTemplate.queryForObject(
            "select count(*) from workflow_events"
                + " where workflow_run_id = (select id from workflow_runs where public_id = ?)"
                + "   and event_type = 'recovery.reconciled'",
            Integer.class,
            run);
    return count == null ? 0 : count;
  }
}
