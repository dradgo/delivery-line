package org.dradgo.application.workflow.ci;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import org.dradgo.application.integration.repohost.RepositoryHostAdapter;
import org.dradgo.application.project.ProjectConnectorResolver;
import org.dradgo.application.project.ProjectRuntimeConfigResolver;
import org.dradgo.application.runner.RunnerExecutionService;
import org.dradgo.application.runner.RunnerLogCaptureService;
import org.dradgo.application.runner.spi.RunnerExecutionRecordPort;
import org.dradgo.application.workflow.AfterCommitSideEffectRunner;
import org.dradgo.application.workflow.CiFixEscalationThresholdProvider;
import org.dradgo.application.workflow.WorkflowOrchestrationService;
import org.dradgo.application.workflow.WorkflowTransitionService;
import org.dradgo.application.workflow.ci.CiStatusPollingService.CiSweepResult;
import org.dradgo.application.workflow.spi.CiPollRow;
import org.dradgo.application.workflow.spi.CiStatusPort;
import org.dradgo.application.workflow.spi.WorkflowEventRecord;
import org.dradgo.application.workflow.spi.WorkflowEventWritePort;
import org.dradgo.application.workflow.spi.WorkflowRunRejectionLoopPort;
import org.dradgo.domain.integration.repohost.CiCheck;
import org.dradgo.domain.integration.repohost.CiConclusion;
import org.dradgo.domain.integration.repohost.CiStatus;
import org.dradgo.domain.project.Project;
import org.dradgo.domain.registry.RunnerStage;
import org.dradgo.domain.registry.WorkflowEventType;
import org.dradgo.domain.registry.WorkflowState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

/**
 * Story 3h-5 (AC2/AC4/AC6, AC7) — unit coverage for the CI-investigation sweep: green proceeds,
 * neutral records, pending bumps the attempt (and caps to unavailable), a transient read failure is
 * swallowed and retried, a red CI loops + honours the cap → escalation, and a red CI on a
 * non-reviewable run never re-dispatches.
 */
class CiStatusPollingServiceTest {

  private CiStatusPort ciStatusPort;
  private ProjectRuntimeConfigResolver runtimeResolver;
  private ProjectConnectorResolver connectorResolver;
  private RepositoryHostAdapter adapter;
  private WorkflowRunRejectionLoopPort rejectionLoopPort;
  private CiFixEscalationThresholdProvider thresholdProvider;
  private WorkflowTransitionService transitionService;
  private RunnerExecutionRecordPort recordPort;
  private RunnerExecutionService executionService;
  private RunnerLogCaptureService logCaptureService;
  private WorkflowEventWritePort eventWritePort;
  private WorkflowOrchestrationService orchestration;
  private CiStatusPollingService service;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    ciStatusPort = mock(CiStatusPort.class);
    runtimeResolver = mock(ProjectRuntimeConfigResolver.class);
    connectorResolver = mock(ProjectConnectorResolver.class);
    adapter = mock(RepositoryHostAdapter.class);
    rejectionLoopPort = mock(WorkflowRunRejectionLoopPort.class);
    thresholdProvider = mock(CiFixEscalationThresholdProvider.class);
    transitionService = mock(WorkflowTransitionService.class);
    recordPort = mock(RunnerExecutionRecordPort.class);
    executionService = mock(RunnerExecutionService.class);
    logCaptureService = mock(RunnerLogCaptureService.class);
    eventWritePort = mock(WorkflowEventWritePort.class);
    orchestration = mock(WorkflowOrchestrationService.class);

    Project project = mock(Project.class);
    lenient().when(runtimeResolver.resolveForRun(anyString())).thenReturn(project);
    lenient().when(connectorResolver.resolveRepositoryHost(any())).thenReturn(adapter);
    lenient()
        .when(runtimeResolver.resolveRepositoryRef(anyString()))
        .thenReturn(Optional.of("owner/repo"));
    lenient().when(thresholdProvider.get()).thenReturn(3);
    lenient().when(recordPort.nextContextBundleVersion(anyString(), any())).thenReturn(1);

