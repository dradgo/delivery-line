package org.dradgo.application.workflow;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.Optional;
import org.dradgo.application.artifact.ActorContext;
import org.dradgo.application.runner.RunnerBroker;
import org.dradgo.application.runner.RunnerDispatchAck;
import org.dradgo.application.runner.RunnerDispatchResult;
import org.dradgo.application.runner.RunnerExecutionHandle;
import org.dradgo.application.runner.RunnerProperties;
import org.dradgo.application.workflow.spi.WorkflowRunReadPort;
import org.dradgo.application.workflow.spi.WorkflowRunSnapshot;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.RunnerExecutionStatus;
import org.dradgo.domain.registry.RunnerStage;
import org.dradgo.domain.registry.WorkflowState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Story 3a-1 — unit coverage for {@link WorkflowOrchestrationService} (AC1/AC5/AC6/AC9). */
class WorkflowOrchestrationServiceTest {

  private static final String RUN_ID = "run_orch12345678";
  private static final String REX_ID = "rex_orch12345678";

  private RunnerBroker runnerBroker;
  private WorkflowTransitionService transitionService;
  private WorkflowRunReadPort readPort;
  private org.dradgo.application.runner.spi.RunnerExecutionRecordPort recordPort;

  @BeforeEach
  void setUp() {
    runnerBroker = mock(RunnerBroker.class);
    transitionService = mock(WorkflowTransitionService.class);
    readPort = mock(WorkflowRunReadPort.class);
    recordPort = mock(org.dradgo.application.runner.spi.RunnerExecutionRecordPort.class);
  }

  private WorkflowOrchestrationService service(boolean autoDispatch) {
    RunnerProperties props =
        new RunnerProperties(
            2.0d,
            java.util.Map.of(),
            10_000L,
            50,
            60_000L,
            5_000L,
            RunnerProperties.Recovery.defaults(),
            RunnerProperties.Mock.defaults(),
            RunnerProperties.Scheduling.defaults(),
            RunnerProperties.Docker.defaults(),
            RunnerProperties.defaultSecretEnvNames(),
            false,
            new RunnerProperties.SpecStage(
                org.dradgo.domain.registry.RunnerKind.CODEX, autoDispatch));
    return new WorkflowOrchestrationService(
        runnerBroker, transitionService, readPort, recordPort, props);
  }

  private void stubRun(WorkflowState state, int rejectionLoopCount) {
    when(readPort.findByPublicId(RUN_ID))
        .thenReturn(
            Optional.of(
                new WorkflowRunSnapshot(RUN_ID, state, null, 1L, rejectionLoopCount, false)));
  }

  private RunnerDispatchResult dispatched() {
    return new RunnerDispatchResult.Dispatched(
        new RunnerExecutionHandle(
            REX_ID,
            RUN_ID,
            RunnerStage.INVESTIGATION,
            RunnerExecutionStatus.PENDING,
            OffsetDateTime.parse("2026-06-02T12:00:00Z")),
        new RunnerDispatchAck("mock:happy-spec"));
  }

  @Test
  void dispatchSpecGenerationEnsuresInvestigatingAndDispatchesWithLoopZeroKey() {
    stubRun(WorkflowState.INBOX, 0);
    when(runnerBroker.dispatch(eq(RUN_ID), eq(RunnerStage.INVESTIGATION), any(), any()))
        .thenReturn(dispatched());

    RunnerDispatchResult result = service(true).dispatchSpecGeneration(RUN_ID, "corr-1");

    assertSame(RunnerExecutionStatus.PENDING, result.handle().status());
    // Inbox -> Investigating transition fired (ADR 0004 direct trigger).
    verify(transitionService)
        .transition(
            eq(RUN_ID),
            eq(WorkflowState.INVESTIGATING),
            any(),
            eq("spec_dispatch"),
            eq("spec-investigating:" + RUN_ID));
    // Dispatch carries the loop-0 idempotency key + correlationId-bearing system actor.
    ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<ActorContext> actor = ArgumentCaptor.forClass(ActorContext.class);
    verify(runnerBroker)
        .dispatch(eq(RUN_ID), eq(RunnerStage.INVESTIGATION), key.capture(), actor.capture());
    org.junit.jupiter.api.Assertions.assertEquals("spec-dispatch:" + RUN_ID + ":0", key.getValue());
    org.junit.jupiter.api.Assertions.assertEquals("corr-1", actor.getValue().correlationId());
  }

  @Test
  void dispatchSpecGenerationDoesNotReTransitionWhenAlreadyInvestigating() {
    stubRun(WorkflowState.INVESTIGATING, 0);
    when(runnerBroker.dispatch(any(), any(), any(), any())).thenReturn(dispatched());

    service(true).dispatchSpecGeneration(RUN_ID, null);

    verify(transitionService, never()).transition(any(), any(), any(), any(), any());
    verify(runnerBroker).dispatch(eq(RUN_ID), eq(RunnerStage.INVESTIGATION), any(), any());
  }

  @Test
  void retrySpecGenerationReDispatchesWithBumpedLoopKeyAndNeverTransitions() {
    stubRun(WorkflowState.INVESTIGATING, 2);
    when(runnerBroker.dispatch(any(), any(), any(), any())).thenReturn(dispatched());

    service(true).retrySpecGeneration(RUN_ID, "corr-r");

    verify(transitionService, never()).transition(any(), any(), any(), any(), any());
    ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
    verify(runnerBroker).dispatch(eq(RUN_ID), eq(RunnerStage.INVESTIGATION), key.capture(), any());
    org.junit.jupiter.api.Assertions.assertEquals("spec-dispatch:" + RUN_ID + ":2", key.getValue());
  }

  @Test
  void dispatchAndRetryAreNoOpsWhenAutoDispatchDisabled() {
    WorkflowOrchestrationService disabled = service(false);

    assertNull(disabled.dispatchSpecGeneration(RUN_ID, "c"));
    assertNull(disabled.retrySpecGeneration(RUN_ID, "c"));

    verifyNoInteractions(runnerBroker);
    verifyNoInteractions(transitionService);
    verifyNoInteractions(readPort);
  }

  @Test
  void onSpecStageSucceededTransitionsToWaitingForSpecApproval() {
    service(true).onSpecStageSucceeded(RUN_ID, REX_ID, "corr-s");

    verify(transitionService)
        .transition(
            eq(RUN_ID),
            eq(WorkflowState.WAITING_FOR_SPEC_APPROVAL),
            any(),
            eq("spec_ready"),
            eq("spec-ready:" + REX_ID),
            any(java.util.Map.class));
  }

  @Test
  void onSpecStageSucceededSwallowsIllegalTransition() {
    // The success callback is NOT gated by auto-dispatch and must be idempotent: a duplicate
    // result whose run already left Investigating surfaces ILLEGAL_TRANSITION, which is swallowed.
    org.mockito.Mockito.doThrow(
            new DomainException(DomainErrorCode.ILLEGAL_TRANSITION, "already advanced"))
        .when(transitionService)
        .transition(any(), any(), any(), any(), any(), any(java.util.Map.class));

    // No exception escapes.
    service(false).onSpecStageSucceeded(RUN_ID, REX_ID, "corr-s");
  }
}
