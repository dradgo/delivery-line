package org.dradgo.application.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.dradgo.TestcontainersConfiguration;
import org.dradgo.application.workflow.commands.ArchiveRunCommand;
import org.dradgo.application.workflow.commands.UnarchiveRunCommand;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.WorkflowState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Story 3d-8 (FR67, AC1/AC2/AC6/AC10, ADR 0027) — Testcontainers append-only regression for the
 * soft-hide surface. Verifies the central invariants on a real Postgres:
 *
 * <ul>
 *   <li>archive sets {@code workflow_runs.archived_at} + appends exactly ONE {@code
 *       workflow.archived} event; un-archive clears it + appends exactly ONE {@code
 *       workflow.unarchived} event.
 *   <li>across a full archive → un-archive cycle, NO {@code workflow_events} row is updated or
 *       deleted (only appended) and NO {@code workflow_runs} row is deleted (FR47 append-only).
 *   <li>an archived run leaves the default queue ({@code listRuns(includeArchived=false)}) but
 *       remains reachable by-id and via {@code includeArchived=true} (AC6).
 *   <li>double-archive / un-archive-not-archived raise {@code ARCHIVE_NOT_APPLICABLE} and write
 *       nothing; an idempotent replay appends no second event.
 * </ul>
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles({"test", "linear-mock"})
@Tag("integration")
class WorkflowArchiveServiceAppendOnlyIT {

  private static final String ACTOR = "alex";
  private static final String CORR = "corr-archive-it-1";

  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private WorkflowArchiveService workflowArchiveService;
  @Autowired private WorkflowInspectionService workflowInspectionService;

  @BeforeEach
  void setup() {
    cleanDatabase();
  }

  @AfterEach
  void teardown() {
    cleanDatabase();
  }

  private void cleanDatabase() {
    jdbcTemplate.update("delete from recovery_actions");
    jdbcTemplate.update("delete from runner_executions");
    jdbcTemplate.update("delete from artifact_operations");
    jdbcTemplate.update("delete from artifacts");
    jdbcTemplate.update("delete from workflow_events");
    jdbcTemplate.update("delete from idempotency_records");
    jdbcTemplate.update("delete from workflow_runs");
  }

  @Test
  void archiveThenUnarchiveIsAppendOnlyAndReversible() {
    String runId = insertRun("run_archiveit00000001", WorkflowState.FAILED);
    insertSeedEvent(runId, "evt_archiveitseed001");
    int seedEventCount = countRows("workflow_events");

    // ARCHIVE — marker set + one workflow.archived event, no other event mutated.
    WorkflowArchiveResult archived =
        workflowArchiveService.archiveRun(
            new ArchiveRunCommand(
                runId, ACTOR, ActorType.HUMAN, "idem-archive-it-aaaaaaaaaa", CORR, "obsolete"));
    assertNotNull(archived.archivedAt());
    assertEquals(WorkflowState.FAILED, archived.currentState());
    assertNotNull(archivedAtOf(runId), "archived_at must be set after archive");
    assertEquals(
        seedEventCount + 1, countRows("workflow_events"), "exactly one event appended on archive");
    assertEquals(
        1,
        countEventsOfType(runId, "workflow.archived"),
        "a single workflow.archived event must be appended");
    assertEquals(0, countEventsOfType(runId, "workflow.unarchived"), "no unarchive event yet");

    // The archived run leaves the default queue but stays reachable by id + includeArchived=true.
    assertTrue(
        listRunIds(false).stream().noneMatch(runId::equals),
        "archived run must NOT appear in the default (archived_at IS NULL) queue");
    assertTrue(
        listRunIds(true).stream().anyMatch(runId::equals),
        "archived run MUST appear when includeArchived=true");
    assertNotNull(
        workflowInspectionService.getRunSummary(runId),
        "archived run must remain reachable by-id (audit-queryable, AC6)");

    // UN-ARCHIVE — marker cleared + one workflow.unarchived event.
    WorkflowArchiveResult unarchived =
        workflowArchiveService.unarchiveRun(
            new UnarchiveRunCommand(
                runId, ACTOR, ActorType.HUMAN, "idem-unarchive-it-aaaaaaa", CORR, null));
    assertNull(unarchived.archivedAt());
    assertNull(archivedAtOf(runId), "archived_at must be cleared after un-archive");
    assertEquals(
        seedEventCount + 2,
        countRows("workflow_events"),
        "exactly two events appended across archive + un-archive");
    assertEquals(1, countEventsOfType(runId, "workflow.unarchived"));

    // Append-only: the originally-seeded event is untouched, the run row still exists.
    assertEquals(
        1,
        countRows("workflow_runs"),
        "no workflow_runs row may be deleted by archive/un-archive (FR47)");
    assertTrue(
        listRunIds(false).stream().anyMatch(runId::equals),
        "un-archived run returns to the default queue");
  }