    // A TransactionManager whose TransactionTemplate runs its callback synchronously.
    PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
    lenient().when(txManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
    AfterCommitSideEffectRunner afterCommit = new AfterCommitSideEffectRunner(txManager);

    ObjectProvider<WorkflowOrchestrationService> orchestrationProvider = mock(ObjectProvider.class);
    lenient().when(orchestrationProvider.getIfAvailable()).thenReturn(orchestration);

    CiInvestigationProperties properties = new CiInvestigationProperties(true, 30_000, 20, 60);

    service =
        new CiStatusPollingService(
            ciStatusPort,
            runtimeResolver,
            connectorResolver,
            afterCommit,
            properties,
            rejectionLoopPort,
            thresholdProvider,
            transitionService,
            recordPort,
            executionService,
            logCaptureService,
            eventWritePort,
            orchestrationProvider,
            txManager);
  }

  private void seedPendingRun(String runId, String headSha) {
    when(ciStatusPort.findRunsAwaitingCiStatus(anyLong(), anyInt()))
        .thenReturn(List.of(new CiPollRow(runId, headSha, 1L)));
  }

  @Test
  void greenCiRecordsSuccessAndNeverRedispatches() {
    seedPendingRun("run_1", "sha-1");
    when(adapter.readCheckRuns(any(), eq("sha-1")))
        .thenReturn(new CiStatus(CiConclusion.SUCCESS, "sha-1", List.of()));
    // At/after the grace threshold the SUCCESS verdict is accepted terminal.
    when(ciStatusPort.recordCiPollAttempt("run_1")).thenReturn(3);

    CiSweepResult result = service.sweep();

    assertThat(result.green()).isEqualTo(1);
    verify(ciStatusPort).recordCiStatus("run_1", "success");
    verify(transitionService, never())
        .transition(
            anyString(),
            any(WorkflowState.class),
            any(),
            anyString(),
            anyString(),
            any(java.util.Map.class));
    verify(orchestration, never()).retryImplementation(anyString(), any());
  }

  @Test
  void greenWithinGraceWindowKeepsPolling() {
    // Story 3h-5 review round 2: a SUCCESS verdict polled before the grace threshold stays pending
    // (the "green" half of the push→register race — only a passing subset of the commit's
    // check-runs
    // may have registered) instead of being recorded success and dropped from the sweep on tick
    // one.
    seedPendingRun("run_1", "sha-1");
    when(adapter.readCheckRuns(any(), eq("sha-1")))
        .thenReturn(new CiStatus(CiConclusion.SUCCESS, "sha-1", List.of()));
    when(ciStatusPort.recordCiPollAttempt("run_1")).thenReturn(1); // < grace threshold

    CiSweepResult result = service.sweep();

    assertThat(result.pending()).isEqualTo(1);
    assertThat(result.green()).isZero();
    verify(ciStatusPort).recordCiPollAttempt("run_1");
    verify(ciStatusPort, never()).recordCiStatus(eq("run_1"), anyString());
  }

  @Test
  void neutralCiRecordsNeutralAfterGrace() {
    seedPendingRun("run_1", "sha-1");
    when(adapter.readCheckRuns(any(), eq("sha-1")))
        .thenReturn(new CiStatus(CiConclusion.NEUTRAL, "sha-1", List.of()));
    // At/after the grace threshold the NEUTRAL verdict is recorded terminal.
    when(ciStatusPort.recordCiPollAttempt("run_1")).thenReturn(3);

    CiSweepResult result = service.sweep();

    assertThat(result.neutral()).isEqualTo(1);
    verify(ciStatusPort).recordCiStatus("run_1", "neutral");
  }

