package org.dradgo.application.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.dradgo.application.artifact.ActorContext;
import org.dradgo.application.artifact.spi.ArtifactOperationPort;
import org.dradgo.application.idempotency.IdempotencyKeyValidator;
import org.dradgo.application.recovery.spi.RecoveryActionRecordPort;
import org.dradgo.application.recovery.spi.RecoveryActionSnapshot;
import org.dradgo.application.recovery.spi.RecoveryActionWriteCommand;
import org.dradgo.application.runner.queue.RunnerExecutionQueue;
import org.dradgo.application.runner.spi.RunnerAdapter;
import org.dradgo.application.runner.spi.RunnerExecutionCancellation;
import org.dradgo.application.runner.spi.RunnerExecutionRecordPort;
import org.dradgo.application.runner.spi.RunnerExecutionSnapshot;
import org.dradgo.application.workflow.WorkflowCommandService;
import org.dradgo.application.workflow.WorkflowOrchestrationService;
import org.dradgo.application.workflow.commands.PauseWorkflowCommand;
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
 * Story 4.8 (AC11) — unit coverage for {@link RecoveryService#pause}, mirroring {@link
 * RecoveryServiceResumeTest}: pause from a pausable source transitions to {@code Paused} + flips
 * every {@code {queued, pending, running}} runner row to {@code cancelled_for_pause} (with correct
 * queued-vs-in-flight counts + post-commit container stop for previously-RUNNING rows only) +
 * appends {@code recovery.paused} + inserts the {@code recovery_actions} row (action_type=pause,
 * reviewer_role=workflow_owner); pause from a non-pausable state raises {@code
 * PAUSE_NOT_APPLICABLE}; a blank reason raises {@code MISSING_REASON_TEXT}; idempotent replay
 * (actionType-guarded), cross-action / different-run / pending-prior conflicts, and the
 * concurrent-replay-on-conflict race.
 */
class RecoveryServicePauseTest {

  private static final String RUN = "run_pause12345";
  private static final String IDEMPOTENCY_KEY = "idem-pause-1234567890";
  private static final String TRIGGERING_EVENT_ID = "evt_trigger-aaa1";
  private static final String REASON = "suspected runner config issue";
  private static final OffsetDateTime FIXED_NOW = OffsetDateTime.parse("2026-07-12T12:00:00Z");

  private WorkflowRunReadPort runReadPort;
  private WorkflowEventReadPort eventReadPort;
  private RunnerExecutionRecordPort runnerRecordPort;
  private WorkflowCommandService workflowCommandService;
  private WorkflowOrchestrationService workflowOrchestrationService;
  private WorkflowEventWritePort eventWritePort;
  private RecoveryActionRecordPort recoveryRecordPort;
  private RunnerAdapter runnerAdapter;
  private RecoveryService service;

  @BeforeEach
  void setUp() {
    runReadPort = mock(WorkflowRunReadPort.class);
    eventReadPort = mock(WorkflowEventReadPort.class);
    runnerRecordPort = mock(RunnerExecutionRecordPort.class);
    workflowCommandService = mock(WorkflowCommandService.class);
    workflowOrchestrationService = mock(WorkflowOrchestrationService.class);
    eventWritePort = mock(WorkflowEventWritePort.class);
    recoveryRecordPort = mock(RecoveryActionRecordPort.class);
    runnerAdapter = mock(RunnerAdapter.class);
    service =
        new RecoveryService(
            runReadPort,
            eventReadPort,
            runnerRecordPort,
            mock(ArtifactOperationPort.class),
            workflowCommandService,
            mock(RunnerExecutionQueue.class),
            mock(org.dradgo.application.project.ProjectRuntimeConfigResolver.class),
            mock(org.dradgo.application.runner.ManualExecutionDispatcher.class),
            workflowOrchestrationService,
            mock(org.dradgo.application.integration.IntegrationLinkService.class),
            eventWritePort,
            recoveryRecordPort,
            mock(org.dradgo.application.integration.conflict.IntegrationConflictService.class),
            new IdempotencyKeyValidator(),
            Clock.fixed(FIXED_NOW.toInstant(), ZoneOffset.UTC),
            callthroughTemplate(),
            callthroughTemplate(),
            mock(org.dradgo.application.approval.ApprovalService.class),
            mock(org.dradgo.application.artifact.spi.ArtifactRecordPort.class),
            mock(org.dradgo.application.workflow.WorkflowTransitionService.class),
            runnerAdapter,
            null);
  }

