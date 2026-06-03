package org.dradgo.application.workflow;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;

/** Story 3a-1 — unit coverage for {@link WorkflowOrchestrationService} (AC1/AC5/AC6/AC9). */
class WorkflowOrchestrationServiceTest {

  private static final String RUN_ID = "run_orch12345678";
  private static final String REX_ID = "rex_orch12345678";

  private RunnerBroker runnerBroker;
  private WorkflowTransitionService transitionService;
  private WorkflowRunReadPort readPort;
  private org.dradgo.application.runner.spi.RunnerExecutionRecordPort recordPort;
  private ListAppender<ILoggingEvent> logAppender;

  @BeforeEach
  void setUp() {
    runnerBroker = mock(RunnerBroker.class);
    transitionService = mock(WorkflowTransitionService.class);
    readPort = mock(WorkflowRunReadPort.class);
    recordPort = mock(org.dradgo.application.runner.spi.RunnerExecutionRecordPort.class);
    Logger logger = (Logger) LoggerFactory.getLogger(WorkflowOrchestrationService.class);
    logAppender = new ListAppender<>();
    logAppender.start();
    logger.addAppender(logAppender);
    logger.setLevel(Level.DEBUG);
  }

  @AfterEach
  void tearDown() {
    ((Logger) LoggerFactory.getLogger(WorkflowOrchestrationService.class))
        .detachAppender(logAppender);
  }

  private void assertLoggedAt(Level level, String fragment) {
    boolean found =
        logAppender.list.stream()
            .filter(event -> event.getLevel() == level)
            .anyMatch(event -> event.getFormattedMessage().contains(fragment));
    assertTrue(
        found,
        () ->
            "Expected "
                + level
                + " log containing \""
                + fragment
                + "\" but saw: "
                + logAppender.list.stream()
                    .map(e -> e.getLevel() + " " + e.getFormattedMessage())
                    .toList());
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
  void onSpecStageSucceededSwallowsIllegalTransitionAsBenignReplayWhenAlreadySpecReady() {
    // Review finding P3 — a duplicate/replayed result whose run already reached
    // WaitingForSpecApproval surfaces ILLEGAL_TRANSITION; that is a benign idempotent replay and is
    // swallowed (no exception escapes). The state re-read classifies it as replay, logged at INFO.
    org.mockito.Mockito.doThrow(
            new DomainException(DomainErrorCode.ILLEGAL_TRANSITION, "already advanced"))
        .when(transitionService)
        .transition(any(), any(), any(), any(), any(), any(java.util.Map.class));
    when(readPort.findByPublicId(RUN_ID))
        .thenReturn(
            Optional.of(
                new WorkflowRunSnapshot(
                    RUN_ID, WorkflowState.WAITING_FOR_SPEC_APPROVAL, null, 1L, 0, false)));

    service(false).onSpecStageSucceeded(RUN_ID, REX_ID, "corr-s");

    verify(readPort).findByPublicId(RUN_ID);
    // The branch is observable: benign replay is logged at INFO, NOT the WARN anomaly line.
    assertLoggedAt(Level.INFO, "idempotent replay");
  }

  @Test
  void onSpecStageSucceededSwallowsIllegalTransitionAsAnomalyWhenRunDiverged() {
    // Review finding P3 — when the run is in an unexpected state (e.g. TAKEN_OVER) the
    // ILLEGAL_TRANSITION is logged at WARN as a probable anomaly rather than the INFO benign-replay
    // line. It is still swallowed: the runner succeeded + the execution is already COMPLETED in the
    // shared poller transaction, so rethrowing would roll that back and cause infinite re-harvest.
    org.mockito.Mockito.doThrow(
            new DomainException(DomainErrorCode.ILLEGAL_TRANSITION, "illegal from TAKEN_OVER"))
        .when(transitionService)
        .transition(any(), any(), any(), any(), any(), any(java.util.Map.class));
    when(readPort.findByPublicId(RUN_ID))
        .thenReturn(
            Optional.of(
                new WorkflowRunSnapshot(RUN_ID, WorkflowState.TAKEN_OVER, null, 1L, 0, false)));

    // No exception escapes even on a probable anomaly.
    service(false).onSpecStageSucceeded(RUN_ID, REX_ID, "corr-s");

    verify(readPort).findByPublicId(RUN_ID);
    // The branch is observable: anomaly is logged at WARN, NOT the INFO benign-replay line.
    assertLoggedAt(Level.WARN, "probable anomaly");
  }

  @Test
  void onSpecStageSucceededLogsAnomalyWarnForBenignPostSpecReadyDivergence() {
    // Review finding P3 (best-effort classification, resolved Option 1) — a run that GENUINELY
    // reached spec-ready and then legitimately moved on (here: rejected back to Investigating via
    // the reject->retry loop) is, on a late/duplicate harvest, classified from the current snapshot
    // alone. INVESTIGATING is not in SPEC_READY_OR_BEYOND, so it falls into the WARN anomaly branch
    // even though the replay is benign. This documents the known, accepted false-positive (WARN,
    // not ERROR) — the signal is advisory and the outcome is still a safe swallow.
    org.mockito.Mockito.doThrow(
            new DomainException(DomainErrorCode.ILLEGAL_TRANSITION, "illegal from INVESTIGATING"))
        .when(transitionService)
        .transition(any(), any(), any(), any(), any(), any(java.util.Map.class));
    when(readPort.findByPublicId(RUN_ID))
        .thenReturn(
            Optional.of(
                new WorkflowRunSnapshot(RUN_ID, WorkflowState.INVESTIGATING, null, 1L, 1, false)));

    service(false).onSpecStageSucceeded(RUN_ID, REX_ID, "corr-s");

    assertLoggedAt(Level.WARN, "probable anomaly");
  }

  @Test
  void onSpecStageSucceededSwallowsIllegalTransitionWhenRunVanished() {
    // Review finding P3 — a run that cannot be re-read (vanished) is treated as a probable anomaly
    // and swallowed without throwing (defends the empty-Optional path).
    org.mockito.Mockito.doThrow(new DomainException(DomainErrorCode.ILLEGAL_TRANSITION, "gone"))
        .when(transitionService)
        .transition(any(), any(), any(), any(), any(), any(java.util.Map.class));
    when(readPort.findByPublicId(RUN_ID)).thenReturn(Optional.empty());

    service(false).onSpecStageSucceeded(RUN_ID, REX_ID, "corr-s");

    assertLoggedAt(Level.WARN, "<not_found>");
  }

  @Test
  void onSpecStageSucceededSwallowsDiagnosticReadFailureWithoutRethrowing() {
    // Review finding P3 re-read guard — if the diagnostic re-read itself throws (DB/connection
    // error) it must NOT propagate: the call site shares the poller per-item transaction that
    // already committed the runner completion, so an escape would roll that back and cause infinite
    // re-harvest. The read failure is swallowed (logged WARN) and the method returns normally.
    org.mockito.Mockito.doThrow(new DomainException(DomainErrorCode.ILLEGAL_TRANSITION, "illegal"))
        .when(transitionService)
        .transition(any(), any(), any(), any(), any(), any(java.util.Map.class));
    when(readPort.findByPublicId(RUN_ID))
        .thenThrow(new DomainException(DomainErrorCode.INTERNAL_ERROR, "db down"));

    // No exception escapes even though the re-read threw.
    service(false).onSpecStageSucceeded(RUN_ID, REX_ID, "corr-s");

    assertLoggedAt(Level.WARN, "could not re-read run state");
  }
}