  @Test
  void neutralWithinGraceWindowKeepsPolling() {
    // Story 3h-5 review (D1): a NEUTRAL verdict polled before the grace threshold stays pending
    // (the
    // push→register race) instead of being recorded neutral and dropped from the sweep on tick one.
    seedPendingRun("run_1", "sha-1");
    when(adapter.readCheckRuns(any(), eq("sha-1")))
        .thenReturn(new CiStatus(CiConclusion.NEUTRAL, "sha-1", List.of()));
    when(ciStatusPort.recordCiPollAttempt("run_1")).thenReturn(1); // < grace threshold

    CiSweepResult result = service.sweep();

    assertThat(result.pending()).isEqualTo(1);
    verify(ciStatusPort).recordCiPollAttempt("run_1");
    verify(ciStatusPort, never()).recordCiStatus(eq("run_1"), anyString());
  }

  @Test
  void pendingCiBumpsAttemptAndStaysPending() {
    seedPendingRun("run_1", "sha-1");
    when(adapter.readCheckRuns(any(), eq("sha-1"))).thenReturn(CiStatus.pending("sha-1"));
    when(ciStatusPort.recordCiPollAttempt("run_1")).thenReturn(3);

    CiSweepResult result = service.sweep();

    assertThat(result.pending()).isEqualTo(1);
    verify(ciStatusPort).recordCiPollAttempt("run_1");
    verify(ciStatusPort, never()).recordCiStatus(eq("run_1"), anyString());
  }

  @Test
  void pendingBeyondMaxAttemptsRecordsUnavailable() {
    seedPendingRun("run_1", "sha-1");
    when(adapter.readCheckRuns(any(), eq("sha-1"))).thenReturn(CiStatus.pending("sha-1"));
    when(ciStatusPort.recordCiPollAttempt("run_1")).thenReturn(61); // > max (60)

    service.sweep();

    verify(ciStatusPort).recordCiStatus("run_1", "unavailable");
  }

  @Test
  void transientReadFailureIsSwallowedAndRetried() {
    seedPendingRun("run_1", "sha-1");
    when(adapter.readCheckRuns(any(), eq("sha-1"))).thenThrow(new RuntimeException("429"));

    CiSweepResult result = service.sweep();

    assertThat(result.readFailures()).isEqualTo(1);
    verify(ciStatusPort).recordCiPollAttempt("run_1");
    verify(ciStatusPort, never()).recordCiStatus(eq("run_1"), anyString());
  }

  @Test
  void readFailureBeyondMaxAttemptsRecordsUnavailable() {
    // Story 3h-5 review (HIGH, P1): a persistently unreadable host must still hit the poll-attempt
    // cap on the read-failure path and terminate to `unavailable`, mirroring the PENDING branch —
    // previously it bumped the attempt but never checked the cap, so it polled forever.
    seedPendingRun("run_1", "sha-1");
    when(adapter.readCheckRuns(any(), eq("sha-1"))).thenThrow(new RuntimeException("500"));
    when(ciStatusPort.recordCiPollAttempt("run_1")).thenReturn(61); // > max (60)

    CiSweepResult result = service.sweep();

    assertThat(result.readFailures()).isEqualTo(1);
    verify(ciStatusPort).recordCiStatus("run_1", "unavailable");
  }

  @Test
  void redCiOnReviewableRunUnderCapRedispatchesAndMaterializesCiRex() {
    seedPendingRun("run_1", "sha-1");
    when(adapter.readCheckRuns(any(), eq("sha-1")))
        .thenReturn(
            new CiStatus(
                CiConclusion.FAILURE,
                "sha-1",
                List.of(new CiCheck("ci-build", "failure", null, "1 failing", "boom"))));
    when(ciStatusPort.readCurrentState("run_1")).thenReturn(Optional.of("WaitingForReview"));
    when(rejectionLoopPort.incrementAndReadCiFixLoopCount("run_1")).thenReturn(1); // <= cap 3
    // Past the FAILURE grace threshold so the red verdict is acted on this tick.
    when(ciStatusPort.recordCiPollAttempt("run_1")).thenReturn(3);

    CiSweepResult result = service.sweep();

    assertThat(result.red()).isEqualTo(1);
    // A FAILED CI runner_executions row is materialized (Decision 3).
    verify(recordPort).insertPending(anyString(), eq("run_1"), eq(RunnerStage.CI), anyInt(), any());
    verify(executionService).recordFailed(anyString(), any());
    // Transition WaitingForReview -> Executing, then re-dispatch AFTER (Trap T1).
    verify(transitionService)
        .transition(
            eq("run_1"),
            eq(WorkflowState.EXECUTING),
            any(),
            anyString(),
            eq("ci-fix:run_1:1"),
            any(java.util.Map.class));
    verify(orchestration).retryImplementation(eq("run_1"), any());
    verify(ciStatusPort).recordCiStatus("run_1", "failure");
    verify(rejectionLoopPort, never()).markEscalationOnce(anyString());
  }