  @Test
  void pauseFromExecutingCancelsRunnersAppendsEventInsertsRowAndStopsRunningContainer() {
    stubRunWithState(WorkflowState.EXECUTING);
    stubTriggeringEvent();
    when(recoveryRecordPort.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.empty());
    when(recoveryRecordPort.insert(any(RecoveryActionWriteCommand.class)))
        .thenReturn(pauseActionSnapshot("rcv_pause-aaaaa", "pending"));
    when(recoveryRecordPort.markSucceeded(IDEMPOTENCY_KEY))
        .thenReturn(pauseActionSnapshot("rcv_pause-aaaaa", "succeeded"));
    stubDispatchableRows(
        runnerRow("rex_q-11111", RunnerExecutionStatus.QUEUED),
        runnerRow("rex_p-22222", RunnerExecutionStatus.PENDING),
        runnerRow("rex_r-33333", RunnerExecutionStatus.RUNNING));
    stubCancellation("rex_q-11111", RunnerExecutionStatus.QUEUED);
    stubCancellation("rex_p-22222", RunnerExecutionStatus.PENDING);
    stubCancellation("rex_r-33333", RunnerExecutionStatus.RUNNING);

    PauseRecoveryResult result = service.pause(RUN, IDEMPOTENCY_KEY, actor(), REASON);

    assertNotNull(result);
    assertFalse(result.replayed());
    assertEquals("rcv_pause-aaaaa", result.recoveryActionPublicId());
    assertEquals(WorkflowState.EXECUTING, result.priorState());
    assertEquals(WorkflowState.PAUSED, result.resultingState());
    assertEquals(2, result.cancelledInFlightCount());
    assertEquals(1, result.cancelledQueuedCount());
    assertNotNull(result.pausedEventPublicId());
    assertTrue(result.pausedEventPublicId().startsWith("evt_"));

    ArgumentCaptor<PauseWorkflowCommand> commandCaptor =
        ArgumentCaptor.forClass(PauseWorkflowCommand.class);
    verify(workflowCommandService, times(1)).pauseWorkflow(commandCaptor.capture());
    assertEquals(RUN, commandCaptor.getValue().workflowRunId());
    assertEquals(IDEMPOTENCY_KEY, commandCaptor.getValue().idempotencyKey());
    assertEquals(REASON, commandCaptor.getValue().reasonText());

    // AC4: the DB flip covers all three dispatch-visible rows; docker stop fires ONLY for the
    // previously-RUNNING row, post-commit.
    verify(runnerRecordPort, times(1)).markCancelledForPause(eq("rex_q-11111"), any());
    verify(runnerRecordPort, times(1)).markCancelledForPause(eq("rex_p-22222"), any());
    verify(runnerRecordPort, times(1)).markCancelledForPause(eq("rex_r-33333"), any());
    verify(runnerAdapter, times(1)).cancel("rex_r-33333");
    verify(runnerAdapter, never()).cancel("rex_q-11111");
    verify(runnerAdapter, never()).cancel("rex_p-22222");

    ArgumentCaptor<WorkflowEventRecord> eventCaptor =
        ArgumentCaptor.forClass(WorkflowEventRecord.class);
    verify(eventWritePort, times(1)).append(eventCaptor.capture());
    WorkflowEventRecord pausedEvent = eventCaptor.getValue();
    assertEquals(WorkflowEventType.RECOVERY_PAUSED, pausedEvent.eventType());
    assertEquals(WorkflowState.EXECUTING, pausedEvent.priorState());
    assertEquals(WorkflowState.PAUSED, pausedEvent.resultingState());
    assertTrue(pausedEvent.interventionMarker());
    assertEquals(TRIGGERING_EVENT_ID, pausedEvent.details().get("triggeringEventId"));
    assertEquals(IDEMPOTENCY_KEY, pausedEvent.details().get("idempotencyKey"));
    assertEquals("corr-pause-1", pausedEvent.details().get("correlationId"));
    assertEquals(REASON, pausedEvent.details().get("reason"));
    assertEquals(REASON, pausedEvent.reason());

    ArgumentCaptor<RecoveryActionWriteCommand> writeCaptor =
        ArgumentCaptor.forClass(RecoveryActionWriteCommand.class);
    verify(recoveryRecordPort, times(1)).insert(writeCaptor.capture());
    assertEquals("pause", writeCaptor.getValue().actionType());
    assertEquals("workflow_owner", writeCaptor.getValue().reviewerRole());
    assertEquals("pending", writeCaptor.getValue().resultStatus());
    assertEquals(IDEMPOTENCY_KEY, writeCaptor.getValue().idempotencyKey());
    assertEquals(TRIGGERING_EVENT_ID, writeCaptor.getValue().triggeringEventPublicId());

    verify(recoveryRecordPort, times(1)).markSucceeded(IDEMPOTENCY_KEY);
    verify(recoveryRecordPort, never()).markFailed(any());
  }

