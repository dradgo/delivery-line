package org.dradgo.adapters.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.dradgo.TestcontainersConfiguration;
import org.dradgo.application.workflow.WorkflowInspectionService;
import org.dradgo.application.workflow.WorkflowInspectionService.OperatorRunFilter;
import org.dradgo.application.workflow.WorkflowInspectionService.OperatorRunRow;
import org.dradgo.application.workflow.WorkflowInspectionService.OperatorRunSummary;
import org.dradgo.domain.id.PublicIdPrefixes;
import org.dradgo.domain.registry.FailureCategory;
import org.dradgo.domain.registry.WorkflowState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Story 4.1 (AC2/AC6/AC10) — real-Postgres coverage for {@code
 * WorkflowInspectionService.getOperatorRunSummary} + the {@code OperatorRunPersistenceAdapter} join
 * query: per-token predicate selection, {@code --since} window, histograms over the FULL matching
 * set, {@code oldestEntryAt}, {@code --limit} caps {@code runs[]} but not the histograms, {@code
 * lastTransitionAt DESC} ordering, typed integration-link resolution, and a ~1000-run ≤5s perf
 * assertion (NFR26 extended). The correlated Failed-transition lateral and the {@code
 * intervention_marker} predicate only exercise on real PG. Named {@code *IT} so Failsafe runs it (a
 * {@code *Test} name leaks into Windows Surefire). Not {@code @Transactional} — seeds via
 * auto-committed JDBC and cleaned in {@code @AfterEach} (mirror {@code
 * RunnerExecutionTokenUsagePersistenceIT}), so the read tx sees committed rows and {@code now()}
 * math is realistic.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles({"test", "linear-mock"})
@Tag("integration")
class WorkflowInspectionOperatorStatusIT {

  private static final OffsetDateTime NOW = OffsetDateTime.now(ZoneOffset.UTC);

  @Autowired private WorkflowInspectionService inspection;
  @Autowired private JdbcTemplate jdbcTemplate;

  @AfterEach
  void tearDown() {
    jdbcTemplate.update("delete from integration_links");
    jdbcTemplate.update("delete from workflow_events");
    jdbcTemplate.update("delete from workflow_runs");
  }

  @Test
  void eachTokenSelectsOnlyItsMatchingRuns() {
    String failed = seedRun("Failed");
    seedTransition(failed, "Executing", "Failed", "runner_timeout", false, NOW.minusMinutes(5));
    String orphan = seedRun("Failed");
    seedTransition(orphan, "Executing", "Failed", "orphan", false, NOW.minusMinutes(5));
    String taken = seedRun("TakenOver");
    seedTransition(taken, "WaitingForReview", "TakenOver", null, false, NOW.minusMinutes(5));
    String stalled = seedRun("Executing");
    seedTransition(stalled, "Investigating", "Executing", null, false, NOW.minusDays(90));
    String overridden = seedRun("Planned");
    seedTransition(overridden, "Inbox", "Planned", null, true, NOW.minusMinutes(5));
    String activeRecent = seedRun("Executing");
    seedTransition(activeRecent, "Investigating", "Executing", null, false, NOW.minusSeconds(30));
    String completed = seedRun("Completed");
    seedTransition(completed, "WaitingForReview", "Completed", null, false, NOW.minusMinutes(5));

    // failed → both Failed-state runs (orphaned ⊇ failed on state)
    OperatorRunSummary failedOnly = summary("failed", null, 100);
    assertThat(runIds(failedOnly)).containsExactlyInAnyOrder(failed, orphan);
    assertThat(failedOnly.byState()).containsEntry(WorkflowState.FAILED, 2);

    // orphaned → only the orphan-category run
    OperatorRunSummary orphanedOnly = summary("orphaned", null, 100);
    assertThat(runIds(orphanedOnly)).containsExactly(orphan);
    assertThat(orphanedOnly.byFailureCategory()).containsEntry(FailureCategory.ORPHAN, 1);

    assertThat(runIds(summary("takenover", null, 100))).containsExactly(taken);
    assertThat(runIds(summary("stalled", null, 100))).containsExactly(stalled);
    assertThat(runIds(summary("overridden", null, 100))).containsExactly(overridden);

    // default {failed,stalled,orphaned} — the UNION, deduped; active-recent + completed excluded
    OperatorRunSummary defaults = summary("failed,stalled,orphaned", null, 100);
    assertThat(runIds(defaults)).containsExactlyInAnyOrder(failed, orphan, stalled);
  }