  @Test
  void doubleArchiveRaisesArchiveNotApplicableAndWritesNothing() {
    String runId = insertRun("run_archiveit00000002", WorkflowState.COMPLETED);
    workflowArchiveService.archiveRun(
        new ArchiveRunCommand(
            runId, ACTOR, ActorType.HUMAN, "idem-archive-it-bbbbbbbbbb", CORR, "obsolete"));
    int eventsAfterFirst = countRows("workflow_events");

    DomainException rejection =
        assertThrows(
            DomainException.class,
            () ->
                workflowArchiveService.archiveRun(
                    new ArchiveRunCommand(
                        runId,
                        ACTOR,
                        ActorType.HUMAN,
                        "idem-archive-it-cccccccccc",
                        CORR,
                        "again")));
    assertEquals(DomainErrorCode.ARCHIVE_NOT_APPLICABLE, rejection.errorCode());
    assertEquals(
        eventsAfterFirst,
        countRows("workflow_events"),
        "a rejected double-archive must append no event");
  }

  @Test
  void unarchiveNotArchivedRaisesArchiveNotApplicable() {
    String runId = insertRun("run_archiveit00000003", WorkflowState.FAILED);
    DomainException rejection =
        assertThrows(
            DomainException.class,
            () ->
                workflowArchiveService.unarchiveRun(
                    new UnarchiveRunCommand(
                        runId, ACTOR, ActorType.HUMAN, "idem-unarchive-it-bbbbbbb", CORR, null)));
    assertEquals(DomainErrorCode.ARCHIVE_NOT_APPLICABLE, rejection.errorCode());
    assertNull(archivedAtOf(runId));
  }

  @Test
  void idempotentReplayAppendsNoSecondEvent() {
    String runId = insertRun("run_archiveit00000004", WorkflowState.FAILED);
    String key = "idem-archive-it-dddddddddd";
    workflowArchiveService.archiveRun(
        new ArchiveRunCommand(runId, ACTOR, ActorType.HUMAN, key, CORR, "obsolete"));
    int eventsAfterFirst = countRows("workflow_events");

    WorkflowArchiveResult replay =
        workflowArchiveService.archiveRun(
            new ArchiveRunCommand(runId, ACTOR, ActorType.HUMAN, key, CORR, "obsolete"));
    assertTrue(replay.replay(), "same-key replay must report replay=true");
    assertEquals(
        eventsAfterFirst,
        countRows("workflow_events"),
        "an idempotent replay must NOT append a second event");
  }

  @Test
  void archiveUnknownRunRaisesRunNotFound() {
    DomainException rejection =
        assertThrows(
            DomainException.class,
            () ->
                workflowArchiveService.archiveRun(
                    new ArchiveRunCommand(
                        "run_archiveit0000miss",
                        ACTOR,
                        ActorType.HUMAN,
                        "idem-archive-it-eeeeeeeeee",
                        CORR,
                        "obsolete")));
    assertEquals(DomainErrorCode.RUN_NOT_FOUND, rejection.errorCode());
  }

  // --- helpers ---------------------------------------------------------------

  private String insertRun(String publicId, WorkflowState state) {
    jdbcTemplate.update(
        "insert into workflow_runs (public_id, current_state) values (?, ?)",
        publicId,
        state.value());
    return publicId;
  }

  private String insertSeedEvent(String runPublicId, String eventPublicId) {
    Long workflowRunId =
        jdbcTemplate.queryForObject(
            "select id from workflow_runs where public_id = ?", Long.class, runPublicId);
    assertTrue(workflowRunId != null && workflowRunId > 0);
    jdbcTemplate.update(
        """
        insert into workflow_events
          (public_id, workflow_run_id, event_type, actor_identity, actor_type,
           intervention_marker, details, prior_state, resulting_state)
        values (?, ?, 'workflow.stateChanged', 'alex', 'human', false, '{}'::jsonb, 'Executing', 'Failed')
        """,
        eventPublicId,
        workflowRunId);
    return eventPublicId;
  }

  private java.time.OffsetDateTime archivedAtOf(String runPublicId) {
    return jdbcTemplate.queryForObject(
        "select archived_at from workflow_runs where public_id = ?",
        java.time.OffsetDateTime.class,
        runPublicId);
  }

  private int countEventsOfType(String runPublicId, String eventType) {
    Integer count =
        jdbcTemplate.queryForObject(
            "select count(*) from workflow_events where event_type = ? and workflow_run_id ="
                + " (select id from workflow_runs where public_id = ?)",
            Integer.class,
            eventType,
            runPublicId);
    return count == null ? 0 : count;
  }

  private List<String> listRunIds(boolean includeArchived) {
    return workflowInspectionService.listRuns(null, includeArchived, 200).stream()
        .map(WorkflowInspectionService.WorkflowRunSummaryView::workflowRunId)
        .toList();
  }

  private Integer countRows(String tableName) {
    return jdbcTemplate.queryForObject("select count(*) from " + tableName, Integer.class);
  }
}