  @Test
  void pauseFromWaitingForReviewWithNoRunnerRowsYieldsZeroCounts() {
    stubRunWithState(WorkflowState.WAITING_FOR_REVIEW);
    stubTriggeringEvent();
    when(recoveryRecordPort.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.empty());
    when(recoveryRecordPort.insert(any(RecoveryActionWriteCommand.class)))
        .thenReturn(pauseActionSnapshot("rcv_pause-bbbbb", "pending"));
    when(recoveryRecordPort.markSucceeded(IDEMPOTENCY_KEY))
        .thenReturn(pauseActionSnapshot("rcv_pause-bbbbb", "succeeded"));
    stubDispatchableRows();

    PauseRecoveryResult result = service.pause(RUN, IDEMPOTENCY_KEY, actor(), REASON);

    assertEquals(WorkflowState.WAITING_FOR_REVIEW, result.priorState());
    assertEquals(0, result.cancelledInFlightCount());
    assertEquals(0, result.cancelledQueuedCount());
    verify(runnerRecordPort, never()).markCancelledForPause(any(), any());
    verify(runnerAdapter, never()).cancel(any());
  }

  @Test
  void pauseFromFailedSucceeds() {
    // Reconciliation 3: FAILED is a MANDATORY pausable source — 4.4's RecommendationService
    // already advertises pause as a safe action on Failed runs.
    stubRunWithState(WorkflowState.FAILED);
    stubTriggeringEvent();
    when(recoveryRecordPort.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.empty());
    when(recoveryRecordPort.insert(any(RecoveryActionWriteCommand.class)))
        .thenReturn(pauseActionSnapshot("rcv_pause-ccccc", "pending"));
    when(recoveryRecordPort.markSucceeded(IDEMPOTENCY_KEY))
        .thenReturn(pauseActionSnapshot("rcv_pause-ccccc", "succeeded"));
    stubDispatchableRows();

    PauseRecoveryResult result = service.pause(RUN, IDEMPOTENCY_KEY, actor(), REASON);

    assertEquals(WorkflowState.FAILED, result.priorState());
    assertEquals(WorkflowState.PAUSED, result.resultingState());
  }

  @ParameterizedTest
  @EnumSource(
      value = WorkflowState.class,
      names = {
        "COMPLETED",
        "TAKEN_OVER",
        "RECONCILED",
        "PAUSED",
        "INBOX",
        "PLANNED",
        "SPLIT",
        "WAITING_FOR_DEPENDENCIES"
      })
  void pauseFromNonPausableStateRaisesPauseNotApplicableWithoutMutatingState(
      WorkflowState nonPausable) {
    // AC3 — the explicit allow-list gate: terminal states + TakenOver + Paused itself (the epic's
    // stated rejections) PLUS the four autonomous-driver states whose one-shot trigger a pause
    // would silently consume (Reconciliation 1).
    when(recoveryRecordPort.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.empty());
    stubRunWithState(nonPausable);

    DomainException error =
        assertThrows(
            DomainException.class, () -> service.pause(RUN, IDEMPOTENCY_KEY, actor(), REASON));
    assertEquals(DomainErrorCode.PAUSE_NOT_APPLICABLE, error.errorCode());
    assertEquals(RUN, error.details().get("runId"));
    assertEquals(nonPausable.value(), error.details().get("currentState"));

    verify(workflowCommandService, never()).pauseWorkflow(any());
    verify(runnerRecordPort, never()).markCancelledForPause(any(), any());
    verify(eventWritePort, never()).append(any());
    verify(recoveryRecordPort, never()).insert(any(RecoveryActionWriteCommand.class));
    verify(runnerAdapter, never()).cancel(any());
  }