  @Test
  void failureHistogramExcludesHistoricalCategoryOfNonFailedRunsAndSignifierMatchesCurrentState() {
    // review 4.1 (#2): a run that was Failed(orphan) then taken over carries a historical
    // failure_category, but must NOT inflate byFailureCategory (which characterizes the CURRENTLY
    // Failed fleet). review 4.1 (#1): its server-derived signifier is TAKENOVER, not ORPHANED.
    String stillFailed = seedRun("Failed");
    seedTransition(stillFailed, "Executing", "Failed", "orphan", false, NOW.minusMinutes(10));
    String takenAfterFail = seedRun("TakenOver");
    seedTransition(takenAfterFail, "Executing", "Failed", "orphan", false, NOW.minusMinutes(20));
    seedTransition(takenAfterFail, "Failed", "TakenOver", null, false, NOW.minusMinutes(5));

    OperatorRunSummary result = summary("failed,orphaned,takenover", null, 100);
    assertThat(runIds(result)).containsExactlyInAnyOrder(stillFailed, takenAfterFail);
    // Only the currently-Failed run contributes to the failure histogram.
    assertThat(result.byFailureCategory()).containsEntry(FailureCategory.ORPHAN, 1).hasSize(1);

    OperatorRunRow takenRow =
        result.runs().stream()
            .filter(r -> r.runId().equals(takenAfterFail))
            .findFirst()
            .orElseThrow();
    assertThat(takenRow.operatorSignifier()).isEqualTo("TAKENOVER");
    // The row still surfaces its historical category (row-level contract unchanged).
    assertThat(takenRow.failureCategory()).isEqualTo("orphan");
  }

  @Test
  void signifierDistinguishesOverriddenAndStalledActiveRuns() {
    // review 4.1 (#1): both are active-state runs, but the signifier separates them so the renderer
    // no longer mislabels an overridden run as STALLED.
    String stalled = seedRun("Executing");
    seedTransition(stalled, "Investigating", "Executing", null, false, NOW.minusDays(90));
    String overridden = seedRun("Executing");
    seedTransition(overridden, "Investigating", "Executing", null, true, NOW.minusSeconds(30));

    OperatorRunSummary result = summary("stalled,overridden", null, 100);
    assertThat(signifierOf(result, stalled)).isEqualTo("STALLED");
    assertThat(signifierOf(result, overridden)).isEqualTo("OVERRIDDEN");
  }

  private static String signifierOf(OperatorRunSummary summary, String runId) {
    return summary.runs().stream()
        .filter(r -> r.runId().equals(runId))
        .map(OperatorRunRow::operatorSignifier)
        .findFirst()
        .orElseThrow();
  }

  @Test
  void sinceWindowFiltersByRecentTransition() {
    String recent = seedRun("Failed");
    seedTransition(recent, "Executing", "Failed", null, false, NOW.minusMinutes(10));
    String old = seedRun("Failed");
    seedTransition(old, "Executing", "Failed", null, false, NOW.minusHours(2));

    assertThat(runIds(summary("failed", "1h", 100))).containsExactly(recent);
    assertThat(runIds(summary("failed", null, 100))).containsExactlyInAnyOrder(recent, old);
  }

  @Test
  void rowsAreOrderedByLastTransitionDescending() {
    String older = seedRun("Failed");
    seedTransition(older, "Executing", "Failed", null, false, NOW.minusMinutes(30));
    String newer = seedRun("Failed");
    seedTransition(newer, "Executing", "Failed", null, false, NOW.minusMinutes(5));

    List<String> ordered = runIds(summary("failed", null, 100));
    assertThat(ordered).containsExactly(newer, older);
  }

  @Test
  void limitCapsRowsButNotHistogramsOrTotal() {
    for (int i = 0; i < 3; i++) {
      String run = seedRun("Failed");
      seedTransition(run, "Executing", "Failed", null, false, NOW.minusMinutes(i + 1));
    }

    OperatorRunSummary limited = summary("failed", null, 2);
    assertThat(limited.runs()).hasSize(2);
    assertThat(limited.total()).isEqualTo(3);
    assertThat(limited.byState()).containsEntry(WorkflowState.FAILED, 3);
  }

  @Test
  void oldestEntryAtIsMinEventTimeAcrossMatchedRuns() {
    String run = seedRun("Failed");
    OffsetDateTime oldest = NOW.minusDays(3).withNano(0);
    seedTransition(run, null, "Investigating", null, false, oldest);
    seedTransition(run, "Executing", "Failed", null, false, NOW.minusMinutes(5).withNano(0));

    OperatorRunSummary result = summary("failed", null, 100);
    assertThat(result.oldestEntryAt()).isNotNull();
    assertThat(result.oldestEntryAt().toInstant()).isEqualTo(oldest.toInstant());
  }