  @Test
  void redWithinGraceWindowKeepsPollingAndDoesNotRedispatch() {
    // Story 3h-5 3rd review (D1): a FAILURE verdict polled before the grace threshold stays pending
    // (the RED half of the push→register race — a fast check may have registered+failed while the
    // build check is not yet created) instead of triggering an immediate re-dispatch on tick one.
    seedPendingRun("run_1", "sha-1");
    when(adapter.readCheckRuns(any(), eq("sha-1")))
        .thenReturn(
            new CiStatus(
                CiConclusion.FAILURE,
                "sha-1",
                List.of(new CiCheck("ci-build", "failure", null, "1 failing", "boom"))));
    when(ciStatusPort.recordCiPollAttempt("run_1")).thenReturn(1); // < grace threshold

    CiSweepResult result = service.sweep();

    assertThat(result.pending()).isEqualTo(1);
    assertThat(result.red()).isZero();
    verify(ciStatusPort).recordCiPollAttempt("run_1");
    // No CI rex materialized, no transition, no re-dispatch, no verdict recorded yet.
    verify(recordPort, never()).insertPending(anyString(), anyString(), any(), anyInt(), any());
    verify(orchestration, never()).retryImplementation(anyString(), any());
    verify(ciStatusPort, never()).recordCiStatus(eq("run_1"), anyString());
  }

  @Test
  void redCiCaptureFailureLeavesRunPendingAndSkipsRedispatch() {
    // Story 3h-5 review round 2 (P2): if reserve/capture (Phase 2/3) is swallowed, Phase 4 must NOT
    // consume a fix-loop iteration or re-dispatch EXECUTION without the ci.failure feedback — the
    // run
    // is left ci_status='pending' for a clean next-tick retry.
    seedPendingRun("run_1", "sha-1");
    when(adapter.readCheckRuns(any(), eq("sha-1")))
        .thenReturn(
            new CiStatus(
                CiConclusion.FAILURE,
                "sha-1",
                List.of(new CiCheck("ci-build", "failure", null, "1 failing", "boom"))));
    when(ciStatusPort.readCurrentState("run_1")).thenReturn(Optional.of("WaitingForReview"));
    // Past the FAILURE grace threshold so the red verdict is acted on this tick.
    when(ciStatusPort.recordCiPollAttempt("run_1")).thenReturn(3);
    // Capture throws (e.g. the structural secret-leak scan) → Phase 3 is swallowed.
    when(logCaptureService.captureLogs(anyString(), anyString(), anyString(), anyString()))
        .thenThrow(new RuntimeException("capture boom"));

    service.sweep();

    // Phase 4 gated off: no loop increment, no transition, no re-dispatch, ci_status left pending.
    verify(rejectionLoopPort, never()).incrementAndReadCiFixLoopCount(anyString());
    verify(transitionService, never())
        .transition(
            anyString(),
            any(WorkflowState.class),
            any(),
            anyString(),
            anyString(),
            any(java.util.Map.class));
    verify(orchestration, never()).retryImplementation(anyString(), any());
    verify(ciStatusPort, never()).recordCiStatus(eq("run_1"), eq("failure"));
  }

