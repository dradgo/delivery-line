package org.dradgo.application.runner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.dradgo.application.artifact.ArtifactOperationService;
import org.dradgo.application.idempotency.IdempotencyService;
import org.dradgo.application.runner.spi.DockerHostPort;
import org.dradgo.application.runner.spi.DockerHostPort.DanglingContainerInfo;
import org.dradgo.application.runner.spi.RecoverableRunnerAdapter;
import org.dradgo.application.runner.spi.RunnerExecutionEventPort;
import org.dradgo.application.runner.spi.RunnerExecutionRecordPort;
import org.dradgo.application.runner.spi.RunnerExecutionSnapshot;
import org.dradgo.application.runner.spi.RunnerScratchStore;
import org.dradgo.application.runner.spi.RunnerWorkspaceStore;
import org.dradgo.application.workflow.WorkflowTransitionService;
import org.dradgo.domain.registry.RunnerExecutionStatus;
import org.dradgo.domain.registry.RunnerStage;
import org.dradgo.domain.registry.WorkflowEventType;
import org.dradgo.runnercontracts.RunnerContractValidator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Story 3.2a AC11 (story-3.2 AC10 q) — Logback list-appender logging contract for the docker
 * lifecycle branches, mirroring the {@code RunnerLoggingContractTest} (story 1.19) template. Pins
 * ≥1 assertion per new branch: timeout terminate (stop→kill outcome), heartbeat-stale WARN, orphan
 * WARN, workspace cleanup-deleted, workspace orphan-dir preserve, dangling-container removed,
 * recovery resumed-via-docker-probe, recovery no-container-found. Also closes the open story-3.1
 * {@code P-Logging-Contract} review item and asserts no workspace bytes leak into the logs.
 */
class DockerRunnerLifecycleLoggingContractTest {

  private static final String RUN_ID = "run_logcontract01";
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-05-29T12:00:00Z"), ZoneOffset.UTC);

  private RunnerExecutionRecordPort recordPort;
  private RunnerExecutionEventPort eventPort;
  private RunnerExecutionService executionService;
  private WorkflowTransitionService workflowTransitionService;
  private RecoverableRunnerAdapter runnerAdapter;
  private RunnerScratchStore scratchStore;
  private RunnerBroker broker;

  private RunnerExecutionRecordPort cleanupRecordPort;
  private RunnerWorkspaceStore workspaceStore;
  private DockerHostPort docker;
  private RunnerWorkspaceCleanupJob cleanupJob;

  private ListAppender<ILoggingEvent> brokerAppender;
  private ListAppender<ILoggingEvent> cleanupAppender;
  private Logger brokerLogger;
  private Logger cleanupLogger;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    recordPort = mock(RunnerExecutionRecordPort.class);
    eventPort = mock(RunnerExecutionEventPort.class);
    executionService = mock(RunnerExecutionService.class);
    workflowTransitionService = mock(WorkflowTransitionService.class);
    runnerAdapter = mock(RecoverableRunnerAdapter.class);
    scratchStore = mock(RunnerScratchStore.class);
    when(recordPort.findStaleByStatusInAndStageAndLastActivityAtBefore(
            any(), any(), any(), anyInt()))
        .thenReturn(List.of());
    broker =
        new RunnerBroker(
            recordPort,
            eventPort,
            executionService,
            mock(ContextBundleService.class),
            mock(IdempotencyService.class),
            workflowTransitionService,
            mock(ArtifactOperationService.class),
            runnerAdapter,
            scratchStore,
            new RunnerContractValidator(),
            RunnerProperties.defaults(),
            cleanScanService(),
            callthroughTemplate(),
            callthroughTemplate(),
            CLOCK);

    cleanupRecordPort = mock(RunnerExecutionRecordPort.class);
    workspaceStore = mock(RunnerWorkspaceStore.class);
    docker = mock(DockerHostPort.class);
    ObjectProvider<DockerHostPort> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(docker);
    cleanupJob =
        new RunnerWorkspaceCleanupJob(
            cleanupRecordPort, workspaceStore, provider, RunnerProperties.defaults(), CLOCK);

