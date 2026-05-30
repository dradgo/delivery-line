package org.dradgo.application.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.dradgo.application.artifact.ArtifactOperationService;
import org.dradgo.application.idempotency.IdempotencyService;
import org.dradgo.application.runner.spi.RunnerAdapter;
import org.dradgo.application.runner.spi.RunnerExecutionEventPort;
import org.dradgo.application.runner.spi.RunnerExecutionRecordPort;
import org.dradgo.application.runner.spi.RunnerExecutionSnapshot;
import org.dradgo.application.runner.spi.RunnerScratchStore;
import org.dradgo.application.workflow.WorkflowTransitionService;
import org.dradgo.domain.registry.FailureCategory;
import org.dradgo.domain.registry.RunnerExecutionStatus;
import org.dradgo.domain.registry.RunnerStage;
import org.dradgo.domain.registry.WorkflowEventType;
import org.dradgo.runnercontracts.RunnerContractValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Story 3.2a AC1 (d) + (e) and AC2 — fast-tier (Mockito, no Docker, no Spring) coverage of {@link
 * RunnerBroker#scanForStaleExecutions()}: orphan flip past 2× timeout, at-most-once heartbeat-stale
 * emission (Trap T4), and the stage-scoped query that closes the cross-stage starvation bug.
 */
class RunnerBrokerStaleDetectionUnitTest {

  private static final String RUN_ID = "run_stale12345678";
  // RunnerProperties.defaults(): stage timeout 600s, staleThresholdMultiplier 2.0 → orphan @ 1200s.
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-05-29T12:00:00Z"), ZoneOffset.UTC);

  private RunnerExecutionRecordPort recordPort;
  private RunnerExecutionEventPort eventPort;
  private RunnerExecutionService executionService;
  private WorkflowTransitionService workflowTransitionService;
  private RunnerAdapter runnerAdapter;
  private RunnerBroker broker;

  @BeforeEach
  void setUp() {
    recordPort = mock(RunnerExecutionRecordPort.class);
    eventPort = mock(RunnerExecutionEventPort.class);
    executionService = mock(RunnerExecutionService.class);
    workflowTransitionService = mock(WorkflowTransitionService.class);
    runnerAdapter = mock(RunnerAdapter.class);
    // Default: no stale rows for any stage/window unless a test stubs otherwise.
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
            mock(RunnerScratchStore.class),
            new RunnerContractValidator(),
            RunnerProperties.defaults(),
            cleanScanService(),
            callthroughTemplate(),
            callthroughTemplate(),
            CLOCK);
  }

  @Test
  void orphanFlipPastTwoTimesTimeoutEmitsRunnerOrphanedAndDoesNotDriveWorkflowFailed() {
    String rex = "rex_orphan00000a";
    OffsetDateTime lastActivity = OffsetDateTime.now(CLOCK).minusHours(3); // well past 2× (1200s)
    RunnerExecutionSnapshot running =
        row(rex, RunnerStage.INVESTIGATION, RunnerExecutionStatus.RUNNING, lastActivity, null);
    // The row is returned for BOTH the heartbeat-stale (600s) and orphan (1200s) windows.
    when(recordPort.findStaleByStatusInAndStageAndLastActivityAtBefore(
            any(), eq(RunnerStage.INVESTIGATION), any(), anyInt()))
        .thenReturn(List.of(running));
    when(recordPort.findByPublicId(rex)).thenReturn(Optional.of(running));

    broker.scanForStaleExecutions();

    // Orphan flip happened; no heartbeat-stale event (the row is past the orphan threshold so the
    // heartbeat phase defers to the orphan phase rather than double-emitting).
    verify(executionService).recordOrphaned(rex);
    ArgumentCaptor<Map<String, Object>> details = detailsCaptor();
    verify(eventPort)
        .append(
            eq(RUN_ID),
            eq(WorkflowEventType.RUNNER_ORPHANED),
            any(),
            eq("lease_expired"),
            eq(FailureCategory.ORPHAN),
            any(),
            details.capture());
    assertEquals(FailureCategory.ORPHAN.value(), details.getValue().get("failureCategory"));
    assertEquals("lease_expired", details.getValue().get("reason"));
    verify(eventPort, never())
        .append(
            any(), eq(WorkflowEventType.RUNNER_HEARTBEAT_STALE), any(), any(), any(), any(), any());
    // AC3 (c): orphan is left to Epic-4 recovery — the broker does NOT drive the workflow to
    // FAILED.
    verify(workflowTransitionService, never())
        .transition(any(), any(), any(), any(), any(), any(FailureCategory.class), any());
  }

  @Test
  void heartbeatStaleEmittedAtMostOncePerStaleWindowAcrossTwoScans() {
    String rex = "rex_hbstale0000a";
    // Past 1× timeout (600s) but inside 2× (1200s): 15 min → heartbeat-stale, NOT orphan.
    OffsetDateTime lastActivity = OffsetDateTime.now(CLOCK).minusMinutes(15);
    AtomicReference<RunnerExecutionSnapshot> current =
        new AtomicReference<>(
            row(rex, RunnerStage.INVESTIGATION, RunnerExecutionStatus.RUNNING, lastActivity, null));
    // Phase-1 (600s) returns the row; phase-2 (1200s) returns nothing (row not orphan-eligible
    // yet).
    when(recordPort.findStaleByStatusInAndStageAndLastActivityAtBefore(
            any(), eq(RunnerStage.INVESTIGATION), any(), anyInt()))
        .thenAnswer(
            invocation -> {
              java.time.Duration window = invocation.getArgument(2);
              return window.compareTo(java.time.Duration.ofSeconds(900)) <= 0
                  ? List.of(current.get())
                  : List.of();
            });
    when(recordPort.findByPublicId(rex)).thenAnswer(invocation -> Optional.of(current.get()));
    // Emitting the event marks heartbeat_stale_emitted_at; reflect that in subsequent reads.
    when(recordPort.markHeartbeatStaleEmitted(eq(rex), any()))
        .thenAnswer(
            invocation -> {
              OffsetDateTime emittedAt = invocation.getArgument(1);
              current.set(
                  row(
                      rex,
                      RunnerStage.INVESTIGATION,
                      RunnerExecutionStatus.RUNNING,
                      lastActivity,
                      emittedAt));
              return current.get();
            });

    broker.scanForStaleExecutions();
    broker.scanForStaleExecutions();

    // Trap T4: exactly one heartbeat-stale event across both scans; the row stays RUNNING (WARN
    // only — never orphaned).
    verify(eventPort, times(1))
        .append(
            eq(RUN_ID),
            eq(WorkflowEventType.RUNNER_HEARTBEAT_STALE),
            any(),
            any(),
            any(),
            any(),
            any());
    verify(recordPort, times(1)).markHeartbeatStaleEmitted(eq(rex), any());
    verify(executionService, never()).recordOrphaned(rex);
  }

  @Test
  void stageScopedQueryProcessesAllExecutionRowsDespiteInvestigationBacklog() {
    int batchSize = RunnerProperties.defaults().timeoutScanBatchSize();
    OffsetDateTime orphanAge = OffsetDateTime.now(CLOCK).minusHours(2); // past 2× timeout

    // A backlog of (batchSize + 5) investigation rows that would, under the old non-scoped query,
    // fill the LIMIT and starve the execution stage every tick.
    List<RunnerExecutionSnapshot> investigation = new ArrayList<>();
    for (int i = 0; i < batchSize + 5; i++) {
      investigation.add(
          row(
              String.format("rex_inv%09d", i),
              RunnerStage.INVESTIGATION,
              RunnerExecutionStatus.RUNNING,
              orphanAge,
              null));
    }
    List<RunnerExecutionSnapshot> execution =
        List.of(
            row(
                "rex_exec000001a",
                RunnerStage.EXECUTION,
                RunnerExecutionStatus.RUNNING,
                orphanAge,
                null),
            row(
                "rex_exec000002a",
                RunnerStage.EXECUTION,
                RunnerExecutionStatus.RUNNING,
                orphanAge,
                null),
            row(
                "rex_exec000003a",
                RunnerStage.EXECUTION,
                RunnerExecutionStatus.RUNNING,
                orphanAge,
                null));

    when(recordPort.findStaleByStatusInAndStageAndLastActivityAtBefore(
            any(), eq(RunnerStage.INVESTIGATION), any(), anyInt()))
        .thenReturn(investigation.subList(0, Math.min(batchSize, investigation.size())));
    when(recordPort.findStaleByStatusInAndStageAndLastActivityAtBefore(
            any(), eq(RunnerStage.EXECUTION), any(), anyInt()))
        .thenReturn(execution);
    when(recordPort.findByPublicId(any()))
        .thenAnswer(
            invocation -> {
              String id = invocation.getArgument(0);
              RunnerStage stage =
                  id.startsWith("rex_exec") ? RunnerStage.EXECUTION : RunnerStage.INVESTIGATION;
              return Optional.of(row(id, stage, RunnerExecutionStatus.RUNNING, orphanAge, null));
            });

    broker.scanForStaleExecutions();

    // All three execution rows are orphaned in a single scan — the stage-scoped LIMIT means the
    // investigation backlog cannot starve them.
    verify(executionService).recordOrphaned("rex_exec000001a");
    verify(executionService).recordOrphaned("rex_exec000002a");
    verify(executionService).recordOrphaned("rex_exec000003a");
  }

  @Test
  void orphanPathInvokedTwiceEmitsRunnerOrphanedExactlyOnce() {
    // Story 3.2a AC9: a concurrent scan-vs-recovery race can re-enter the orphan path on a row that
    // a sibling already moved terminal. recordOrphaned re-reads under a write lock and throws
    // ILLEGAL_TRANSITION on the loser; the broker must catch it and NOT append a duplicate event.
    String rex = "rex_dblemit0000a";
    OffsetDateTime orphanAge = OffsetDateTime.now(CLOCK).minusHours(3);
    RunnerExecutionSnapshot running =
        row(rex, RunnerStage.INVESTIGATION, RunnerExecutionStatus.RUNNING, orphanAge, null);
    when(recordPort.findStaleByStatusInAndStageAndLastActivityAtBefore(
            any(), eq(RunnerStage.INVESTIGATION), any(), anyInt()))
        .thenReturn(List.of(running));
    when(recordPort.findByPublicId(rex)).thenReturn(Optional.of(running));
    when(executionService.recordOrphaned(rex))
        .thenReturn(running)
        .thenThrow(
            new org.dradgo.domain.DomainException(
                org.dradgo.domain.registry.DomainErrorCode.ILLEGAL_TRANSITION, "already terminal"));

    broker.scanForStaleExecutions();
    broker.scanForStaleExecutions();

    verify(eventPort, times(1))
        .append(
            eq(RUN_ID), eq(WorkflowEventType.RUNNER_ORPHANED), any(), any(), any(), any(), any());
  }

  @Test
  void heartbeatStaleSkipsRowWithNullLastActivityWithoutEmittingOrOrphaning() {
    // Story 3.2a AC9: a null last_activity_at is anomalous (a live row always carries one); the
    // heartbeat-stale phase must skip it explicitly rather than conflating null with
    // "past the orphan threshold".
    String rex = "rex_nullact0000a";
    RunnerExecutionSnapshot running =
        row(rex, RunnerStage.INVESTIGATION, RunnerExecutionStatus.RUNNING, null, null);
    when(recordPort.findStaleByStatusInAndStageAndLastActivityAtBefore(
            any(), eq(RunnerStage.INVESTIGATION), any(), anyInt()))
        .thenReturn(List.of(running));
    when(recordPort.findByPublicId(rex)).thenReturn(Optional.of(running));

    broker.scanForStaleExecutions();

    verify(eventPort, never())
        .append(
            any(), eq(WorkflowEventType.RUNNER_HEARTBEAT_STALE), any(), any(), any(), any(), any());
    verify(recordPort, never()).markHeartbeatStaleEmitted(any(), any());
  }

  // ----- helpers -----

  private static RunnerExecutionSnapshot row(
      String publicId,
      RunnerStage stage,
      RunnerExecutionStatus status,
      OffsetDateTime lastActivityAt,
      OffsetDateTime heartbeatStaleEmittedAt) {
    OffsetDateTime created = OffsetDateTime.now(CLOCK).minusHours(4);
    return new RunnerExecutionSnapshot(
        publicId,
        RUN_ID,
        stage,
        status,
        1,
        lastActivityAt,
        lastActivityAt == null ? null : lastActivityAt.plusSeconds(600),
        null,
        RunnerExecutionStateMachine.isTerminal(status) ? lastActivityAt : null,
        created,
        null,
        heartbeatStaleEmittedAt);
  }

  @SuppressWarnings("unchecked")
  private static ArgumentCaptor<Map<String, Object>> detailsCaptor() {
    return ArgumentCaptor.forClass(Map.class);
  }

  private static RunnerSecretScanService cleanScanService() {
    RunnerSecretScanService scanService = mock(RunnerSecretScanService.class);
    when(scanService.scanWorkspace(any(), any(), any(), any()))
        .thenReturn(RunnerSecretScanService.ScanOutcome.clean());
    return scanService;
  }

  private static TransactionTemplate callthroughTemplate() {
    TransactionTemplate template = mock(TransactionTemplate.class);
    when(template.execute(any()))
        .thenAnswer(
            invocation -> {
              TransactionCallback<?> callback = invocation.getArgument(0);
              return callback.doInTransaction(null);
            });
    return template;
  }
}