  @Test
  void pauseWithBlankReasonRaisesMissingReasonText() {
    for (String blank : new String[] {null, "", "   "}) {
      DomainException error =
          assertThrows(
              DomainException.class, () -> service.pause(RUN, IDEMPOTENCY_KEY, actor(), blank));
      assertEquals(DomainErrorCode.MISSING_REASON_TEXT, error.errorCode());
      assertEquals("reasonText", error.details().get("field"));
    }
    // 4.8 review — the reason guard runs BEFORE the replay pre-check: a same-key retry that omits
    // the reason is an invalid request (AC2), never a silent replay (reasonText composes the
    // fingerprint identity, Reconciliation 9).
    verify(recoveryRecordPort, never()).findByIdempotencyKey(any());
    verify(runReadPort, never()).findByPublicId(any());
    verify(workflowCommandService, never()).pauseWorkflow(any());
    verify(eventWritePort, never()).append(any());
  }

  @Test
  void pausePriorStateComesFromTheTransitionEventReadBackInsideThePrepTx() {
    // 4.8 review (TOCTOU) — if the run advances to ANOTHER pausable state between the outer gate
    // and the prep tx, the authoritative priorState is what the transition stamped on its
    // workflow.stateChanged → Paused event (read back in-tx), NOT the stale pre-tx snapshot: the
    // recovery.paused anchor, the result, and the success log must agree with the event resume
    // derives its priorState from.
    stubRunWithState(WorkflowState.EXECUTING);
    stubTriggeringEvent();
    when(recoveryRecordPort.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.empty());
    when(recoveryRecordPort.insert(any(RecoveryActionWriteCommand.class)))
        .thenReturn(pauseActionSnapshot("rcv_pause-tocto", "pending"));
    when(recoveryRecordPort.markSucceeded(IDEMPOTENCY_KEY))
        .thenReturn(pauseActionSnapshot("rcv_pause-tocto", "succeeded"));
    stubDispatchableRows();
    when(eventReadPort.findLatestTransitionToState(RUN, WorkflowState.PAUSED))
        .thenReturn(
            Optional.of(
                new WorkflowEventRecord(
                    "evt_pause-trans1",
                    RUN,
                    WorkflowEventType.WORKFLOW_STATE_CHANGED,
                    WorkflowState.WAITING_FOR_REVIEW,
                    WorkflowState.PAUSED,
                    "alex",
                    ActorType.HUMAN,
                    REASON,
                    null,
                    false,
                    FIXED_NOW,
                    Map.of())));

    PauseRecoveryResult result = service.pause(RUN, IDEMPOTENCY_KEY, actor(), REASON);

    assertEquals(WorkflowState.WAITING_FOR_REVIEW, result.priorState());
    ArgumentCaptor<WorkflowEventRecord> eventCaptor =
        ArgumentCaptor.forClass(WorkflowEventRecord.class);
    verify(eventWritePort).append(eventCaptor.capture());
    assertEquals(WorkflowState.WAITING_FOR_REVIEW, eventCaptor.getValue().priorState());
  }

  @Test
  void pauseInTxRegateRejectsWhenRunAdvancedToNonPausableStateAfterOuterGate() {
    // 4.8 review (TOCTOU) — the prep tx re-gates: a run that advanced to a NON-pausable state
    // between the outer fast-path gate and the tx rejects with the typed 409, not the
    // transition's raw ILLEGAL_TRANSITION.
    when(recoveryRecordPort.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.empty());
    stubTriggeringEvent();
    when(runReadPort.findByPublicId(RUN))
        .thenReturn(
            Optional.of(new WorkflowRunSnapshot(RUN, WorkflowState.EXECUTING, null, 7L, 0, false)),
            Optional.of(new WorkflowRunSnapshot(RUN, WorkflowState.COMPLETED, null, 8L, 0, false)));

    DomainException error =
        assertThrows(
            DomainException.class, () -> service.pause(RUN, IDEMPOTENCY_KEY, actor(), REASON));
    assertEquals(DomainErrorCode.PAUSE_NOT_APPLICABLE, error.errorCode());
    assertEquals(WorkflowState.COMPLETED.value(), error.details().get("currentState"));
    verify(workflowCommandService, never()).pauseWorkflow(any());
    verify(eventWritePort, never()).append(any());
  }

