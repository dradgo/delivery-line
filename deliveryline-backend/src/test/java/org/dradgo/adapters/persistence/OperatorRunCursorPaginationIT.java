package org.dradgo.adapters.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import org.dradgo.TestcontainersConfiguration;
import org.dradgo.application.workflow.WorkflowInspectionService;
import org.dradgo.application.workflow.WorkflowInspectionService.OperatorRunFilter;
import org.dradgo.application.workflow.WorkflowInspectionService.OperatorRunRow;
import org.dradgo.application.workflow.WorkflowInspectionService.OperatorRunSummary;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.id.PublicIdPrefixes;
import org.dradgo.domain.registry.DomainErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Story 4.2 (AC5/AC2/AC3/AC10) — real-Postgres coverage for keyset cursor pagination, the
 * runner-kind read-model join ({@code projects.runner_kind}), and the runner-kind /
 * failure-category filter predicates on {@code WorkflowInspectionService.getOperatorRunSummary}.
 * The keyset walk (including the {@code nulls last} tail for runs with no events) and the {@code
 * left join projects} only exercise on real PG. Named {@code *IT} so Failsafe runs it (a {@code
 * *Test} name leaks into Windows Surefire). Not {@code @Transactional} — seeds via auto-committed
 * JDBC and cleaned in {@code @AfterEach} so the read tx sees committed rows and {@code now()} math
 * is realistic.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles({"test", "linear-mock"})
@Tag("integration")
@ExtendWith(OutputCaptureExtension.class)
class OperatorRunCursorPaginationIT {

  private static final OffsetDateTime NOW = OffsetDateTime.now(ZoneOffset.UTC);
  private static final String PROJECT_ID = "prj_opcursor01";

  @Autowired private WorkflowInspectionService inspection;
  @Autowired private JdbcTemplate jdbcTemplate;

  @AfterEach
  void tearDown() {
    jdbcTemplate.update("delete from integration_links");
    jdbcTemplate.update("delete from workflow_events");
    jdbcTemplate.update(
        "delete from workflow_runs where project_id is null or project_id = ?", PROJECT_ID);
    jdbcTemplate.update("delete from projects where slug like 'op-cursor-%'");
  }

  @Test
  void cursorWalkReturnsEveryRunExactlyOnceInStableOrderIncludingNullTail() {
    // Five Failed runs with events (non-null last_transition_at, distinct times) + two Failed runs
    // with NO events (null last_transition_at → sorted into the `nulls last` tail). Paging with a
    // small page size crosses the non-null→null boundary mid-page.
    for (int i = 1; i <= 5; i++) {
      String run = seedRun("Failed", null);
      seedTransition(run, "Executing", "Failed", null, false, NOW.minusMinutes(i));
    }
    seedRun("Failed", null); // no events → null last_transition_at
    seedRun("Failed", null); // no events → null last_transition_at

    // Full single-query order is the deterministic reference (last_transition_at desc nulls last,
    // run_id desc). A correct keyset cursor must reproduce it EXACTLY across pages.
    List<String> reference =
        inspection
            .getOperatorRunSummary(new OperatorRunFilter(List.of("failed"), null, 500))
            .runs()
            .stream()
            .map(OperatorRunRow::runId)
            .toList();
    assertThat(reference).hasSize(7);

    List<String> paged = pageAllRunIds("failed", 3);
    assertThat(paged)
        .as("cursor walk must return the same runs in the same order as a single-page scan")
        .containsExactlyElementsOf(reference);
    assertThat(new HashSet<>(paged)).as("no run duplicated across pages").hasSize(7);
  }

  @Test
  void lastPageCarriesNoNextCursor() {
    for (int i = 1; i <= 4; i++) {
      String run = seedRun("Failed", null);
      seedTransition(run, "Executing", "Failed", null, false, NOW.minusMinutes(i));
    }
    OperatorRunSummary lastPage =
        inspection.getOperatorRunSummary(new OperatorRunFilter(List.of("failed"), null, 10));
    assertThat(lastPage.runs()).hasSize(4);
    assertThat(lastPage.nextCursor()).isNull();

    OperatorRunSummary firstOfTwo =
        inspection.getOperatorRunSummary(new OperatorRunFilter(List.of("failed"), null, 2));
    assertThat(firstOfTwo.runs()).hasSize(2);
    assertThat(firstOfTwo.nextCursor()).isNotNull();
  }

  @Test
  void aggregateStaysStableAcrossPages() {
    for (int i = 1; i <= 4; i++) {
      String run = seedRun("Failed", null);
      seedTransition(run, "Executing", "Failed", null, false, NOW.minusMinutes(i));
    }
    OperatorRunSummary page1 =
        inspection.getOperatorRunSummary(new OperatorRunFilter(List.of("failed"), null, 2));
    OperatorRunSummary page2 =
        inspection.getOperatorRunSummary(
            new OperatorRunFilter(
                List.of("failed"), null, 2, List.of(), List.of(), page1.nextCursor()));
    // The aggregate is computed over the full match set independent of the cursor.
    assertThat(page1.total()).isEqualTo(4);
    assertThat(page2.total()).isEqualTo(4);
  }