    brokerLogger = (Logger) LoggerFactory.getLogger(RunnerBroker.class);
    cleanupLogger = (Logger) LoggerFactory.getLogger(RunnerWorkspaceCleanupJob.class);
    brokerAppender = new ListAppender<>();
    cleanupAppender = new ListAppender<>();
    brokerAppender.start();
    cleanupAppender.start();
    brokerLogger.addAppender(brokerAppender);
    cleanupLogger.addAppender(cleanupAppender);
  }

  @AfterEach
  void tearDown() {
    brokerLogger.detachAppender(brokerAppender);
    cleanupLogger.detachAppender(cleanupAppender);
  }

  @Test
  void timeoutTerminateLogsTheKillAfterGraceOutcome() {
    String rex = "rex_timeoutlog01";
    RunnerExecutionSnapshot stale = rowWithTimeout(rex, OffsetDateTime.now(CLOCK).minusMinutes(1));
    when(recordPort.findStaleByStatusInAndTimeoutAtBefore(any(), any(), anyInt()))
        .thenReturn(List.of(stale));
    when(recordPort.findByPublicId(rex)).thenReturn(Optional.of(stale));
    when(runnerAdapter.findContainerIdFor(rex)).thenReturn(Optional.of("container_kill_1"));
    when(runnerAdapter.terminate(eq(rex), any()))
        .thenReturn(RecoverableRunnerAdapter.TerminationOutcome.KILLED_AFTER_GRACE);

    broker.scanForTimeouts();

    assertThat(brokerLog())
        .contains("scanForTimeouts terminate")
        .contains("outcome=KILLED_AFTER_GRACE");
  }

  @Test
  void heartbeatStaleAndOrphanBranchesLogWarnLines() {
    String staleRex = "rex_hbstalelog01";
    String orphanRex = "rex_orphanlog001";
    // heartbeat-stale: 15 min idle (past 1x, inside 2x).
    RunnerExecutionSnapshot heartbeatRow =
        rowWithActivity(staleRex, OffsetDateTime.now(CLOCK).minusMinutes(15));
    // orphan: 3h idle (past 2x).
    RunnerExecutionSnapshot orphanRow =
        rowWithActivity(orphanRex, OffsetDateTime.now(CLOCK).minusHours(3));
    when(recordPort.findStaleByStatusInAndStageAndLastActivityAtBefore(
            any(), eq(RunnerStage.INVESTIGATION), any(), anyInt()))
        .thenAnswer(
            invocation -> {
              Duration window = invocation.getArgument(2);
              // phase-1 window (1x = 600s) → heartbeat candidate; phase-2 (2x) → orphan candidate.
              return window.compareTo(Duration.ofSeconds(900)) <= 0
                  ? List.of(heartbeatRow)
                  : List.of(orphanRow);
            });
    when(recordPort.findByPublicId(staleRex)).thenReturn(Optional.of(heartbeatRow));
    when(recordPort.findByPublicId(orphanRex)).thenReturn(Optional.of(orphanRow));

    broker.scanForStaleExecutions();

    assertThat(brokerLog())
        .contains("RUNNER_HEARTBEAT_STALE appended")
        .contains("RUNNER_ORPHANED appended");
  }

  @Test
  void recoveryResumedViaDockerProbeLogsRunningReArm() {
    String rex = "rex_recoverlog01";
    RunnerExecutionSnapshot row = rowWithActivity(rex, OffsetDateTime.now(CLOCK).minusMinutes(1));
    when(recordPort.findActiveStatuses(any(), anyInt())).thenReturn(List.of(row));
    when(scratchStore.tryReadRunnerResult(rex)).thenReturn(Optional.empty());
    when(runnerAdapter.recoverHandle(rex)).thenReturn(Optional.of(new RunnerPollStatus.Running()));

    broker.recoverOnStartup();

    assertThat(brokerLog()).contains("recoverOnStartup resumed").contains("via=docker_probe");
  }

  @Test
  void recoveryNoContainerFoundLogsWarn() {
    String rex = "rex_nocontainr01";
    RunnerExecutionSnapshot row = rowWithActivity(rex, OffsetDateTime.now(CLOCK).minusMinutes(1));
    when(recordPort.findActiveStatuses(any(), anyInt())).thenReturn(List.of(row));
    when(scratchStore.tryReadRunnerResult(rex)).thenReturn(Optional.empty());
    when(runnerAdapter.recoverHandle(rex)).thenReturn(Optional.empty());
    when(runnerAdapter.findContainerIdFor(rex)).thenReturn(Optional.empty());

    broker.recoverOnStartup();

    assertThat(brokerLog()).contains("recoverOnStartup docker probe no-container-found");
    // Story 3.2a code-review (2026-05-29) — AC10(h): assert the BEHAVIOR, not just the WARN log.
    // No container matching the label filter must flip the row to orphaned and emit
    // RUNNER_ORPHANED.
    Mockito.verify(executionService).recordOrphaned(rex);
    Mockito.verify(eventPort)
        .append(any(), eq(WorkflowEventType.RUNNER_ORPHANED), any(), any(), any(), any(), any());
  }

  @Test
  void workspaceCleanupDeletedAndOrphanPreserveAndDanglingRemovedLogLinesAreEmitted() {
    // cleanup-deleted
    RunnerExecutionSnapshot terminal =
        rowWithStatus("rex_cleanlog0001", RunnerExecutionStatus.COMPLETED);
    when(cleanupRecordPort.findCompletedBeforeAndNotArchived(any(), anyInt()))
        .thenReturn(List.of(terminal));
    // orphan-dir preserve
    when(workspaceStore.listWorkspaceSubdirectories())
        .thenReturn(List.of(java.nio.file.Path.of("runner-work", "rex_orphandir001")));
    when(cleanupRecordPort.findByPublicId("rex_orphandir001")).thenReturn(Optional.empty());
    // dangling removed (rowless, past min-age)
    when(docker.listContainersByLabel(any(), any()))
        .thenReturn(
            List.of(
                new DanglingContainerInfo(
                    "container_dangling_1",
                    "rex_danglerow001",
                    "exited",
                    OffsetDateTime.now(CLOCK).minusMinutes(10))));
    when(cleanupRecordPort.findByPublicId("rex_danglerow001")).thenReturn(Optional.empty());

    cleanupJob.runCleanup();

    assertThat(cleanupLog())
        .contains("workspace cleanup deleted")
        .contains("workspace orphan dir found")
        .contains("action=preserve")
        .contains("dangling container removed");
    // Forbidden: no raw workspace bytes / credential markers leak into the cleanup logs.
    assertThat(cleanupLog()).doesNotContain("secret").doesNotContain("BEGIN PRIVATE KEY");
  }

  // ----- helpers -----

  private String brokerLog() {
    return render(brokerAppender);
  }

  private String cleanupLog() {
    return render(cleanupAppender);
  }

  private static String render(ListAppender<ILoggingEvent> appender) {
    StringBuilder sb = new StringBuilder();
    for (ILoggingEvent event : appender.list) {
      sb.append(event.getFormattedMessage()).append('\n');
    }
    return sb.toString();
  }

  private static RunnerExecutionSnapshot rowWithActivity(
      String rex, OffsetDateTime lastActivityAt) {
    return new RunnerExecutionSnapshot(
        rex,
        RUN_ID,
        RunnerStage.INVESTIGATION,
        RunnerExecutionStatus.RUNNING,
        1,
        lastActivityAt,
        lastActivityAt.plusSeconds(600),
        null,
        null,
        OffsetDateTime.now(CLOCK).minusHours(4),
        null,
        null);
  }

  private static RunnerExecutionSnapshot rowWithTimeout(String rex, OffsetDateTime timeoutAt) {
    return new RunnerExecutionSnapshot(
        rex,
        RUN_ID,
        RunnerStage.INVESTIGATION,
        RunnerExecutionStatus.RUNNING,
        1,
        OffsetDateTime.now(CLOCK).minusMinutes(20),
        timeoutAt,
        null,
        null,
        OffsetDateTime.now(CLOCK).minusHours(4),
        null,
        null);
  }

  private static RunnerExecutionSnapshot rowWithStatus(String rex, RunnerExecutionStatus status) {
    OffsetDateTime now = OffsetDateTime.now(CLOCK);
    return new RunnerExecutionSnapshot(
        rex,
        RUN_ID,
        RunnerStage.INVESTIGATION,
        status,
        1,
        now.minusHours(26),
        now.minusHours(25),
        null,
        now.minusHours(25),
        now.minusHours(26),
        null,
        null);
  }

  private static RunnerSecretScanService cleanScanService() {
    RunnerSecretScanService scanService = mock(RunnerSecretScanService.class);
    when(scanService.scanWorkspace(any(), any(), any(), any()))
        .thenReturn(RunnerSecretScanService.ScanOutcome.clean());
    return scanService;
  }

  private static TransactionTemplate callthroughTemplate() {
    TransactionTemplate template = Mockito.mock(TransactionTemplate.class);
    when(template.execute(any()))
        .thenAnswer(
            invocation -> {
              TransactionCallback<?> callback = invocation.getArgument(0);
              return callback.doInTransaction(null);
            });
    return template;
  }
}