  @Test
  void pauseReplayWithWhitespacePaddedIdenticalReasonStillReplays() {
    // 4.8 review — the replay discriminator compares TRIMMED reasons (the fingerprint's
    // normalizeOptional semantics): a whitespace-differing retry of the identical pause replays
    // instead of conflicting.
    RecoveryActionSnapshot prior = pauseActionSnapshotWithEvent("rcv_prior-wwwww", "succeeded");
    when(recoveryRecordPort.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.of(prior));
    stubPersistedPausedEvent(WorkflowState.EXECUTING, REASON);

    PauseRecoveryResult result = service.pause(RUN, IDEMPOTENCY_KEY, actor(), "  " + REASON + "  ");

    assertTrue(result.replayed());
    verify(workflowCommandService, never()).pauseWorkflow(any());
  }

  @Test
  void pauseStoresTrimmedReasonOnRecoveryPausedEvent() {
    // 4.8 review — the reason is trimmed at the boundary so BOTH audit rows (the transition's
    // typed reason and the recovery.paused event) store the same value the fingerprint hashes.
    stubRunWithState(WorkflowState.EXECUTING);
    stubTriggeringEvent();
    when(recoveryRecordPort.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.empty());
    when(recoveryRecordPort.insert(any(RecoveryActionWriteCommand.class)))
        .thenReturn(pauseActionSnapshot("rcv_pause-trim1", "pending"));
    when(recoveryRecordPort.markSucceeded(IDEMPOTENCY_KEY))
        .thenReturn(pauseActionSnapshot("rcv_pause-trim1", "succeeded"));
    stubDispatchableRows();

    service.pause(RUN, IDEMPOTENCY_KEY, actor(), "  " + REASON + "  ");

    ArgumentCaptor<WorkflowEventRecord> eventCaptor =
        ArgumentCaptor.forClass(WorkflowEventRecord.class);
    verify(eventWritePort).append(eventCaptor.capture());
    assertEquals(REASON, eventCaptor.getValue().reason());
  }

  @Test
  void pauseRejectsInvalidIdempotencyKeyBeforeAnyPersistenceLookup() {
    DomainException error =
        assertThrows(DomainException.class, () -> service.pause(RUN, "bad key", actor(), REASON));
    assertEquals(DomainErrorCode.INVALID_IDEMPOTENCY_KEY, error.errorCode());

    verify(recoveryRecordPort, never()).findByIdempotencyKey(any());
    verify(runReadPort, never()).findByPublicId(any());
  }

  @Test
  void pauseScansOnlyQueuedPendingRunningNeverAwaitingManual() {
    // Reconciliation 5: the scan set is {QUEUED, PENDING, RUNNING} — a parked awaiting_manual row
    // is never cancelled (pause is reversible; no re-park path on resume).
    stubRunWithState(WorkflowState.WAITING_FOR_MANUAL_EXECUTION);
    stubTriggeringEvent();
    when(recoveryRecordPort.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.empty());
    when(recoveryRecordPort.insert(any(RecoveryActionWriteCommand.class)))
        .thenReturn(pauseActionSnapshot("rcv_pause-ddddd", "pending"));
    when(recoveryRecordPort.markSucceeded(IDEMPOTENCY_KEY))
        .thenReturn(pauseActionSnapshot("rcv_pause-ddddd", "succeeded"));
    stubDispatchableRows();

    service.pause(RUN, IDEMPOTENCY_KEY, actor(), REASON);

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<RunnerExecutionStatus>> scanCaptor =
        ArgumentCaptor.forClass((Class) List.class);
    verify(runnerRecordPort).findByWorkflowRunPublicIdAndStatusIn(eq(RUN), scanCaptor.capture());
    assertEquals(
        List.of(
            RunnerExecutionStatus.QUEUED,
            RunnerExecutionStatus.PENDING,
            RunnerExecutionStatus.RUNNING),
        scanCaptor.getValue());
    verify(runnerRecordPort, never()).markCancelledForPause(any(), any());
  }

