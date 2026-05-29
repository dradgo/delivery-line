package org.dradgo.application.runner;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.OffsetDateTime;
import org.dradgo.TestcontainersConfiguration;
import org.dradgo.adapters.files.LocalRunnerWorkspaceStore;
import org.dradgo.application.runner.spi.DockerHostPort;
import org.dradgo.application.runner.spi.RunnerExecutionRecordPort;
import org.dradgo.domain.id.PublicIdPrefixes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Story 3.2a AC3 (story-3.2 AC10 i/j/k) — Testcontainers coverage of {@link
 * RunnerWorkspaceCleanupJob} workspace sweep + orphan-dir preserve, plus the Trap-T16 SQL status
 * guard. Uses the real Postgres (via {@link TestcontainersConfiguration}) for the {@code
 * runner_executions} rows and a {@link TempDir} for the on-disk workspace — NO Docker engine (the
 * dangling-container sweep is wired with an empty {@link ObjectProvider}).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles({"test", "linear-mock"})
@Tag("integration")
class RunnerWorkspaceCleanupJobIT {

  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private RunnerExecutionRecordPort recordPort;

  @TempDir Path tempHome;

  private LocalRunnerWorkspaceStore workspaceStore;
  private RunnerWorkspaceCleanupJob job;
  private ListAppender<ILoggingEvent> appender;
  private Logger jobLogger;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    workspaceStore = new LocalRunnerWorkspaceStore(tempHome.toString());
    ObjectProvider<DockerHostPort> noDocker = mock(ObjectProvider.class);
    when(noDocker.getIfAvailable()).thenReturn(null); // runners.mock — no engine wired.
    // RunnerProperties.defaults(): workspace-retention-hours = 24.
    job =
        new RunnerWorkspaceCleanupJob(
            recordPort, workspaceStore, noDocker, RunnerProperties.defaults(), Clock.systemUTC());

