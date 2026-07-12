package org.dradgo.application.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import org.dradgo.application.artifact.ActorContext;
import org.dradgo.application.artifact.spi.ArtifactOperationPort;
import org.dradgo.application.idempotency.IdempotencyKeyValidator;
import org.dradgo.application.recovery.spi.RecoveryActionRecordPort;
import org.dradgo.application.recovery.spi.RecoveryActionSnapshot;
import org.dradgo.application.recovery.spi.RecoveryActionWriteCommand;
import org.dradgo.application.runner.RunnerDispatchResult;
import org.dradgo.application.runner.RunnerExecutionHandle;
import org.dradgo.application.runner.queue.RunnerExecutionQueue;
import org.dradgo.application.runner.spi.RunnerExecutionRecordPort;
import org.dradgo.application.workflow.WorkflowCommandService;
import org.dradgo.application.workflow.WorkflowOrchestrationService;
import org.dradgo.application.workflow.commands.ResumeWorkflowCommand;
import org.dradgo.application.workflow.spi.WorkflowEventReadPort;
import org.dradgo.application.workflow.spi.WorkflowEventRecord;
import org.dradgo.application.workflow.spi.WorkflowEventWritePort;
import org.dradgo.application.workflow.spi.WorkflowRunReadPort;
import org.dradgo.application.workflow.spi.WorkflowRunSnapshot;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.RunnerExecutionStatus;
import org.dradgo.domain.registry.RunnerStage;
import org.dradgo.domain.registry.WorkflowEventType;
import org.dradgo.domain.registry.WorkflowState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Story 4.5 (AC10) — unit coverage for {@link RecoveryService#resume}, mirroring the {@code retry}
 * cases in {@link RecoveryServiceUnitTest}: resume from {@code Paused} transitions to the prior
 * executing state + appends {@code recovery.resumed} + inserts the {@code recovery_actions} row
 * (action_type=resume, reviewer_role=workflow_owner) + re-dispatches the runner; resume from a
 * non-{@code Paused} state or a {@code Paused} run with no {@code → Paused} event raises {@code
 * RESUME_NOT_APPLICABLE}; idempotent replay; idempotency conflict; concurrent-replay-on-conflict.
 */
class RecoveryServiceResumeTest {

  private static final String RUN = "run_resume1234";
  private static final String IDEMPOTENCY_KEY = "idem-resume-1234567890";
  private static final String PAUSED_EVENT_ID = "evt_paused-aaaa1";
  private static final OffsetDateTime FIXED_NOW = OffsetDateTime.parse("2026-07-07T12:00:00Z");

  private WorkflowRunReadPort runReadPort;
  private WorkflowEventReadPort eventReadPort;
  private RunnerExecutionRecordPort runnerRecordPort;
  private ArtifactOperationPort artifactOperationPort;
  private WorkflowCommandService workflowCommandService;
  private RunnerExecutionQueue runnerExecutionQueue;
  private WorkflowOrchestrationService workflowOrchestrationService;
  private WorkflowEventWritePort eventWritePort;
  private RecoveryActionRecordPort recoveryRecordPort;
  private RecoveryService service;

  @BeforeEach
  void setUp() {
    runReadPort = mock(WorkflowRunReadPort.class);
    eventReadPort = mock(WorkflowEventReadPort.class);
    runnerRecordPort = mock(RunnerExecutionRecordPort.class);
    artifactOperationPort = mock(ArtifactOperationPort.class);
    workflowCommandService = mock(WorkflowCommandService.class);
    runnerExecutionQueue = mock(RunnerExecutionQueue.class);
    workflowOrchestrationService = mock(WorkflowOrchestrationService.class);
    eventWritePort = mock(WorkflowEventWritePort.class);
    recoveryRecordPort = mock(RecoveryActionRecordPort.class);
    service =
        new RecoveryService(
            runReadPort,
            eventReadPort,
            runnerRecordPort,
            artifactOperationPort,
            workflowCommandService,
            runnerExecutionQueue,
            mock(org.dradgo.application.project.ProjectRuntimeConfigResolver.class),
            mock(org.dradgo.application.runner.ManualExecutionDispatcher.class),
            workflowOrchestrationService,
            eventWritePort,
            recoveryRecordPort,
            new IdempotencyKeyValidator(),
            Clock.fixed(FIXED_NOW.toInstant(), ZoneOffset.UTC),
            callthroughTemplate(),
            callthroughTemplate(),
            mock(org.dradgo.application.approval.ApprovalService.class),
            mock(org.dradgo.application.artifact.spi.ArtifactRecordPort.class),
            mock(org.dradgo.application.workflow.WorkflowTransitionService.class));
  }

  @Test
  void resumeFromPausedTransitionsToPriorStateAppendsEventInsertsRowAndRedispatches() {
    stubPausedRun();
    stubPausedEvent(WorkflowState.EXECUTING);
    when(recoveryRecordPort.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.empty());
    when(recoveryRecordPort.insert(any(RecoveryActionWriteCommand.class)))
        .thenReturn(resumeActionSnapshot("rcv_res-aaaaa", "pending"));
    when(recoveryRecordPort.markSucceeded(IDEMPOTENCY_KEY))
        .thenReturn(resumeActionSnapshot("rcv_res-aaaaa", "succeeded"));
    stubResumeDispatch("rex_res-bbbbb");

    ResumeRecoveryResult result = service.resume(RUN, IDEMPOTENCY_KEY, actor(), null);

    assertNotNull(result);
    assertFalse(result.replayed());
    assertEquals("rcv_res-aaaaa", result.recoveryActionPublicId());
    assertEquals("rex_res-bbbbb", result.newRunnerExecutionPublicId());
    assertEquals(WorkflowState.EXECUTING, result.resultingState());
    assertNotNull(result.resumedEventPublicId());
    assertTrue(result.resumedEventPublicId().startsWith("evt_"));

    ArgumentCaptor<ResumeWorkflowCommand> commandCaptor =
        ArgumentCaptor.forClass(ResumeWorkflowCommand.class);
    verify(workflowCommandService, times(1)).resumeWorkflow(commandCaptor.capture());
    assertEquals(RUN, commandCaptor.getValue().workflowRunId());
    assertEquals(IDEMPOTENCY_KEY, commandCaptor.getValue().idempotencyKey());
    assertEquals(WorkflowState.EXECUTING, commandCaptor.getValue().targetState());
    assertEquals("resume to Executing", commandCaptor.getValue().reasonText());

    ArgumentCaptor<WorkflowEventRecord> eventCaptor =
        ArgumentCaptor.forClass(WorkflowEventRecord.class);
    verify(eventWritePort, times(1)).append(eventCaptor.capture());
    WorkflowEventRecord resumedEvent = eventCaptor.getValue();
    assertEquals(WorkflowEventType.RECOVERY_RESUMED, resumedEvent.eventType());
    assertEquals(WorkflowState.PAUSED, resumedEvent.priorState());
    assertEquals(WorkflowState.EXECUTING, resumedEvent.resultingState());
    assertTrue(resumedEvent.interventionMarker());
    assertEquals(PAUSED_EVENT_ID, resumedEvent.details().get("triggeringEventId"));
    assertEquals(IDEMPOTENCY_KEY, resumedEvent.details().get("idempotencyKey"));
    assertEquals("corr-resume-1", resumedEvent.details().get("correlationId"));

    ArgumentCaptor<RecoveryActionWriteCommand> writeCaptor =
        ArgumentCaptor.forClass(RecoveryActionWriteCommand.class);
    verify(recoveryRecordPort, times(1)).insert(writeCaptor.capture());
    assertEquals("resume", writeCaptor.getValue().actionType());
    assertEquals("workflow_owner", writeCaptor.getValue().reviewerRole());
    assertEquals("pending", writeCaptor.getValue().resultStatus());
    assertEquals(IDEMPOTENCY_KEY, writeCaptor.getValue().idempotencyKey());
    assertEquals(PAUSED_EVENT_ID, writeCaptor.getValue().triggeringEventPublicId());

    verify(workflowOrchestrationService, times(1)).redispatchAfterRetry(eq(RUN), any());
    verify(recoveryRecordPort, times(1)).markSucceeded(IDEMPOTENCY_KEY);
    verify(recoveryRecordPort, never()).markFailed(any());
  }

  @Test
  void resumeWithOperatorReasonRoutesReasonIntoCommandAndEventDetails() {
    stubPausedRun();
    stubPausedEvent(WorkflowState.EXECUTING);
    when(recoveryRecordPort.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.empty());
    when(recoveryRecordPort.insert(any(RecoveryActionWriteCommand.class)))
        .thenReturn(resumeActionSnapshot("rcv_res-aaaaa", "pending"));
    when(recoveryRecordPort.markSucceeded(IDEMPOTENCY_KEY))
        .thenReturn(resumeActionSnapshot("rcv_res-aaaaa", "succeeded"));
    stubResumeDispatch("rex_res-bbbbb");

    String operatorReason = "investigation done, safe to continue";
    service.resume(RUN, IDEMPOTENCY_KEY, actor(), operatorReason);

    ArgumentCaptor<ResumeWorkflowCommand> commandCaptor =
        ArgumentCaptor.forClass(ResumeWorkflowCommand.class);
    verify(workflowCommandService).resumeWorkflow(commandCaptor.capture());
    assertEquals(operatorReason, commandCaptor.getValue().reasonText());

    ArgumentCaptor<WorkflowEventRecord> eventCaptor =
        ArgumentCaptor.forClass(WorkflowEventRecord.class);
    verify(eventWritePort).append(eventCaptor.capture());
    assertEquals(operatorReason, eventCaptor.getValue().reason());
    assertEquals(operatorReason, eventCaptor.getValue().details().get("reason"));
  }

  @Test
  void resumeReturnsNullNewRunnerExecutionWhenAutoDispatchOff() {
    // redispatchAfterRetry returns null when the auto-dispatch gate is off (shared test profile);
    // the bare Paused → Executing transition stays observable and the resume still succeeds.
    stubPausedRun();
    stubPausedEvent(WorkflowState.EXECUTING);
    when(recoveryRecordPort.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.empty());
    when(recoveryRecordPort.insert(any(RecoveryActionWriteCommand.class)))
        .thenReturn(resumeActionSnapshot("rcv_res-aaaaa", "pending"));
    when(recoveryRecordPort.markSucceeded(IDEMPOTENCY_KEY))
        .thenReturn(resumeActionSnapshot("rcv_res-aaaaa", "succeeded"));
    when(workflowOrchestrationService.redispatchAfterRetry(eq(RUN), any())).thenReturn(null);

    ResumeRecoveryResult result = service.resume(RUN, IDEMPOTENCY_KEY, actor(), null);

    assertFalse(result.replayed());
    assertNull(result.newRunnerExecutionPublicId());
    assertEquals(WorkflowState.EXECUTING, result.resultingState());
    verify(recoveryRecordPort, times(1)).markSucceeded(IDEMPOTENCY_KEY);
  }

  @Test
  void resumeFromInvestigatingPriorStateRedispatchesSpecGeneration() {
    // Story 4.8 — 4.5's previously-unreachable branch is LIVE now that Investigating is a pausable
    // source: resuming into Investigating re-dispatches the spec runner via retrySpecGeneration.
    stubPausedRun();
    stubPausedEvent(WorkflowState.INVESTIGATING);
    when(recoveryRecordPort.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.empty());
    when(recoveryRecordPort.insert(any(RecoveryActionWriteCommand.class)))
        .thenReturn(resumeActionSnapshot("rcv_res-inv01", "pending"));
    when(recoveryRecordPort.markSucceeded(IDEMPOTENCY_KEY))
        .thenReturn(resumeActionSnapshot("rcv_res-inv01", "succeeded"));
    when(workflowOrchestrationService.retrySpecGeneration(eq(RUN), any())).thenReturn(null);

    ResumeRecoveryResult result = service.resume(RUN, IDEMPOTENCY_KEY, actor(), null);

    assertEquals(WorkflowState.INVESTIGATING, result.resultingState());
    verify(workflowOrchestrationService, times(1)).retrySpecGeneration(eq(RUN), any());
    verify(workflowOrchestrationService, never()).redispatchAfterRetry(any(), any());
  }

  @ParameterizedTest
  @EnumSource(
      value = WorkflowState.class,
      names = {
        "WAITING_FOR_SPEC_APPROVAL",
        "WAITING_FOR_REVIEW",
        "WAITING_FOR_MANUAL_EXECUTION",
        "WAITING_FOR_LINT_APPROVAL",
        "WAITING_FOR_DELIVERY",
        "FAILED"
      })
  void resumeFromGateOrFailedPriorStateDispatchesNothing(WorkflowState priorState) {
    // Story 4.8 (AC11) — ALL six gate/failed priorStates carry no runner work: pause left the
    // gate approval / manual park / failure diagnostics intact, so resume just transitions back
    // without any re-dispatch (only EXECUTING/INVESTIGATING re-dispatch, pinned above).
    stubPausedRun();
    stubPausedEvent(priorState);
    when(recoveryRecordPort.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.empty());
    when(recoveryRecordPort.insert(any(RecoveryActionWriteCommand.class)))
        .thenReturn(resumeActionSnapshot("rcv_res-gate1", "pending"));
    when(recoveryRecordPort.markSucceeded(IDEMPOTENCY_KEY))
        .thenReturn(resumeActionSnapshot("rcv_res-gate1", "succeeded"));

    ResumeRecoveryResult result = service.resume(RUN, IDEMPOTENCY_KEY, actor(), null);

    assertEquals(priorState, result.resultingState());
    assertNull(result.newRunnerExecutionPublicId());
    verify(workflowOrchestrationService, never()).redispatchAfterRetry(any(), any());
    verify(workflowOrchestrationService, never()).retrySpecGeneration(any(), any());
  }

  @Test
  void resumeOnNonPausedRaisesResumeNotApplicableWithoutMutatingState() {
    when(recoveryRecordPort.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.empty());
    when(runReadPort.findByPublicId(RUN))
        .thenReturn(
            Optional.of(new WorkflowRunSnapshot(RUN, WorkflowState.EXECUTING, null, 1L, 0, false)));

    DomainException error =
        assertThrows(
            DomainException.class, () -> service.resume(RUN, IDEMPOTENCY_KEY, actor(), null));
    assertEquals(DomainErrorCode.RESUME_NOT_APPLICABLE, error.errorCode());
    assertEquals(RUN, error.details().get("runId"));
    assertEquals("Executing", error.details().get("currentState"));

    verify(workflowCommandService, never()).resumeWorkflow(any());
    verify(eventWritePort, never()).append(any());
    verify(recoveryRecordPort, never()).insert(any(RecoveryActionWriteCommand.class));
    verify(workflowOrchestrationService, never()).redispatchAfterRetry(any(), any());
  }

  @Test
  void resumeOnPausedWithoutPausedEventRaisesResumeNotApplicable() {
    when(recoveryRecordPort.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.empty());
    stubPausedRun();
    when(eventReadPort.findLatestTransitionToState(RUN, WorkflowState.PAUSED))
        .thenReturn(Optional.empty());

    DomainException error =
        assertThrows(
            DomainException.class, () -> service.resume(RUN, IDEMPOTENCY_KEY, actor(), null));
    assertEquals(DomainErrorCode.RESUME_NOT_APPLICABLE, error.errorCode());
    assertEquals("no_paused_event_to_link", error.details().get("reason"));

    verify(workflowCommandService, never()).resumeWorkflow(any());
    verify(eventWritePort, never()).append(any());
    verify(recoveryRecordPort, never()).insert(any(RecoveryActionWriteCommand.class));
  }

  @Test
  void resumeRejectsInvalidIdempotencyKeyBeforeAnyPersistenceLookup() {
    DomainException error =
        assertThrows(DomainException.class, () -> service.resume(RUN, "bad key", actor(), null));
    assertEquals(DomainErrorCode.INVALID_IDEMPOTENCY_KEY, error.errorCode());

    verify(recoveryRecordPort, never()).findByIdempotencyKey(any());
    verify(runReadPort, never()).findByPublicId(any());
  }

  @Test
  void resumeWithSucceededPriorActionForSameRunReturnsReplayedResultWithoutSideEffects() {
    when(recoveryRecordPort.findByIdempotencyKey(IDEMPOTENCY_KEY))
        .thenReturn(Optional.of(resumeActionSnapshotWithEvent("rcv_prior-bbbbb", "succeeded")));

    ResumeRecoveryResult result = service.resume(RUN, IDEMPOTENCY_KEY, actor(), null);

    assertTrue(result.replayed());
    assertEquals("rcv_prior-bbbbb", result.recoveryActionPublicId());
    assertEquals("evt_resumed-prior", result.resumedEventPublicId());
    assertNull(result.newRunnerExecutionPublicId());

    verify(runReadPort, never()).findByPublicId(any());
    verify(workflowCommandService, never()).resumeWorkflow(any());
    verify(eventWritePort, never()).append(any());
    verify(recoveryRecordPort, never()).insert(any(RecoveryActionWriteCommand.class));
    verify(workflowOrchestrationService, never()).redispatchAfterRetry(any(), any());
  }

  @Test
  void resumeWithPriorActionForDifferentRunRaisesIdempotencyKeyConflict() {
    RecoveryActionSnapshot prior =
        new RecoveryActionSnapshot(
            "rcv_prior-ccccc",
            42L,
            "run_otherrun9999",
            "resume",
            PAUSED_EVENT_ID,
            "evt_resumed-other",
            "alex",
            ActorType.HUMAN,
            IDEMPOTENCY_KEY,
            "succeeded",
            FIXED_NOW,
            "workflow_owner");
    when(recoveryRecordPort.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.of(prior));

    DomainException error =
        assertThrows(
            DomainException.class, () -> service.resume(RUN, IDEMPOTENCY_KEY, actor(), null));
    assertEquals(DomainErrorCode.IDEMPOTENCY_KEY_CONFLICT, error.errorCode());
    assertEquals("run_otherrun9999", error.details().get("priorRunId"));
    assertEquals(RUN, error.details().get("requestedRunId"));

    verify(runReadPort, never()).findByPublicId(any());
    verify(workflowOrchestrationService, never()).redispatchAfterRetry(any(), any());
  }

  @Test
  void resumeWithPendingPriorActionRaisesIdempotencyKeyConflict() {
    when(recoveryRecordPort.findByIdempotencyKey(IDEMPOTENCY_KEY))
        .thenReturn(Optional.of(resumeActionSnapshot("rcv_prior-ddddd", "pending")));

    DomainException error =
        assertThrows(
            DomainException.class, () -> service.resume(RUN, IDEMPOTENCY_KEY, actor(), null));
    assertEquals(DomainErrorCode.IDEMPOTENCY_KEY_CONFLICT, error.errorCode());
    assertEquals("pending", error.details().get("priorResultStatus"));
  }

  @Test
  void resumeConcurrentLoserSeesReplayWhenWinnerSucceededBetweenChecks() {
    // Concurrent-resume race: the pre-insert findByIdempotencyKey returns empty, but the
    // recovery_actions insert then throws IDEMPOTENCY_KEY_CONFLICT because the winner's row landed
    // in between. By the time we re-read, the winner is `succeeded` — return the documented replay.
    stubPausedRun();
    stubPausedEvent(WorkflowState.EXECUTING);
    RecoveryActionSnapshot winner = resumeActionSnapshotWithEvent("rcv_winner-1234", "succeeded");
    when(recoveryRecordPort.findByIdempotencyKey(IDEMPOTENCY_KEY))
        .thenReturn(Optional.empty())
        .thenReturn(Optional.of(winner));
    when(recoveryRecordPort.insert(any(RecoveryActionWriteCommand.class)))
        .thenThrow(
            new DomainException(
                DomainErrorCode.IDEMPOTENCY_KEY_CONFLICT,
                "recovery_actions unique violation",
                Map.of("idempotencyKey", IDEMPOTENCY_KEY)));

    ResumeRecoveryResult result = service.resume(RUN, IDEMPOTENCY_KEY, actor(), null);

    assertTrue(result.replayed());
    assertEquals("rcv_winner-1234", result.recoveryActionPublicId());
    assertEquals("evt_resumed-prior", result.resumedEventPublicId());
    assertNull(result.newRunnerExecutionPublicId());
    verify(workflowOrchestrationService, never()).redispatchAfterRetry(any(), any());
    verify(recoveryRecordPort, never()).markSucceeded(any());
  }

  @Test
  void resumeConcurrentLoserPropagatesConflictWhenWinnerStillPending() {
    stubPausedRun();
    stubPausedEvent(WorkflowState.EXECUTING);
    RecoveryActionSnapshot winnerPending =
        resumeActionSnapshotWithEvent("rcv_winner-pending", "pending");
    when(recoveryRecordPort.findByIdempotencyKey(IDEMPOTENCY_KEY))
        .thenReturn(Optional.empty())
        .thenReturn(Optional.of(winnerPending));
    when(recoveryRecordPort.insert(any(RecoveryActionWriteCommand.class)))
        .thenThrow(
            new DomainException(
                DomainErrorCode.IDEMPOTENCY_KEY_CONFLICT,
                "recovery_actions unique violation",
                Map.of("idempotencyKey", IDEMPOTENCY_KEY)));

    DomainException error =
        assertThrows(
            DomainException.class, () -> service.resume(RUN, IDEMPOTENCY_KEY, actor(), null));
    assertEquals(DomainErrorCode.IDEMPOTENCY_KEY_CONFLICT, error.errorCode());
    verify(workflowOrchestrationService, never()).redispatchAfterRetry(any(), any());
  }

  @Test
  void resumeDispatchFailureFlipsRecoveryActionToFailedAndAppendsDispatchFailedEvent() {
    stubPausedRun();
    stubPausedEvent(WorkflowState.EXECUTING);
    when(recoveryRecordPort.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.empty());
    when(recoveryRecordPort.insert(any(RecoveryActionWriteCommand.class)))
        .thenReturn(resumeActionSnapshot("rcv_res-ccccc", "pending"));
    when(workflowOrchestrationService.redispatchAfterRetry(eq(RUN), any()))
        .thenThrow(new IllegalStateException("queue enqueue failed"));

    IllegalStateException error =
        assertThrows(
            IllegalStateException.class, () -> service.resume(RUN, IDEMPOTENCY_KEY, actor(), null));
    assertTrue(error.getMessage().contains("queue enqueue failed"));

    verify(recoveryRecordPort, times(1)).markFailed(IDEMPOTENCY_KEY);
    verify(recoveryRecordPort, never()).markSucceeded(any());

    // First append is recovery.resumed (prep tx); second is recovery.dispatchFailed (compensation).
    ArgumentCaptor<WorkflowEventRecord> appended =
        ArgumentCaptor.forClass(WorkflowEventRecord.class);
    verify(eventWritePort, times(2)).append(appended.capture());
    WorkflowEventRecord dispatchFailed = appended.getAllValues().get(1);
    assertEquals(WorkflowEventType.RECOVERY_DISPATCH_FAILED, dispatchFailed.eventType());
    assertEquals("rcv_res-ccccc", dispatchFailed.details().get("recoveryActionId"));
    assertEquals("RUNTIME_ERROR", dispatchFailed.details().get("errorCode"));
    assertEquals("IllegalStateException", dispatchFailed.details().get("errorClass"));
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private void stubPausedRun() {
    when(runReadPort.findByPublicId(RUN))
        .thenReturn(
            Optional.of(new WorkflowRunSnapshot(RUN, WorkflowState.PAUSED, null, 7L, 0, false)));
  }

  private void stubPausedEvent(WorkflowState priorState) {
    when(eventReadPort.findLatestTransitionToState(RUN, WorkflowState.PAUSED))
        .thenReturn(
            Optional.of(
                new WorkflowEventRecord(
                    PAUSED_EVENT_ID,
                    RUN,
                    WorkflowEventType.WORKFLOW_STATE_CHANGED,
                    priorState,
                    WorkflowState.PAUSED,
                    "alex",
                    ActorType.HUMAN,
                    "paused for investigation",
                    null,
                    true,
                    FIXED_NOW,
                    Map.of())));
  }

  private void stubResumeDispatch(String newRexPublicId) {
    RunnerExecutionHandle handle =
        new RunnerExecutionHandle(
            newRexPublicId,
            RUN,
            RunnerStage.EXECUTION,
            RunnerExecutionStatus.QUEUED,
            FIXED_NOW.plusMinutes(30));
    when(workflowOrchestrationService.redispatchAfterRetry(eq(RUN), any()))
        .thenReturn(new RunnerDispatchResult.Queued(handle, "evt_q-resume1"));
  }

  private RecoveryActionSnapshot resumeActionSnapshot(String publicId, String resultStatus) {
    return new RecoveryActionSnapshot(
        publicId,
        1L,
        RUN,
        "resume",
        PAUSED_EVENT_ID,
        null,
        "alex",
        ActorType.HUMAN,
        IDEMPOTENCY_KEY,
        resultStatus,
        FIXED_NOW,
        "workflow_owner");
  }

  private RecoveryActionSnapshot resumeActionSnapshotWithEvent(
      String publicId, String resultStatus) {
    return new RecoveryActionSnapshot(
        publicId,
        1L,
        RUN,
        "resume",
        PAUSED_EVENT_ID,
        "evt_resumed-prior",
        "alex",
        ActorType.HUMAN,
        IDEMPOTENCY_KEY,
        resultStatus,
        FIXED_NOW,
        "workflow_owner");
  }

  private static ActorContext actor() {
    return new ActorContext("alex", ActorType.HUMAN, "corr-resume-1");
  }

  private static TransactionTemplate callthroughTemplate() {
    TransactionTemplate template = mock(TransactionTemplate.class);
    when(template.execute(any()))
        .thenAnswer(
            invocation -> {
              TransactionCallback<?> callback = invocation.getArgument(0);
              return callback.doInTransaction(null);
            });
    org.mockito.Mockito.doAnswer(
            invocation -> {
              java.util.function.Consumer<org.springframework.transaction.TransactionStatus>
                  action = invocation.getArgument(0);
              action.accept(null);
              return null;
            })
        .when(template)
        .executeWithoutResult(any());
    return template;
  }
}