  @Test
  void pauseSkipsAlreadyTerminalRowWithoutCounting() {
    // Optional.empty() from markCancelledForPause = already-terminal idempotent skip: not counted,
    // never docker-stopped.
    stubRunWithState(WorkflowState.EXECUTING);
    stubTriggeringEvent();
    when(recoveryRecordPort.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.empty());
    when(recoveryRecordPort.insert(any(RecoveryActionWriteCommand.class)))
        .thenReturn(pauseActionSnapshot("rcv_pause-eeeee", "pending"));
    when(recoveryRecordPort.markSucceeded(IDEMPOTENCY_KEY))
        .thenReturn(pauseActionSnapshot("rcv_pause-eeeee", "succeeded"));
    stubDispatchableRows(runnerRow("rex_done-4444", RunnerExecutionStatus.RUNNING));
    when(runnerRecordPort.markCancelledForPause(eq("rex_done-4444"), any()))
        .thenReturn(Optional.empty());

    PauseRecoveryResult result = service.pause(RUN, IDEMPOTENCY_KEY, actor(), REASON);

    assertEquals(0, result.cancelledInFlightCount());
    assertEquals(0, result.cancelledQueuedCount());
    verify(runnerAdapter, never()).cancel(any());
  }

  @Test
  void pauseContainerCancelFailureIsSwallowedAndPauseStillSucceeds() {
    // The docker stop is best-effort: the DB flip already stopped dispatch, so a cancel failure
    // must never fail the pause (WARN only).
    stubRunWithState(WorkflowState.EXECUTING);
    stubTriggeringEvent();
    when(recoveryRecordPort.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.empty());
    when(recoveryRecordPort.insert(any(RecoveryActionWriteCommand.class)))
        .thenReturn(pauseActionSnapshot("rcv_pause-fffff", "pending"));
    when(recoveryRecordPort.markSucceeded(IDEMPOTENCY_KEY))
        .thenReturn(pauseActionSnapshot("rcv_pause-fffff", "succeeded"));
    stubDispatchableRows(runnerRow("rex_r-55555", RunnerExecutionStatus.RUNNING));
    stubCancellation("rex_r-55555", RunnerExecutionStatus.RUNNING);
    org.mockito.Mockito.doThrow(new IllegalStateException("docker stop failed"))
        .when(runnerAdapter)
        .cancel("rex_r-55555");

    PauseRecoveryResult result = service.pause(RUN, IDEMPOTENCY_KEY, actor(), REASON);

    assertFalse(result.replayed());
    assertEquals(1, result.cancelledInFlightCount());
    verify(recoveryRecordPort, times(1)).markSucceeded(IDEMPOTENCY_KEY);
  }

  @Test
  void pauseWithSucceededPriorPauseForSameRunReturnsReplayedResultWithZeroCounts() {
    RecoveryActionSnapshot prior = pauseActionSnapshotWithEvent("rcv_prior-aaaaa", "succeeded");
    when(recoveryRecordPort.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.of(prior));
    stubPersistedPausedEvent(WorkflowState.EXECUTING, REASON);

    PauseRecoveryResult result = service.pause(RUN, IDEMPOTENCY_KEY, actor(), REASON);

    assertTrue(result.replayed());
    assertEquals("rcv_prior-aaaaa", result.recoveryActionPublicId());
    assertEquals("evt_paused-prior", result.pausedEventPublicId());
    assertEquals(WorkflowState.EXECUTING, result.priorState());
    // Counts are 0 BY CONTRACT on replay — this call cancelled nothing.
    assertEquals(0, result.cancelledInFlightCount());
    assertEquals(0, result.cancelledQueuedCount());

    verify(runReadPort, never()).findByPublicId(any());
    verify(workflowCommandService, never()).pauseWorkflow(any());
    verify(eventWritePort, never()).append(any());
    verify(recoveryRecordPort, never()).insert(any(RecoveryActionWriteCommand.class));
    verify(runnerRecordPort, never()).markCancelledForPause(any(), any());
    verify(runnerAdapter, never()).cancel(any());
  }

  @Test
  void pauseReplayWithDifferentReasonRaisesIdempotencyKeyConflict() {
    // reasonText composes the fingerprint identity — a same-key pause with a DIFFERENT reason is a
    // conflict, never a replay.
    RecoveryActionSnapshot prior = pauseActionSnapshotWithEvent("rcv_prior-bbbbb", "succeeded");
    when(recoveryRecordPort.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.of(prior));
    stubPersistedPausedEvent(WorkflowState.EXECUTING, REASON);

    DomainException error =
        assertThrows(
            DomainException.class,
            () -> service.pause(RUN, IDEMPOTENCY_KEY, actor(), "a totally different reason"));
    assertEquals(DomainErrorCode.IDEMPOTENCY_KEY_CONFLICT, error.errorCode());
  }

