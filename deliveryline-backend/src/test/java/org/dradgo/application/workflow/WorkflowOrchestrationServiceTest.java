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
import org.dradgo.application.runner.ContextBundleService;
import org.dradgo.application.runner.ExecutionSubStage;
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
  private ContextBundleService contextBundleService;
  // Story 3.16 — completion-sync collaborators.
  private org.springframework.beans.factory.ObjectProvider<
          org.dradgo.application.integration.linear.LinearAdapter>
      linearAdapterProvider;
  private org.dradgo.application.integration.linear.LinearAdapter linearAdapter;
  private org.dradgo.application.security.RedactionPolicyService redactionPolicyService;
  private org.dradgo.application.integration.IntegrationLinkService integrationLinkService;
  private org.dradgo.application.artifact.spi.ArtifactRecordPort artifactRecordPort;
  private org.dradgo.application.approval.spi.ApprovalReadPort approvalReadPort;
  private org.dradgo.application.workflow.spi.WorkflowEventReadPort workflowEventReadPort;
  private org.dradgo.application.workflow.spi.WorkflowEventWritePort workflowEventWritePort;
  private WorkflowProperties workflowProperties;
  private ListAppender<ILoggingEvent> logAppender;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    runnerBroker = mock(RunnerBroker.class);
    transitionService = mock(WorkflowTransitionService.class);
    readPort = mock(WorkflowRunReadPort.class);
    recordPort = mock(org.dradgo.application.runner.spi.RunnerExecutionRecordPort.class);
    contextBundleService = mock(ContextBundleService.class);
    linearAdapterProvider = mock(org.springframework.beans.factory.ObjectProvider.class);
    linearAdapter = mock(org.dradgo.application.integration.linear.LinearAdapter.class);
    when(linearAdapterProvider.getIfAvailable()).thenReturn(linearAdapter);
    redactionPolicyService = mock(org.dradgo.application.security.RedactionPolicyService.class);
    integrationLinkService = mock(org.dradgo.application.integration.IntegrationLinkService.class);
    artifactRecordPort = mock(org.dradgo.application.artifact.spi.ArtifactRecordPort.class);
    approvalReadPort = mock(org.dradgo.application.approval.spi.ApprovalReadPort.class);
    workflowEventReadPort = mock(org.dradgo.application.workflow.spi.WorkflowEventReadPort.class);
    workflowEventWritePort = mock(org.dradgo.application.workflow.spi.WorkflowEventWritePort.class);
    workflowProperties =
        WorkflowProperties.defaults(); // completion-sync enabled + default template
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
                org.dradgo.domain.registry.RunnerKind.CODEX, autoDispatch),
            // Story 3.11 — the plan-stage switch tracks the same flag so this helper drives both
            // the
            // spec and plan auto-dispatch paths.
            new RunnerProperties.PlanStage(
                org.dradgo.domain.registry.RunnerKind.CODEX, autoDispatch),
            // Story 3.12 — the implementation-stage switch tracks the same flag so this helper also
            // drives the pr-output (dispatchImplementation / retryImplementation) auto-dispatch
            // path.
            new RunnerProperties.ImplementationStage(
                org.dradgo.domain.registry.RunnerKind.CODEX, autoDispatch));
    return new WorkflowOrchestrationService(
        runnerBroker,
        transitionService,
        readPort,
        recordPort,
        props,
        contextBundleService,
        linearAdapterProvider,
        redactionPolicyService,
        workflowProperties,
        integrationLinkService,
        artifactRecordPort,
        approvalReadPort,
        workflowEventReadPort,
        workflowEventWritePort);
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

  // ===== Story 3.11 — plan-stage orchestration (AC1/AC3/AC5/AC6/AC10) =====

  private RunnerDispatchResult executionDispatched() {
    return new RunnerDispatchResult.Dispatched(
        new RunnerExecutionHandle(
            REX_ID,
            RUN_ID,
            RunnerStage.EXECUTION,
            RunnerExecutionStatus.PENDING,
            OffsetDateTime.parse("2026-06-09T12:00:00Z")),
        new RunnerDispatchAck("mock:happy-implementation-plan"));
  }

  private org.dradgo.application.runner.spi.RunnerExecutionSnapshot executionSnapshot(
      RunnerExecutionStatus status) {
    OffsetDateTime now = OffsetDateTime.parse("2026-06-09T12:00:00Z");
    return new org.dradgo.application.runner.spi.RunnerExecutionSnapshot(
        REX_ID,
        RUN_ID,
        RunnerStage.EXECUTION,
        status,
        1,
        now,
        now.plusSeconds(600),
        null,
        null,
        now,
        null);
  }

  @Test
  void dispatchPlanGenerationDispatchesWithBundleVersionKeyAndNeverTransitions() {
    // AC1 (Trap T1): the run is already Executing (approveSpec transitioned it) — dispatchPlan must
    // NOT transition. The key derives from the next EXECUTION context-bundle version (Decision D2).
    stubRun(WorkflowState.EXECUTING, 0);
    when(recordPort.nextContextBundleVersion(RUN_ID, RunnerStage.EXECUTION)).thenReturn(1);
    when(runnerBroker.dispatch(eq(RUN_ID), eq(RunnerStage.EXECUTION), any(), any()))
        .thenReturn(executionDispatched());

    RunnerDispatchResult result = service(true).dispatchPlanGeneration(RUN_ID, "corr-p");

    assertSame(RunnerExecutionStatus.PENDING, result.handle().status());
    verify(transitionService, never()).transition(any(), any(), any(), any(), any());
    ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<ActorContext> actor = ArgumentCaptor.forClass(ActorContext.class);
    verify(runnerBroker)
        .dispatch(eq(RUN_ID), eq(RunnerStage.EXECUTION), key.capture(), actor.capture());
    org.junit.jupiter.api.Assertions.assertEquals("plan-dispatch:" + RUN_ID + ":1", key.getValue());
    org.junit.jupiter.api.Assertions.assertEquals("corr-p", actor.getValue().correlationId());
    // Logging contract: entry + dispatch-outcome INFO surfaces are observable.
    assertLoggedAt(Level.INFO, "dispatchPlanGeneration entry");
    assertLoggedAt(Level.INFO, "dispatchPlanGeneration dispatched");
  }

  @Test
  void retryPlanGenerationReDispatchesWithFreshBundleVersionKeyAndNeverTransitions() {
    // AC5 — a recovery retry advances the monotonic EXECUTION bundle version so the broker mints a
    // fresh runnerExecutionId; re-dispatch ONLY, never re-transition (T1/T8).
    stubRun(WorkflowState.EXECUTING, 0);
    when(recordPort.nextContextBundleVersion(RUN_ID, RunnerStage.EXECUTION)).thenReturn(2);
    when(runnerBroker.dispatch(any(), any(), any(), any())).thenReturn(executionDispatched());

    service(true).retryPlanGeneration(RUN_ID, "corr-r");

    verify(transitionService, never()).transition(any(), any(), any(), any(), any());
    ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
    verify(runnerBroker).dispatch(eq(RUN_ID), eq(RunnerStage.EXECUTION), key.capture(), any());
    org.junit.jupiter.api.Assertions.assertEquals("plan-dispatch:" + RUN_ID + ":2", key.getValue());
  }

  @Test
  void dispatchPlanGenerationIsNoOpWhenAnExecutionIsAlreadyInFlight() {
    // AC6 — a pending/running EXECUTION execution in the SAME sub-stage is a no-op returning the
    // existing handle; never double-dispatch. The sub-stage-aware guard re-derives the run's
    // sub-stage (Task 2): a plan dispatch only no-ops against an in-flight IMPLEMENTATION_PLAN.
    stubRun(WorkflowState.EXECUTING, 0);
    when(contextBundleService.deriveExecutionSubStage(RUN_ID))
        .thenReturn(ExecutionSubStage.IMPLEMENTATION_PLAN);
    when(recordPort.findByWorkflowRunPublicIdAndStatusIn(eq(RUN_ID), any()))
        .thenReturn(java.util.List.of(executionSnapshot(RunnerExecutionStatus.PENDING)));

    RunnerDispatchResult result = service(true).dispatchPlanGeneration(RUN_ID, "corr-p");

    assertTrue(result.isReplay());
    verify(runnerBroker, never()).dispatch(any(), any(), any(), any());
    assertLoggedAt(Level.WARN, "in-flight no-op");
  }

  @Test
  void dispatchAndRetryPlanAreNoOpsWhenPlanAutoDispatchDisabled() {
    WorkflowOrchestrationService disabled = service(false);

    assertNull(disabled.dispatchPlanGeneration(RUN_ID, "c"));
    assertNull(disabled.retryPlanGeneration(RUN_ID, "c"));

    verifyNoInteractions(runnerBroker);
    verifyNoInteractions(transitionService);
    verifyNoInteractions(readPort);
  }

  @Test
  void onPlanStageSucceededTransitionsToWaitingForReview() {
    service(true).onPlanStageSucceeded(RUN_ID, REX_ID, "corr-p");

    verify(transitionService)
        .transition(
            eq(RUN_ID),
            eq(WorkflowState.WAITING_FOR_REVIEW),
            any(),
            eq("implementation_plan_ready"),
            eq("plan-ready:" + REX_ID),
            any(java.util.Map.class));
    assertLoggedAt(Level.INFO, "to=WaitingForReview");
  }

  @Test
  void onPlanStageSucceededSwallowsIllegalTransitionAsBenignReplayWhenAlreadyAtReview() {
    // AC3 — a duplicate/replayed result whose run already reached WaitingForReview is a benign
    // idempotent replay: swallowed, logged INFO (not the WARN anomaly line).
    org.mockito.Mockito.doThrow(
            new DomainException(DomainErrorCode.ILLEGAL_TRANSITION, "already advanced"))
        .when(transitionService)
        .transition(any(), any(), any(), any(), any(), any(java.util.Map.class));
    when(readPort.findByPublicId(RUN_ID))
        .thenReturn(
            Optional.of(
                new WorkflowRunSnapshot(
                    RUN_ID, WorkflowState.WAITING_FOR_REVIEW, null, 1L, 0, false)));

    service(false).onPlanStageSucceeded(RUN_ID, REX_ID, "corr-p");

    verify(readPort).findByPublicId(RUN_ID);
    assertLoggedAt(Level.INFO, "idempotent replay");
  }

  @Test
  void onPlanStageSucceededSwallowsIllegalTransitionAsAnomalyWhenRunDiverged() {
    // AC3 — when the run is in an unexpected state (e.g. TAKEN_OVER) the ILLEGAL_TRANSITION is
    // logged WARN as a probable anomaly; still swallowed (shared poller tx — rethrow would
    // re-harvest forever).
    org.mockito.Mockito.doThrow(
            new DomainException(DomainErrorCode.ILLEGAL_TRANSITION, "illegal from TAKEN_OVER"))
        .when(transitionService)
        .transition(any(), any(), any(), any(), any(), any(java.util.Map.class));
    when(readPort.findByPublicId(RUN_ID))
        .thenReturn(
            Optional.of(
                new WorkflowRunSnapshot(RUN_ID, WorkflowState.TAKEN_OVER, null, 1L, 0, false)));

    service(false).onPlanStageSucceeded(RUN_ID, REX_ID, "corr-p");

    verify(readPort).findByPublicId(RUN_ID);
    assertLoggedAt(Level.WARN, "probable anomaly");
  }

  @Test
  void onPlanStageSucceededSwallowsDiagnosticReadFailureWithoutRethrowing() {
    // AC3 re-read guard — a diagnostic re-read failure must NOT propagate (shared poller tx).
    org.mockito.Mockito.doThrow(new DomainException(DomainErrorCode.ILLEGAL_TRANSITION, "illegal"))
        .when(transitionService)
        .transition(any(), any(), any(), any(), any(), any(java.util.Map.class));
    when(readPort.findByPublicId(RUN_ID))
        .thenThrow(new DomainException(DomainErrorCode.INTERNAL_ERROR, "db down"));

    service(false).onPlanStageSucceeded(RUN_ID, REX_ID, "corr-p");

    assertLoggedAt(Level.WARN, "could not re-read run state");
  }

  // ===== Story 3.12 — pr-output orchestration (AC1/AC5/AC8 + Task 2 sub-stage guard) =====

  private RunnerDispatchResult prOutputDispatched() {
    return new RunnerDispatchResult.Dispatched(
        new RunnerExecutionHandle(
            REX_ID,
            RUN_ID,
            RunnerStage.EXECUTION,
            RunnerExecutionStatus.PENDING,
            OffsetDateTime.parse("2026-06-10T12:00:00Z")),
        new RunnerDispatchAck("mock:happy-pr-output"));
  }

  @Test
  void dispatchImplementationDispatchesWithPrOutputKeyAndNeverTransitions() {
    // AC1 (Trap T1): the run is already Executing with an approved plan (deriveExecutionSubStage ->
    // PR_OUTPUT) — dispatchImplementation must NOT transition. The key uses the pr-output prefix +
    // the next EXECUTION bundle version (Decision D2).
    stubRun(WorkflowState.EXECUTING, 0);
    when(contextBundleService.deriveExecutionSubStage(RUN_ID))
        .thenReturn(ExecutionSubStage.PR_OUTPUT);
    when(recordPort.nextContextBundleVersion(RUN_ID, RunnerStage.EXECUTION)).thenReturn(2);
    when(runnerBroker.dispatch(eq(RUN_ID), eq(RunnerStage.EXECUTION), any(), any()))
        .thenReturn(prOutputDispatched());

    RunnerDispatchResult result = service(true).dispatchImplementation(RUN_ID, "corr-i");

    assertSame(RunnerExecutionStatus.PENDING, result.handle().status());
    verify(transitionService, never()).transition(any(), any(), any(), any(), any());
    ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<ActorContext> actor = ArgumentCaptor.forClass(ActorContext.class);
    verify(runnerBroker)
        .dispatch(eq(RUN_ID), eq(RunnerStage.EXECUTION), key.capture(), actor.capture());
    org.junit.jupiter.api.Assertions.assertEquals(
        "pr-output-dispatch:" + RUN_ID + ":2", key.getValue());
    org.junit.jupiter.api.Assertions.assertEquals("corr-i", actor.getValue().correlationId());
    assertLoggedAt(Level.INFO, "dispatchImplementation entry");
    assertLoggedAt(Level.INFO, "dispatchImplementation dispatched");
  }

  @Test
  void retryImplementationReDispatchesWithFreshBundleVersionKeyAndNeverTransitions() {
    stubRun(WorkflowState.EXECUTING, 0);
    when(contextBundleService.deriveExecutionSubStage(RUN_ID))
        .thenReturn(ExecutionSubStage.PR_OUTPUT);
    when(recordPort.nextContextBundleVersion(RUN_ID, RunnerStage.EXECUTION)).thenReturn(3);
    when(runnerBroker.dispatch(any(), any(), any(), any())).thenReturn(prOutputDispatched());

    service(true).retryImplementation(RUN_ID, "corr-ir");

    verify(transitionService, never()).transition(any(), any(), any(), any(), any());
    ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
    verify(runnerBroker).dispatch(eq(RUN_ID), eq(RunnerStage.EXECUTION), key.capture(), any());
    org.junit.jupiter.api.Assertions.assertEquals(
        "pr-output-dispatch:" + RUN_ID + ":3", key.getValue());
  }

  @Test
  void dispatchImplementationIsNoOpWhenAPrOutputExecutionIsInFlight() {
    // AC6 / Task 2 — a pending/running pr-output execution (same sub-stage) is a no-op.
    stubRun(WorkflowState.EXECUTING, 0);
    when(contextBundleService.deriveExecutionSubStage(RUN_ID))
        .thenReturn(ExecutionSubStage.PR_OUTPUT);
    when(recordPort.findByWorkflowRunPublicIdAndStatusIn(eq(RUN_ID), any()))
        .thenReturn(java.util.List.of(executionSnapshot(RunnerExecutionStatus.RUNNING)));

    RunnerDispatchResult result = service(true).dispatchImplementation(RUN_ID, "corr-i");

    assertTrue(result.isReplay());
    verify(runnerBroker, never()).dispatch(any(), any(), any(), any());
    assertLoggedAt(Level.WARN, "in-flight no-op");
  }

  @Test
  void subStageAwareGuardDoesNotLetAnInFlightPlanBlockPrOutput() {
    // Task 2 — an in-flight execution whose run currently derives IMPLEMENTATION_PLAN must NOT make
    // a pr-output dispatch a no-op: the sub-stage discriminator differs, so the guard does not
    // treat
    // the active row as in-flight for PR_OUTPUT.
    stubRun(WorkflowState.EXECUTING, 0);
    when(contextBundleService.deriveExecutionSubStage(RUN_ID))
        .thenReturn(ExecutionSubStage.IMPLEMENTATION_PLAN);
    when(recordPort.nextContextBundleVersion(RUN_ID, RunnerStage.EXECUTION)).thenReturn(5);
    when(recordPort.findByWorkflowRunPublicIdAndStatusIn(eq(RUN_ID), any()))
        .thenReturn(java.util.List.of(executionSnapshot(RunnerExecutionStatus.PENDING)));
    when(runnerBroker.dispatch(any(), any(), any(), any())).thenReturn(prOutputDispatched());

    RunnerDispatchResult result = service(true).dispatchImplementation(RUN_ID, "corr-i");

    assertTrue(!result.isReplay());
    verify(runnerBroker).dispatch(eq(RUN_ID), eq(RunnerStage.EXECUTION), any(), any());
  }

  @Test
  void subStageAwareGuardDoesNotLetAnInFlightPrOutputBlockPlan() {
    // Task 2 — the converse: an in-flight execution whose run currently derives PR_OUTPUT must NOT
    // make a plan dispatch a no-op.
    stubRun(WorkflowState.EXECUTING, 0);
    when(contextBundleService.deriveExecutionSubStage(RUN_ID))
        .thenReturn(ExecutionSubStage.PR_OUTPUT);
    when(recordPort.nextContextBundleVersion(RUN_ID, RunnerStage.EXECUTION)).thenReturn(6);
    when(recordPort.findByWorkflowRunPublicIdAndStatusIn(eq(RUN_ID), any()))
        .thenReturn(java.util.List.of(executionSnapshot(RunnerExecutionStatus.PENDING)));
    when(runnerBroker.dispatch(any(), any(), any(), any())).thenReturn(executionDispatched());

    RunnerDispatchResult result = service(true).dispatchPlanGeneration(RUN_ID, "corr-p");

    assertTrue(!result.isReplay());
    verify(runnerBroker).dispatch(eq(RUN_ID), eq(RunnerStage.EXECUTION), any(), any());
  }

  @Test
  void dispatchAndRetryImplementationAreNoOpsWhenImplementationAutoDispatchDisabled() {
    WorkflowOrchestrationService disabled = service(false);

    assertNull(disabled.dispatchImplementation(RUN_ID, "c"));
    assertNull(disabled.retryImplementation(RUN_ID, "c"));

    verifyNoInteractions(runnerBroker);
    verifyNoInteractions(transitionService);
    verifyNoInteractions(readPort);
    verifyNoInteractions(contextBundleService);
  }

  @Test
  void onPrOutputStageSucceededTransitionsToWaitingForReviewWithPrOutputReadyReason() {
    service(true).onPrOutputStageSucceeded(RUN_ID, REX_ID, "corr-i");

    verify(transitionService)
        .transition(
            eq(RUN_ID),
            eq(WorkflowState.WAITING_FOR_REVIEW),
            any(),
            eq("pr_output_ready"),
            eq("pr-output-ready:" + REX_ID),
            any(java.util.Map.class));
    assertLoggedAt(Level.INFO, "reason=pr_output_ready");
  }

  @Test
  void onPrOutputStageSucceededSwallowsIllegalTransitionAsBenignReplayWhenAlreadyAtReview() {
    // AC5 — a duplicate/late pr-output result whose run already reached WaitingForReview is a
    // benign
    // idempotent replay: swallowed, logged INFO (not the WARN anomaly line).
    org.mockito.Mockito.doThrow(
            new DomainException(DomainErrorCode.ILLEGAL_TRANSITION, "already advanced"))
        .when(transitionService)
        .transition(any(), any(), any(), any(), any(), any(java.util.Map.class));
    when(readPort.findByPublicId(RUN_ID))
        .thenReturn(
            Optional.of(
                new WorkflowRunSnapshot(
                    RUN_ID, WorkflowState.WAITING_FOR_REVIEW, null, 1L, 0, false)));

    service(false).onPrOutputStageSucceeded(RUN_ID, REX_ID, "corr-i");

    verify(readPort).findByPublicId(RUN_ID);
    assertLoggedAt(Level.INFO, "idempotent replay");
  }

  @Test
  void onPrOutputStageSucceededSwallowsIllegalTransitionAsAnomalyWhenRunDiverged() {
    // AC5 — an unexpected state (e.g. TAKEN_OVER) is logged WARN as a probable anomaly; still
    // swallowed (shared poller tx — rethrow would re-harvest forever).
    org.mockito.Mockito.doThrow(
            new DomainException(DomainErrorCode.ILLEGAL_TRANSITION, "illegal from TAKEN_OVER"))
        .when(transitionService)
        .transition(any(), any(), any(), any(), any(), any(java.util.Map.class));
    when(readPort.findByPublicId(RUN_ID))
        .thenReturn(
            Optional.of(
                new WorkflowRunSnapshot(RUN_ID, WorkflowState.TAKEN_OVER, null, 1L, 0, false)));

    service(false).onPrOutputStageSucceeded(RUN_ID, REX_ID, "corr-i");

    verify(readPort).findByPublicId(RUN_ID);
    assertLoggedAt(Level.WARN, "probable anomaly");
  }

  // ===============================================================================================
  // Story 3.16 — syncCompletionToLinear (AC2/AC3/AC4/AC5/AC6/AC7).
  // ===============================================================================================

  private static final String TICKET = "LIN-77";

  private org.dradgo.application.integration.IntegrationLink linearLink() {
    return new org.dradgo.application.integration.IntegrationLink(
        "ilk_aaaaaaaaaaaa",
        RUN_ID,
        "linear",
        TICKET,
        org.dradgo.domain.registry.IntegrationSyncStatus.LINKED,
        java.time.Instant.parse("2026-06-02T10:00:00Z"),
        null,
        null);
  }

  private org.dradgo.application.integration.IntegrationLink githubPrLink() {
    return new org.dradgo.application.integration.IntegrationLink(
        "ilk_bbbbbbbbbbbb",
        RUN_ID,
        "github_pr",
        "octo/repo#42",
        org.dradgo.domain.registry.IntegrationSyncStatus.LINKED,
        java.time.Instant.parse("2026-06-02T11:00:00Z"),
        null,
        null);
  }

  private void stubLinks(boolean withPr) {
    when(integrationLinkService.findActiveLinearTicketLink(RUN_ID))
        .thenReturn(Optional.of(linearLink()));
    when(integrationLinkService.findActiveGitHubPrLink(RUN_ID))
        .thenReturn(withPr ? Optional.of(githubPrLink()) : Optional.empty());
    when(artifactRecordPort.findLatestByWorkflowRunIdAndArtifactType(eq(RUN_ID), any()))
        .thenReturn(Optional.empty());
    when(approvalReadPort.findLatestApprovedForArtifactLineage(eq(RUN_ID), any()))
        .thenReturn(Optional.empty());
    when(workflowEventReadPort.findLatestCorrelationId(RUN_ID)).thenReturn(Optional.empty());
    when(workflowEventReadPort.findLatestByWorkflowRunPublicId(RUN_ID))
        .thenReturn(Optional.empty());
    when(workflowEventReadPort.listByWorkflowRunPublicId(eq(RUN_ID), any()))
        .thenReturn(java.util.List.of());
  }

  private void stubRedactionPassthrough() {
    when(redactionPolicyService.redact(
            any(String.class),
            eq(org.dradgo.domain.registry.DataClassification.SHAREABLE_FULL.value())))
        .thenAnswer(
            inv ->
                new org.dradgo.application.security.RedactionResult(
                    inv.getArgument(0),
                    null,
                    org.dradgo.domain.registry.DataClassification.SHAREABLE_FULL,
                    org.dradgo.domain.registry.DataClassification.SHAREABLE_FULL,
                    false,
                    java.util.Set.of()));
  }

  private org.dradgo.application.workflow.spi.WorkflowEventRecord eventAt(
      java.time.OffsetDateTime createdAt) {
    return new org.dradgo.application.workflow.spi.WorkflowEventRecord(
        "evt_000000000000",
        RUN_ID,
        org.dradgo.domain.registry.WorkflowEventType.LINEAR_COMPLETION_SYNC_FAILED,
        null,
        null,
        "system",
        org.dradgo.domain.registry.ActorType.SYSTEM,
        "synthetic",
        null,
        false,
        createdAt,
        java.util.Map.of());
  }

  @Test
  void syncCompletionPostsRedactedShareableFullSummaryWithReconstructedPrUrl() {
    stubLinks(true);
    stubRedactionPassthrough();

    service(false).syncCompletionToLinear(RUN_ID);

    ArgumentCaptor<org.dradgo.application.integration.linear.GovernedRunComment> captor =
        ArgumentCaptor.forClass(org.dradgo.application.integration.linear.GovernedRunComment.class);
    verify(linearAdapter).postGovernedRunComment(eq(TICKET), captor.capture());
    org.dradgo.application.integration.linear.GovernedRunComment posted = captor.getValue();
    org.junit.jupiter.api.Assertions.assertEquals(RUN_ID, posted.runPublicId());
    assertSame(
        org.dradgo.domain.registry.DataClassification.SHAREABLE_FULL, posted.classification());
    assertTrue(
        posted.body().contains("github.com/octo/repo/pull/42"),
        () -> "expected reconstructed PR url in body but was: " + posted.body());
    assertTrue(posted.fingerprint() != null && !posted.fingerprint().isBlank());
    verify(workflowEventWritePort, never()).append(any());
  }

  @Test
  void syncCompletionSendsTheRedactedBodyAndKeepsItShareableOnDirtySource() {
    stubLinks(true);
    // AC2/AC6 — source datum was dirty: redaction scrubbed it AND kept the body shareable-full.
    when(redactionPolicyService.redact(
            any(String.class),
            eq(org.dradgo.domain.registry.DataClassification.SHAREABLE_FULL.value())))
        .thenReturn(
            new org.dradgo.application.security.RedactionResult(
                "DeliveryLine governed run scrubbed completed.",
                null,
                org.dradgo.domain.registry.DataClassification.SHAREABLE_FULL,
                org.dradgo.domain.registry.DataClassification.SHAREABLE_FULL,
                true,
                java.util.Set.of()));

    service(false).syncCompletionToLinear(RUN_ID);

    ArgumentCaptor<org.dradgo.application.integration.linear.GovernedRunComment> captor =
        ArgumentCaptor.forClass(org.dradgo.application.integration.linear.GovernedRunComment.class);
    verify(linearAdapter).postGovernedRunComment(eq(TICKET), captor.capture());
    org.junit.jupiter.api.Assertions.assertEquals(
        "DeliveryLine governed run scrubbed completed.", captor.getValue().body());
    assertSame(
        org.dradgo.domain.registry.DataClassification.SHAREABLE_FULL,
        captor.getValue().classification());
    assertLoggedAt(Level.WARN, "summary_redacted");
  }

  @Test
  void syncCompletionNoOpsWhenNoLinearTicketLinked() {
    when(integrationLinkService.findActiveLinearTicketLink(RUN_ID)).thenReturn(Optional.empty());

    service(false).syncCompletionToLinear(RUN_ID);

    verify(linearAdapter, never()).postGovernedRunComment(any(), any());
    verify(workflowEventWritePort, never()).append(any());
    assertLoggedAt(Level.WARN, "no_linear_ticket_link");
  }

  @Test
  void syncCompletionRecordsFailureEventWithoutThrowingWhenLinearPostFails() {
    stubLinks(true);
    stubRedactionPassthrough();
    org.mockito.Mockito.doThrow(
            new org.dradgo.application.integration.linear.LinearAdapterException(
                org.dradgo.domain.registry.IntegrationFailureCategory.NETWORK_API_FAILURE, "boom"))
        .when(linearAdapter)
        .postGovernedRunComment(eq(TICKET), any());

    service(false).syncCompletionToLinear(RUN_ID); // best-effort: must not throw

    ArgumentCaptor<org.dradgo.application.workflow.spi.WorkflowEventRecord> captor =
        ArgumentCaptor.forClass(org.dradgo.application.workflow.spi.WorkflowEventRecord.class);
    verify(workflowEventWritePort).append(captor.capture());
    org.dradgo.application.workflow.spi.WorkflowEventRecord event = captor.getValue();
    assertSame(
        org.dradgo.domain.registry.WorkflowEventType.LINEAR_COMPLETION_SYNC_FAILED,
        event.eventType());
    org.junit.jupiter.api.Assertions.assertEquals(
        "network_api_failure",
        event.details().get(org.dradgo.domain.registry.WorkflowEventDetailKeys.FAILURE_CATEGORY));
    assertLoggedAt(Level.WARN, "post_failed");
  }

  @Test
  void syncCompletionFingerprintIsStableEvenWhenTheEventTimelineAdvances() {
    // The idempotency basis must EXCLUDE the volatile cycle-time so a re-sync after the run's event
    // timeline advances stays a no-op at the adapter (review 2026-06-11). A prior version of this
    // test stubbed both event reads to empty, so durationFormatted was constantly "n/a" and the
    // equality assertion was vacuous (a pure hash of fixed input is trivially stable).
    stubLinks(true);
    stubRedactionPassthrough();
    WorkflowOrchestrationService svc = service(false);

    java.time.OffsetDateTime first = java.time.OffsetDateTime.parse("2026-06-02T10:00:00Z");

    // First invocation — short timeline (latest = first + 5m → cycle time "5m 0s").
    when(workflowEventReadPort.findLatestByWorkflowRunPublicId(RUN_ID))
        .thenReturn(Optional.of(eventAt(first.plusMinutes(5))));
    when(workflowEventReadPort.listByWorkflowRunPublicId(eq(RUN_ID), any()))
        .thenReturn(java.util.List.of(eventAt(first), eventAt(first.plusMinutes(5))));
    svc.syncCompletionToLinear(RUN_ID);

    // Second invocation — timeline advanced (latest = first + 3h → cycle time "3h 0m 0s").
    when(workflowEventReadPort.findLatestByWorkflowRunPublicId(RUN_ID))
        .thenReturn(Optional.of(eventAt(first.plusHours(3))));
    when(workflowEventReadPort.listByWorkflowRunPublicId(eq(RUN_ID), any()))
        .thenReturn(java.util.List.of(eventAt(first), eventAt(first.plusHours(3))));
    svc.syncCompletionToLinear(RUN_ID);

    ArgumentCaptor<org.dradgo.application.integration.linear.GovernedRunComment> captor =
        ArgumentCaptor.forClass(org.dradgo.application.integration.linear.GovernedRunComment.class);
    verify(linearAdapter, org.mockito.Mockito.times(2))
        .postGovernedRunComment(eq(TICKET), captor.capture());
    java.util.List<org.dradgo.application.integration.linear.GovernedRunComment> posts =
        captor.getAllValues();
    // Precondition (guards against re-vacuum): the advanced timeline really changed the rendered
    // body's cycle time.
    org.junit.jupiter.api.Assertions.assertNotEquals(
        posts.get(0).body(),
        posts.get(1).body(),
        "expected the advanced event timeline to change the rendered cycle time");
    // The idempotency fingerprint stays identical across the advanced timeline (duration excluded).
    org.junit.jupiter.api.Assertions.assertEquals(
        posts.get(0).fingerprint(), posts.get(1).fingerprint());
  }

  @Test
  void syncCompletionSkippedWhenDisabled() {
    workflowProperties =
        new WorkflowProperties(
            WorkflowProperties.Bot.empty(),
            WorkflowProperties.RepoConfig.empty(),
            new WorkflowProperties.LinearCompletionSync(
                false, WorkflowProperties.LinearCompletionSync.DEFAULT_TEMPLATE));

    service(false).syncCompletionToLinear(RUN_ID);

    verifyNoInteractions(integrationLinkService);
    verify(linearAdapter, never()).postGovernedRunComment(any(), any());
  }

  @Test
  void syncCompletionNoOpsWhenLinearAdapterUnavailable() {
    when(linearAdapterProvider.getIfAvailable()).thenReturn(null);
    stubLinks(true);
    stubRedactionPassthrough();

    service(false).syncCompletionToLinear(RUN_ID);

    verify(workflowEventWritePort, never()).append(any());
    assertLoggedAt(Level.WARN, "linear_adapter_unavailable");
  }
}