  @Test
  void runnerKindIsSourcedFromProjectOverrideAndNullWhenNoProject() {
    seedProject(PROJECT_ID, "op-cursor-claude", "claude");
    String linked = seedRun("Failed", PROJECT_ID);
    seedTransition(linked, "Executing", "Failed", null, false, NOW.minusMinutes(1));
    String unlinked = seedRun("Failed", null);
    seedTransition(unlinked, "Executing", "Failed", null, false, NOW.minusMinutes(2));

    OperatorRunSummary result =
        inspection.getOperatorRunSummary(new OperatorRunFilter(List.of("failed"), null, 100));
    assertThat(rowOf(result, linked).runnerKind()).isEqualTo("claude");
    assertThat(rowOf(result, unlinked).runnerKind()).isNull();
  }

  @Test
  void runnerKindFilterNarrowsToMatchingProjectRuns() {
    seedProject(PROJECT_ID, "op-cursor-claude", "claude");
    String claudeRun = seedRun("Failed", PROJECT_ID);
    seedTransition(claudeRun, "Executing", "Failed", null, false, NOW.minusMinutes(1));
    String noProjectRun = seedRun("Failed", null);
    seedTransition(noProjectRun, "Executing", "Failed", null, false, NOW.minusMinutes(2));

    OperatorRunSummary claudeOnly =
        inspection.getOperatorRunSummary(
            new OperatorRunFilter(
                List.of("failed"), null, 100, List.of("claude"), List.of(), null));
    assertThat(runIds(claudeOnly)).containsExactly(claudeRun);
    assertThat(claudeOnly.total()).isEqualTo(1);

    OperatorRunSummary codexOnly =
        inspection.getOperatorRunSummary(
            new OperatorRunFilter(List.of("failed"), null, 100, List.of("codex"), List.of(), null));
    assertThat(runIds(codexOnly)).isEmpty();
  }

  @Test
  void failureCategoryFilterNarrowsToMatchingRuns() {
    String orphan = seedRun("Failed", null);
    seedTransition(orphan, "Executing", "Failed", "orphan", false, NOW.minusMinutes(1));
    String timeout = seedRun("Failed", null);
    seedTransition(timeout, "Executing", "Failed", "runner_timeout", false, NOW.minusMinutes(2));

    OperatorRunSummary orphanOnly =
        inspection.getOperatorRunSummary(
            new OperatorRunFilter(
                List.of("failed"), null, 100, List.of(), List.of("orphan"), null));
    assertThat(runIds(orphanOnly)).containsExactly(orphan);
  }

  @Test
  void completionLogEmittedOnSuccess(CapturedOutput output) {
    String run = seedRun("Failed", null);
    seedTransition(run, "Executing", "Failed", null, false, NOW.minusMinutes(1));

    inspection.getOperatorRunSummary(new OperatorRunFilter(List.of("failed"), null, 100));

    assertThat(output.getOut()).contains("getOperatorRunSummary success");
  }

  @Test
  void malformedCursorRaisesInvalidCommandPayloadAndWarns(CapturedOutput output) {
    assertThatThrownBy(
            () ->
                inspection.getOperatorRunSummary(
                    new OperatorRunFilter(
                        List.of("failed"), null, 100, List.of(), List.of(), "!!!not-base64!!!")))
        .isInstanceOf(DomainException.class)
        .extracting(e -> ((DomainException) e).errorCode())
        .isEqualTo(DomainErrorCode.INVALID_COMMAND_PAYLOAD);
    assertThat(output.getOut()).contains("invalid cursor");
  }

  private List<String> pageAllRunIds(String states, int pageSize) {
    List<String> all = new ArrayList<>();
    String cursor = null;
    int guard = 0;
    do {
      OperatorRunSummary page =
          inspection.getOperatorRunSummary(
              new OperatorRunFilter(
                  splitStates(states), null, pageSize, List.of(), List.of(), cursor));
      page.runs().forEach(r -> all.add(r.runId()));
      cursor = page.nextCursor();
      if (++guard > 100) {
        throw new IllegalStateException("cursor did not terminate");
      }
    } while (cursor != null);
    return all;
  }

  private static OperatorRunRow rowOf(OperatorRunSummary summary, String runId) {
    return summary.runs().stream().filter(r -> r.runId().equals(runId)).findFirst().orElseThrow();
  }

  private static List<String> runIds(OperatorRunSummary summary) {
    return summary.runs().stream().map(OperatorRunRow::runId).toList();
  }

  private static List<String> splitStates(String states) {
    return List.of(states.split(","));
  }

  private void seedProject(String publicId, String slug, String runnerKind) {
    jdbcTemplate.update(
        """
        insert into projects
          (public_id, name, slug, status, ticket_source_kind, repo_host_kind, runner_kind)
        values (?, ?, ?, 'active', 'linear', 'github', ?)
        """,
        publicId,
        slug,
        slug,
        runnerKind);
  }

  private String seedRun(String state, String projectId) {
    String runId = PublicIdPrefixes.WORKFLOW_RUN.next();
    jdbcTemplate.update(
        "insert into workflow_runs (public_id, current_state, project_id) values (?, ?, ?)",
        runId,
        state,
        projectId);
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
}