  @Test
  void pauseWithPriorNonPauseActionUnderSameKeyRaisesIdempotencyKeyConflict() {
    // The Step-1 replay pre-check is actionType-guarded (the 4.5 review catch): a prior resume row
    // under the same key is a conflict, never a false pause replay.
    RecoveryActionSnapshot priorResume =
        new RecoveryActionSnapshot(
            "rcv_prior-res11",
            42L,
            RUN,
            "resume",
            TRIGGERING_EVENT_ID,
            "evt_resumed-x",
            "alex",
            ActorType.HUMAN,
            IDEMPOTENCY_KEY,
            "succeeded",
            FIXED_NOW,
            "workflow_owner");
    when(recoveryRecordPort.findByIdempotencyKey(IDEMPOTENCY_KEY))
        .thenReturn(Optional.of(priorResume));

    DomainException error =
        assertThrows(
            DomainException.class, () -> service.pause(RUN, IDEMPOTENCY_KEY, actor(), REASON));
    assertEquals(DomainErrorCode.IDEMPOTENCY_KEY_CONFLICT, error.errorCode());
    assertEquals("resume", error.details().get("priorActionType"));
    verify(runReadPort, never()).findByPublicId(any());
  }

  @Test
  void pauseWithPriorPauseForDifferentRunRaisesIdempotencyKeyConflict() {
    RecoveryActionSnapshot prior =
        new RecoveryActionSnapshot(
            "rcv_prior-ccccc",
            43L,
            "run_otherrun9999",
            "pause",
            TRIGGERING_EVENT_ID,
            "evt_paused-other",
            "alex",
            ActorType.HUMAN,
            IDEMPOTENCY_KEY,
            "succeeded",
            FIXED_NOW,
            "workflow_owner");
    when(recoveryRecordPort.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.of(prior));

    DomainException error =
        assertThrows(
            DomainException.class, () -> service.pause(RUN, IDEMPOTENCY_KEY, actor(), REASON));
    assertEquals(DomainErrorCode.IDEMPOTENCY_KEY_CONFLICT, error.errorCode());
  }

  @Test
  void pauseWithPendingPriorPauseRaisesIdempotencyKeyConflict() {
    when(recoveryRecordPort.findByIdempotencyKey(IDEMPOTENCY_KEY))
        .thenReturn(Optional.of(pauseActionSnapshot("rcv_prior-ddddd", "pending")));

    DomainException error =
        assertThrows(
            DomainException.class, () -> service.pause(RUN, IDEMPOTENCY_KEY, actor(), REASON));
    assertEquals(DomainErrorCode.IDEMPOTENCY_KEY_CONFLICT, error.errorCode());
    assertEquals("pending", error.details().get("priorResultStatus"));
  }

  @Test
  void pauseConcurrentLoserSeesReplayWhenWinnerSucceededBetweenChecks() {
    // Concurrent-pause race: the pre-insert findByIdempotencyKey returns empty, but the
    // recovery_actions insert then throws IDEMPOTENCY_KEY_CONFLICT because the winner's row landed
    // in between. By the time we re-read, the winner is `succeeded` — return the documented
    // replay.
    stubRunWithState(WorkflowState.EXECUTING);
    stubTriggeringEvent();
    stubDispatchableRows();
    RecoveryActionSnapshot winner = pauseActionSnapshotWithEvent("rcv_winner-1234", "succeeded");
    when(recoveryRecordPort.findByIdempotencyKey(IDEMPOTENCY_KEY))
        .thenReturn(Optional.empty())
        .thenReturn(Optional.of(winner));
    when(recoveryRecordPort.insert(any(RecoveryActionWriteCommand.class)))
        .thenThrow(
            new DomainException(
                DomainErrorCode.IDEMPOTENCY_KEY_CONFLICT,
                "recovery_actions unique violation",
                Map.of("idempotencyKey", IDEMPOTENCY_KEY)));
    stubPersistedPausedEvent(WorkflowState.EXECUTING, REASON);

    PauseRecoveryResult result = service.pause(RUN, IDEMPOTENCY_KEY, actor(), REASON);

    assertTrue(result.replayed());
    assertEquals("rcv_winner-1234", result.recoveryActionPublicId());
    assertEquals("evt_paused-prior", result.pausedEventPublicId());
    assertEquals(0, result.cancelledInFlightCount());
    assertEquals(0, result.cancelledQueuedCount());
    verify(runnerAdapter, never()).cancel(any());
    verify(recoveryRecordPort, never()).markSucceeded(any());
  }