  @Test
  void redCiCapExhaustedFlipsEscalationMarkerAndDoesNotRedispatch() {
    seedPendingRun("run_1", "sha-1");
    when(adapter.readCheckRuns(any(), eq("sha-1")))
        .thenReturn(
            new CiStatus(
                CiConclusion.FAILURE,
                "sha-1",
                List.of(new CiCheck("ci-build", "failure", null, "1 failing", "boom"))));
    when(ciStatusPort.readCurrentState("run_1")).thenReturn(Optional.of("WaitingForReview"));
    // Past the FAILURE grace threshold so the red verdict is acted on this tick.
    when(ciStatusPort.recordCiPollAttempt("run_1")).thenReturn(3);
    when(rejectionLoopPort.incrementAndReadCiFixLoopCount("run_1")).thenReturn(4); // > cap 3
    when(rejectionLoopPort.isEscalationMarkerSet("run_1")).thenReturn(false);
    when(rejectionLoopPort.markEscalationOnce("run_1")).thenReturn(1);

    service.sweep();

    verify(rejectionLoopPort).markEscalationOnce("run_1");
    verify(ciStatusPort).recordCiStatus("run_1", "failure");
    // No transition, no re-dispatch (Decision 5).
    verify(transitionService, never())
        .transition(
            anyString(),
            any(WorkflowState.class),
            any(),
            anyString(),
            anyString(),
            any(java.util.Map.class));
    verify(orchestration, never()).retryImplementation(anyString(), any());
    // Exactly one ESCALATION_REQUIRED event on the flip edge.
    ArgumentCaptor<WorkflowEventRecord> event = ArgumentCaptor.forClass(WorkflowEventRecord.class);
    verify(eventWritePort).append(event.capture());
    assertThat(event.getValue().eventType()).isEqualTo(WorkflowEventType.ESCALATION_REQUIRED);
  }

  @Test
  void redCiOnNonReviewableRunRecordsFailureAndSkipsRedispatch() {
    seedPendingRun("run_1", "sha-1");
    when(adapter.readCheckRuns(any(), eq("sha-1")))
        .thenReturn(
            new CiStatus(
                CiConclusion.FAILURE,
                "sha-1",
                List.of(new CiCheck("ci-build", "failure", null, "1 failing", "boom"))));
    when(ciStatusPort.readCurrentState("run_1")).thenReturn(Optional.of("Completed"));
    // Past the FAILURE grace threshold so the red verdict is acted on this tick.
    when(ciStatusPort.recordCiPollAttempt("run_1")).thenReturn(3);

    service.sweep();

    verify(ciStatusPort).recordCiStatus("run_1", "failure");
    verify(recordPort, never()).insertPending(anyString(), anyString(), any(), anyInt(), any());
    verify(transitionService, never())
        .transition(
            anyString(),
            any(WorkflowState.class),
            any(),
            anyString(),
            anyString(),
            any(java.util.Map.class));
    verify(orchestration, never()).retryImplementation(anyString(), any());
    verify(rejectionLoopPort, never()).incrementAndReadCiFixLoopCount(anyString());
  }

  @Test
  void batchLimitHitIsReported() {
    // batchLimit=20 → fetch 21; return 21 rows to trip the truncation probe.
    java.util.List<CiPollRow> rows = new java.util.ArrayList<>();
    for (int i = 1; i <= 21; i++) {
      rows.add(new CiPollRow("run_" + i, "sha-" + i, i));
    }
    when(ciStatusPort.findRunsAwaitingCiStatus(anyLong(), anyInt())).thenReturn(rows);
    when(adapter.readCheckRuns(any(), anyString()))
        .thenReturn(new CiStatus(CiConclusion.SUCCESS, "sha", List.of()));

    CiSweepResult result = service.sweep();

    assertThat(result.batchLimitHit()).isTrue();
    assertThat(result.scanned()).isEqualTo(20);
  }
}
