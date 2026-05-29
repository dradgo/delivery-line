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
import java.time.Duration;
import java.time.OffsetDateTime;
import org.dradgo.TestcontainersConfiguration;
import org.dradgo.adapters.files.LocalRunnerWorkspaceStore;
import org.dradgo.application.runner.spi.DockerHostPort;
import org.dradgo.application.runner.spi.RunnerExecutionRecordPort;
import org.dradgo.domain.id.PublicIdPrefixes;
import org.dradgo.domain.registry.RunnerStage;
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
  void runningRowWithBackdatedCompletedAtIsNotReturnedByCleanupQuery() {
    // Trap T16: even if completed_at drifted to 25h ago, a row whose status is NOT terminal must
    // never be returned to the workspace sweep (SQL status IN (completed, failed, timed_out,
    // orphaned)).
    String rex = seedRunningRunnerWithBackdatedCompletedAt(/* hoursAgo= */ 25);

    boolean returned =
        recordPort.findCompletedBeforeAndNotArchived(OffsetDateTime.now(), 50).stream()
            .anyMatch(snapshot -> snapshot.publicId().equals(rex));

    assertFalse(returned, "a running row must never be returned by the cleanup query (Trap T16)");
  }

  // ----- seed helpers -----

  private String seedTerminalRunner(long hoursAgo) {
    String rex = insertPendingRow();
    recordPort.markCompleted(rex, OffsetDateTime.now());
    backdateCompletedAt(rex, hoursAgo);
    return rex;
  }

  private String seedRunningRunnerWithBackdatedCompletedAt(long hoursAgo) {
    String rex = insertPendingRow();
    recordPort.transitionToRunning(rex, OffsetDateTime.now());
    backdateCompletedAt(rex, hoursAgo); // forces the drift the T16 guard must ignore
    return rex;
  }

  private String insertPendingRow() {
    String runId = PublicIdPrefixes.WORKFLOW_RUN.next();
    jdbcTemplate.update(
        "insert into workflow_runs (public_id, current_state) values (?, 'Inbox')", runId);
    String rex = PublicIdPrefixes.RUNNER_EXECUTION.next();
    recordPort.insertPending(
        rex,
        runId,
        RunnerStage.INVESTIGATION,
        1,
        new ExecutionConstraints(Duration.ofSeconds(600L), false));
    return rex;
  }

  private void backdateCompletedAt(String rex, long hoursAgo) {
    jdbcTemplate.update(
        "update runner_executions set completed_at = now() - (interval '1 hour' * ?) where public_id = ?",
        hoursAgo,
        rex);
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