  @Test
  void typedIntegrationLinksResolveTicketAndPrAndIgnoreSuperseded() {
    String run = seedRun("Failed");
    seedTransition(run, "Executing", "Failed", null, false, NOW.minusMinutes(5));
    seedLink(run, "linear", "LIN-101", "linked");
    seedLink(run, "github_pr", "octo/repo#7", "synced");
    seedLink(run, "linear", "LIN-OLD", "superseded");

    OperatorRunRow row = summary("failed", null, 100).runs().get(0);
    assertThat(row.linkedTicketRef()).isEqualTo("LIN-101");
    assertThat(row.linkedPrRef()).isEqualTo("octo/repo#7");
    assertThat(row.actorIdentity()).isEqualTo("system");
    assertThat(row.escalationMarker()).isFalse();
  }

  @Test
  void archivedRunsAreExcluded() {
    String live = seedRun("Failed");
    seedTransition(live, "Executing", "Failed", null, false, NOW.minusMinutes(5));
    String archived = seedRunArchived("Failed", NOW.minusMinutes(1));
    seedTransition(archived, "Executing", "Failed", null, false, NOW.minusMinutes(5));

    assertThat(runIds(summary("failed", null, 100))).containsExactly(live);
  }

  @Test
  void thousandRunFleetResolvesWithinFiveSeconds() {
    jdbcTemplate.update(
        """
        insert into workflow_runs (public_id, current_state)
        select 'run_perf' || lpad(g::text, 6, '0'),
               (array['Failed','TakenOver','Executing','Investigating'])[1 + (g % 4)]
          from generate_series(1, 1000) g
        """);
    jdbcTemplate.update(
        """
        insert into workflow_events
          (public_id, workflow_run_id, event_type, prior_state, resulting_state,
           actor_identity, actor_type, failure_category, intervention_marker, created_at)
        select 'evt_perf' || lpad(g::text, 6, '0'),
               r.id, 'workflow.stateChanged', 'Executing', r.current_state,
               'system', 'system',
               case when r.current_state = 'Failed' then 'orphan' else null end,
               false, now() - interval '90 days'
          from workflow_runs r
          join generate_series(1, 1000) g
            on r.public_id = 'run_perf' || lpad(g::text, 6, '0')
        """);

    long start = System.nanoTime();
    OperatorRunSummary result = summary("failed,stalled,orphaned,takenover", null, 500);
    long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

    assertThat(result.total()).isEqualTo(1000);
    assertThat(result.runs()).hasSize(500); // limit caps the page, not the total
    assertThat(elapsedMs).as("1000-run fleet query wall-clock").isLessThan(5000L);
  }

  private OperatorRunSummary summary(String states, String since, int limit) {
    return inspection.getOperatorRunSummary(
        new OperatorRunFilter(splitStates(states), since, limit));
  }

  private static List<String> splitStates(String states) {
    return List.of(states.split(","));
  }

  private static List<String> runIds(OperatorRunSummary summary) {
    return summary.runs().stream().map(OperatorRunRow::runId).toList();
  }

  private String seedRun(String state) {
    String runId = PublicIdPrefixes.WORKFLOW_RUN.next();
    jdbcTemplate.update(
        "insert into workflow_runs (public_id, current_state) values (?, ?)", runId, state);
    return runId;
  }

  private String seedRunArchived(String state, OffsetDateTime archivedAt) {
    String runId = PublicIdPrefixes.WORKFLOW_RUN.next();
    jdbcTemplate.update(
        "insert into workflow_runs (public_id, current_state, archived_at) values (?, ?, ?)",
        runId,
        state,
        archivedAt);
    return runId;
  }

  private void seedTransition(
      String runId,
      String priorState,
      String resultingState,
      String failureCategory,
      boolean intervention,
      OffsetDateTime createdAt) {
    jdbcTemplate.update(
        """
        insert into workflow_events
          (public_id, workflow_run_id, event_type, prior_state, resulting_state,
           actor_identity, actor_type, failure_category, intervention_marker, created_at)
        values (?, (select id from workflow_runs where public_id = ?),
                'workflow.stateChanged', ?, ?, 'system', 'system', ?, ?, ?)
        """,
        PublicIdPrefixes.WORKFLOW_EVENT.next(),
        runId,
        priorState,
        resultingState,
        failureCategory,
        intervention,
        createdAt);
  }

  private void seedLink(String runId, String type, String ref, String syncStatus) {
    jdbcTemplate.update(
        """
        insert into integration_links
          (public_id, workflow_run_id, integration_type, external_ref, sync_status)
        values (?, (select id from workflow_runs where public_id = ?), ?, ?, ?)
        """,
        PublicIdPrefixes.INTEGRATION_LINK.next(),
        runId,
        type,
        ref,
        syncStatus);
  }
}
