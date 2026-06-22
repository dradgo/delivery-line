package org.dradgo.application.runner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.Map;
import org.dradgo.application.artifact.ActorContext;
import org.dradgo.application.runner.spi.RunnerExecutionEventPort;
import org.dradgo.application.runner.spi.RunnerExecutionRecordPort;
import org.dradgo.application.runner.spi.RunnerExecutionSnapshot;
import org.dradgo.application.runner.spi.RunnerScratchStore;
import org.dradgo.application.workflow.WorkflowTransitionService;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.RunnerExecutionStatus;
import org.dradgo.domain.registry.RunnerStage;
import org.dradgo.domain.registry.WorkflowEventType;
import org.dradgo.domain.registry.WorkflowState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Story 3d-3 (AC3/AC8) — fast unit coverage for {@link ManualExecutionDispatcher#park}. Verifies
 * the ordered park sequence (insert awaiting_manual row → compose+write bundle → append
 * manual.executionRequested → transition to WaitingForManualExecution) and that a compose/write
 * failure aborts BEFORE the event + transition (so a real transaction rolls back with no
 * half-event, no orphaned transition).
 */
class ManualExecutionDispatcherTest {

  private static final String RUN_ID = "run_manualpark01";

  private RunnerExecutionRecordPort recordPort;
  private RunnerExecutionEventPort eventPort;
  private RunnerBroker runnerBroker;
  private RunnerScratchStore scratchStore;
  private WorkflowTransitionService workflowTransitionService;
  private ManualExecutionDispatcher dispatcher;

  @BeforeEach
  void setUp() {
    recordPort = mock(RunnerExecutionRecordPort.class);
    eventPort = mock(RunnerExecutionEventPort.class);
    runnerBroker = mock(RunnerBroker.class);
    scratchStore = mock(RunnerScratchStore.class);
    workflowTransitionService = mock(WorkflowTransitionService.class);
    dispatcher =
        new ManualExecutionDispatcher(
            recordPort, eventPort, runnerBroker, scratchStore, workflowTransitionService);
  }

  @Test
  void parkRecordsRowComposesBundleAppendsEventAndTransitions() {
    RunnerExecutionSnapshot snapshot = mock(RunnerExecutionSnapshot.class);
    when(snapshot.publicId()).thenReturn("rex_manualpark01");
    when(snapshot.timeoutAt()).thenReturn(OffsetDateTime.parse("2026-06-22T00:00:00Z"));
    when(snapshot.contextBundleVersion()).thenReturn(3);
    when(recordPort.insertAwaitingManualAllocated(
            any(), eq(RUN_ID), eq(RunnerStage.EXECUTION), any()))
        .thenReturn(snapshot);
    when(runnerBroker.composeAndWriteManualBundle(
            eq(RUN_ID), eq(RunnerStage.EXECUTION), any(), eq(3), any()))
        .thenReturn(Path.of("bundle.json"));
    when(eventPort.append(
            eq(RUN_ID),
            eq(WorkflowEventType.MANUAL_EXECUTION_REQUESTED),
            any(),
            any(),
            any(),
            any(),
            any()))
        .thenReturn("evt_manualreq01");

    ActorContext actor = new ActorContext("system", ActorType.SYSTEM, "corr-1");
    RunnerDispatchResult result = dispatcher.park(RUN_ID, RunnerStage.EXECUTION, actor);

    assertThat(result).isInstanceOf(RunnerDispatchResult.Parked.class);
    assertThat(result.isParked()).isTrue();
    assertThat(result.handle().runnerExecutionId()).isEqualTo("rex_manualpark01");
    assertThat(result.handle().status()).isEqualTo(RunnerExecutionStatus.AWAITING_MANUAL);
    assertThat(((RunnerDispatchResult.Parked) result).manualEventPublicId())
        .isEqualTo("evt_manualreq01");

    // The manual.executionRequested event carries only the existing allow-listed detail keys, with
    // runnerKind = manual (AC4 / R3).
    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, Object>> details = ArgumentCaptor.forClass(Map.class);
    verify(eventPort)
        .append(
            eq(RUN_ID),
            eq(WorkflowEventType.MANUAL_EXECUTION_REQUESTED),
            any(),
            any(),
            any(),
            any(),
            details.capture());
    assertThat(details.getValue()).containsEntry("runnerKind", "manual");
    assertThat(details.getValue()).containsEntry("workflowRunId", RUN_ID);
    assertThat(details.getValue()).containsKey("runnerExecutionId");

    // Transition parks the run in WaitingForManualExecution.
    verify(workflowTransitionService)
        .transition(
            eq(RUN_ID),
            eq(WorkflowState.WAITING_FOR_MANUAL_EXECUTION),
            any(),
            eq("manual_execution_requested"),
            any());
  }

  @Test
  void parkDeletesWrittenBundleWhenEventAppendFails() {
    Path bundle = Path.of("bundle.json");
    RunnerExecutionSnapshot snapshot = mock(RunnerExecutionSnapshot.class);
    when(snapshot.publicId()).thenReturn("rex_manualpark03");
    when(snapshot.contextBundleVersion()).thenReturn(4);
    when(recordPort.insertAwaitingManualAllocated(
            any(), eq(RUN_ID), eq(RunnerStage.EXECUTION), any()))
        .thenReturn(snapshot);
    when(runnerBroker.composeAndWriteManualBundle(
            eq(RUN_ID), eq(RunnerStage.EXECUTION), any(), eq(4), any()))
        .thenReturn(bundle);
    when(eventPort.append(any(), any(), any(), any(), any(), any(), any()))
        .thenThrow(new IllegalStateException("event append failed"));

    assertThatThrownBy(
            () ->
                dispatcher.park(
                    RUN_ID,
                    RunnerStage.EXECUTION,
                    new ActorContext("system", ActorType.SYSTEM, "corr-3")))
        .isInstanceOf(IllegalStateException.class);

    verify(scratchStore).deleteContextBundle(argThat(id -> id != null && id.startsWith("rex_")));
    verify(workflowTransitionService, never()).transition(any(), any(), any(), any(), any());
  }

  @Test
  void parkedResultRejectsMissingManualEventAnchor() {
    RunnerExecutionHandle handle =
        new RunnerExecutionHandle(
            "rex_manualpark04",
            RUN_ID,
            RunnerStage.EXECUTION,
            RunnerExecutionStatus.AWAITING_MANUAL,
            OffsetDateTime.parse("2026-06-22T00:00:00Z"));

    assertThatThrownBy(() -> new RunnerDispatchResult.Parked(handle, null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("manualEventPublicId");
  }

  @Test
  void parkAbortsBeforeEventAndTransitionWhenBundleCompositionFails() {
    RunnerExecutionSnapshot snapshot = mock(RunnerExecutionSnapshot.class);
    when(snapshot.publicId()).thenReturn("rex_manualpark02");
    when(snapshot.timeoutAt()).thenReturn(OffsetDateTime.parse("2026-06-22T00:00:00Z"));
    when(snapshot.contextBundleVersion()).thenReturn(1);
    when(recordPort.insertAwaitingManualAllocated(
            any(), eq(RUN_ID), eq(RunnerStage.INVESTIGATION), any()))
        .thenReturn(snapshot);
    when(runnerBroker.composeAndWriteManualBundle(
            eq(RUN_ID), eq(RunnerStage.INVESTIGATION), any(), anyInt(), any()))
        .thenThrow(
            new DomainException(
                DomainErrorCode.INTERNAL_ERROR, "Repository workspace preparation failed"));

    ActorContext actor = new ActorContext("system", ActorType.SYSTEM, "corr-2");
    assertThatThrownBy(() -> dispatcher.park(RUN_ID, RunnerStage.INVESTIGATION, actor))
        .isInstanceOf(DomainException.class);

    // No half-event, no orphaned transition — the real caller transaction rolls back the inserted
    // awaiting_manual row.
    verifyNoInteractions(eventPort);
    verify(workflowTransitionService, never()).transition(any(), any(), any(), any(), any());
  }
}