  @Test
  void pauseConcurrentLoserPropagatesConflictWhenWinnerStillPending() {
    stubRunWithState(WorkflowState.EXECUTING);
    stubTriggeringEvent();
    stubDispatchableRows();
    RecoveryActionSnapshot winnerPending =
        pauseActionSnapshotWithEvent("rcv_winner-pend1", "pending");
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
            DomainException.class, () -> service.pause(RUN, IDEMPOTENCY_KEY, actor(), REASON));
    assertEquals(DomainErrorCode.IDEMPOTENCY_KEY_CONFLICT, error.errorCode());
    verify(runnerAdapter, never()).cancel(any());
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private void stubRunWithState(WorkflowState state) {
    when(runReadPort.findByPublicId(RUN))
        .thenReturn(Optional.of(new WorkflowRunSnapshot(RUN, state, null, 7L, 0, false)));
  }

  private void stubTriggeringEvent() {
    when(eventReadPort.findLatestByWorkflowRunPublicIdAndEventTypeIn(
            eq(RUN), eq(List.of(WorkflowEventType.WORKFLOW_STATE_CHANGED.value()))))
        .thenReturn(
            Optional.of(
                new WorkflowEventRecord(
                    TRIGGERING_EVENT_ID,
                    RUN,
                    WorkflowEventType.WORKFLOW_STATE_CHANGED,
                    WorkflowState.INVESTIGATING,
                    WorkflowState.EXECUTING,
                    "system",
                    ActorType.SYSTEM,
                    "advance",
                    null,
                    false,
                    FIXED_NOW.minusMinutes(5),
                    Map.of())));
  }

  private void stubDispatchableRows(RunnerExecutionSnapshot... rows) {
    when(runnerRecordPort.findByWorkflowRunPublicIdAndStatusIn(eq(RUN), any()))
        .thenReturn(List.of(rows));
  }

  private void stubCancellation(String rexId, RunnerExecutionStatus previousStatus) {
    when(runnerRecordPort.markCancelledForPause(eq(rexId), any()))
        .thenReturn(Optional.of(new RunnerExecutionCancellation(rexId, previousStatus)));
  }

  private RunnerExecutionSnapshot runnerRow(String publicId, RunnerExecutionStatus status) {
    return new RunnerExecutionSnapshot(
        publicId,
        RUN,
        RunnerStage.EXECUTION,
        status,
        1,
        FIXED_NOW,
        FIXED_NOW.plusMinutes(30),
        null,
        null,
        FIXED_NOW.minusMinutes(10),
        null);
  }

  /** The persisted recovery.paused event backing a replay (typed priorState + reason column). */
  private void stubPersistedPausedEvent(WorkflowState priorState, String reason) {
    when(eventReadPort.listByWorkflowRunPublicId(RUN, null))
        .thenReturn(
            List.of(
                new WorkflowEventRecord(
                    "evt_paused-prior",
                    RUN,
                    WorkflowEventType.RECOVERY_PAUSED,
                    priorState,
                    WorkflowState.PAUSED,
                    "alex",
                    ActorType.HUMAN,
                    reason,
                    null,
                    true,
                    FIXED_NOW.minusMinutes(1),
                    Map.of("idempotencyKey", IDEMPOTENCY_KEY))));
  }

  private RecoveryActionSnapshot pauseActionSnapshot(String publicId, String resultStatus) {
    return new RecoveryActionSnapshot(
        publicId,
        1L,
        RUN,
        "pause",
        TRIGGERING_EVENT_ID,
        null,
        "alex",
        ActorType.HUMAN,
        IDEMPOTENCY_KEY,
        resultStatus,
        FIXED_NOW,
        "workflow_owner");
  }

  private RecoveryActionSnapshot pauseActionSnapshotWithEvent(
      String publicId, String resultStatus) {
    return new RecoveryActionSnapshot(
        publicId,
        1L,
        RUN,
        "pause",
        TRIGGERING_EVENT_ID,
        "evt_paused-prior",
        "alex",
        ActorType.HUMAN,
        IDEMPOTENCY_KEY,
        resultStatus,
        FIXED_NOW,
        "workflow_owner");
  }

  private static ActorContext actor() {
    return new ActorContext("alex", ActorType.HUMAN, "corr-pause-1");
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