    jobLogger = (Logger) LoggerFactory.getLogger(RunnerWorkspaceCleanupJob.class);
    appender = new ListAppender<>();
    appender.start();
    jobLogger.addAppender(appender);
  }

  @AfterEach
  void tearDown() {
    if (jobLogger != null && appender != null) {
      jobLogger.detachAppender(appender);
    }
    // This @SpringBootTest is not @Transactional and shares its Postgres with other same-config
    // contract tests. Delete the rows it seeded (FK-safe order) so leftover runner_executions don't
    // break a sibling test's own cleanup (e.g. `delete from workflow_runs` failing on
    // fk_runner_executions_workflow_runs).
    jdbcTemplate.update("delete from runner_executions");
    jdbcTemplate.update("delete from workflow_runs");
  }

  @Test
  void completedRowPastRetentionHasWorkspaceDeletedAndArchivedAtSet() {
    String rex = seedTerminalRunner(/* hoursAgo= */ 25);
    workspaceStore.prepare(rex); // create the on-disk workspace tree
    assertTrue(Files.isDirectory(workspaceStore.workspaceRoot().resolve(rex)));

    int deleted = job.sweepWorkspaces();

    assertTrue(deleted >= 1, "the past-retention row must be swept");
    assertFalse(
        Files.exists(workspaceStore.workspaceRoot().resolve(rex)),
        "workspace dir must be deleted for a past-retention terminal row");
    assertNotNull(archivedAt(rex), "archived_at must be set after the sweep");
  }

  @Test
  void completedRowWithinRetentionIsNotDeletedAndArchivedAtStaysNull() {
    String rex = seedTerminalRunner(/* hoursAgo= */ 2); // inside the 24h horizon
    workspaceStore.prepare(rex);

    job.sweepWorkspaces();

    assertTrue(
        Files.isDirectory(workspaceStore.workspaceRoot().resolve(rex)),
        "workspace dir must be preserved within the retention horizon");
    assertNull(archivedAt(rex), "archived_at must stay null within the retention horizon");
  }

  @Test
  void orphanDirsWithoutRowsArePreservedWithWarn() throws Exception {
    // A valid-id rex_ dir with no DB row, and a malformed rex_ dir (invalid public id).
    Path validOrphan = workspaceStore.workspaceRoot().resolve("rex_orphan0001ab");
    Path malformedOrphan = workspaceStore.workspaceRoot().resolve("rex_bad"); // suffix < 4 chars
    Files.createDirectories(validOrphan);
    Files.createDirectories(malformedOrphan);

    int preserved = job.sweepWorkspaceOrphanDirs();

    assertTrue(preserved >= 2, "both orphan dirs must be counted as preserved");
    assertTrue(Files.isDirectory(validOrphan), "valid orphan dir must NOT be deleted (Trap T7)");
    assertTrue(Files.isDirectory(malformedOrphan), "malformed orphan dir must NOT be deleted");
    String logs = renderLogs();
    assertTrue(
        logs.contains("workspace orphan dir found")
            && logs.contains("action=preserve")
            && logs.contains("rex_orphan0001ab"),
        () -> "expected a preserve WARN for the valid orphan dir; logs=\n" + logs);
    assertTrue(
        logs.contains("reason=invalid_id"),
        () -> "expected reason=invalid_id for the malformed dir; logs=\n" + logs);
  }

  @Test
  void runningRowIsNeverReturnedByCleanupQueryRegardlessOfAge() {
    // Trap T16: the cleanup query restricts status to terminal values
    // (completed/failed/timed_out/orphaned), so a non-terminal row is never swept. (A running row
    // cannot even carry completed_at — the ck_runner_executions_completed_correlation CHECK forbids
    // it — so the SQL status guard is belt-and-suspenders.) Seed an old running row and assert it
    // is
    // excluded.
    String rex = seedRunningRunner();

    boolean returned =
        recordPort.findCompletedBeforeAndNotArchived(OffsetDateTime.now(), 50).stream()
            .anyMatch(snapshot -> snapshot.publicId().equals(rex));

    assertFalse(returned, "a running row must never be returned by the cleanup query (Trap T16)");
  }

  // ----- seed helpers -----
  // Raw JdbcTemplate inserts (not the record port): the port's mutating methods take a pessimistic
  // lock and require an active transaction, which this non-@Transactional @SpringBootTest does not
  // provide.

  private String seedTerminalRunner(long hoursAgo) {
    String runId = PublicIdPrefixes.WORKFLOW_RUN.next();
    jdbcTemplate.update(
        "insert into workflow_runs (public_id, current_state) values (?, 'Inbox')", runId);
    String rex = PublicIdPrefixes.RUNNER_EXECUTION.next();
    jdbcTemplate.update(
        """
        insert into runner_executions
          (public_id, workflow_run_id, stage, status, context_bundle_version,
           last_activity_at, timeout_at, completed_at, created_at)
        values (?, (select id from workflow_runs where public_id = ?), 'investigation', 'completed',
                1, now() - (interval '1 hour' * ?), now() - (interval '1 hour' * ?),
                now() - (interval '1 hour' * ?), now() - (interval '1 hour' * ?))
        """,
        rex,
        runId,
        hoursAgo,
        hoursAgo,
        hoursAgo,
        hoursAgo);
    return rex;
  }

  private String seedRunningRunner() {
    String runId = PublicIdPrefixes.WORKFLOW_RUN.next();
    jdbcTemplate.update(
        "insert into workflow_runs (public_id, current_state) values (?, 'Inbox')", runId);
    String rex = PublicIdPrefixes.RUNNER_EXECUTION.next();
    jdbcTemplate.update(
        """
        insert into runner_executions
          (public_id, workflow_run_id, stage, status, context_bundle_version,
           last_activity_at, timeout_at, created_at)
        values (?, (select id from workflow_runs where public_id = ?), 'investigation', 'running', 1,
                now() - interval '25 hours', now() - interval '24 hours', now() - interval '25 hours')
        """,
        rex,
        runId);
    return rex;
  }

  private OffsetDateTime archivedAt(String rex) {
    return jdbcTemplate.queryForObject(
        "select archived_at from runner_executions where public_id = ?", OffsetDateTime.class, rex);
  }

  private String renderLogs() {
    StringBuilder sb = new StringBuilder();
    for (ILoggingEvent event : appender.list) {
      sb.append(event.getFormattedMessage()).append('\n');
    }
    return sb.toString();
  }
}
