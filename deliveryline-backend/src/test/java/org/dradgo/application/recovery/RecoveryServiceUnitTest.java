package org.dradgo.application.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
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
import org.dradgo.application.runner.RunnerBroker;
import org.dradgo.application.runner.RunnerDispatchResult;
import org.dradgo.application.runner.RunnerExecutionHandle;
import org.dradgo.application.runner.spi.RunnerExecutionRecordPort;
import org.dradgo.application.runner.spi.RunnerExecutionSnapshot;
import org.dradgo.application.workflow.WorkflowCommandService;
import org.dradgo.application.workflow.commands.RetryWorkflowCommand;
import org.dradgo.application.workflow.spi.WorkflowEventReadPort;
import org.dradgo.application.workflow.spi.WorkflowEventRecord;
import org.dradgo.application.workflow.spi.WorkflowEventWritePort;
import org.dradgo.application.workflow.spi.WorkflowRunReadPort;
import org.dradgo.application.workflow.spi.WorkflowRunSnapshot;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.FailureCategory;
import org.dradgo.domain.registry.RunnerExecutionStatus;
import org.dradgo.domain.registry.RunnerStage;
import org.dradgo.domain.registry.WorkflowEventType;
import org.dradgo.domain.registry.WorkflowState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

class RecoveryServiceUnitTest {

  private static final String RUN = "run_recovery1234";
  private static final String IDEMPOTENCY_KEY = "idem-recovery-1234567890";
  private static final OffsetDateTime FIXED_NOW = OffsetDateTime.parse("2026-05-15T12:00:00Z");
  private static final OffsetDateTime FAILURE_AT = OffsetDateTime.parse("2026-05-15T11:50:00Z");
  private static final OffsetDateTime LAST_ACTIVITY_AT =
      OffsetDateTime.parse("2026-05-15T11:49:55Z");

  private WorkflowRunReadPort runReadPort;
  private WorkflowEventReadPort eventReadPort;
  private RunnerExecutionRecordPort runnerRecordPort;
  private ArtifactOperationPort artifactOperationPort;
  private WorkflowCommandService workflowCommandService;
  private RunnerBroker runnerBroker;
  private WorkflowEventWritePort eventWritePort;
  private RecoveryActionRecordPort recoveryRecordPort;
  private IdempotencyKeyValidator idempotencyKeyValidator;
  private RecoveryService service;

  @BeforeEach
  void setUp() {
    runReadPort = mock(WorkflowRunReadPort.class);
    eventReadPort = mock(WorkflowEventReadPort.class);
    runnerRecordPort = mock(RunnerExecutionRecordPort.class);
    artifactOperationPort = mock(ArtifactOperationPort.class);
    workflowCommandService = mock(WorkflowCommandService.class);
    runnerBroker = mock(RunnerBroker.class);
    eventWritePort = mock(WorkflowEventWritePort.class);
    recoveryRecordPort = mock(RecoveryActionRecordPort.class);
    idempotencyKeyValidator = new IdempotencyKeyValidator();
    service =
        new RecoveryService(
            runReadPort,
            eventReadPort,
            runnerRecordPort,
            artifactOperationPort,
            workflowCommandService,
            runnerBroker,
            eventWritePort,
            recoveryRecordPort,
            idempotencyKeyValidator,
            Clock.fixed(FIXED_NOW.toInstant(), ZoneOffset.UTC),
            callthroughTemplate(),
            callthroughTemplate());
  }

  @Test
  void retryOnFailedRunInsertsRecoveryActionAndDispatchesAndAppendsRecoveryRetriedEvent() {
    stubFailedRun();
    stubFailureEventPresent();
    stubLastFailedRunner(RunnerStage.EXECUTION);
    when(recoveryRecordPort.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.empty());
    RecoveryActionSnapshot inserted = recoveryActionSnapshot("rcv_recov-aaaaa", "pending");
    when(recoveryRecordPort.insert(any(RecoveryActionWriteCommand.class))).thenReturn(inserted);
    when(recoveryRecordPort.markSucceeded(IDEMPOTENCY_KEY))
        .thenReturn(recoveryActionSnapshot("rcv_recov-aaaaa", "succeeded"));
    stubBrokerDispatch("rex_new1-bbbbb", RunnerStage.EXECUTION);

    RetryRecoveryResult result = service.retry(RUN, IDEMPOTENCY_KEY, actor(), null);

    assertNotNull(result);
    assertFalse(result.replayed());
    assertEquals("rcv_recov-aaaaa", result.recoveryActionPublicId());
    assertEquals("rex_new1-bbbbb", result.newRunnerExecutionPublicId());
    assertNotNull(result.recoveryRetriedEventPublicId());
    assertTrue(result.recoveryRetriedEventPublicId().startsWith("evt_"));

    ArgumentCaptor<RetryWorkflowCommand> commandCaptor =
        ArgumentCaptor.forClass(RetryWorkflowCommand.class);
    verify(workflowCommandService, times(1)).retryWorkflow(commandCaptor.capture());
    assertEquals(RUN, commandCaptor.getValue().workflowRunId());
    assertEquals(IDEMPOTENCY_KEY, commandCaptor.getValue().idempotencyKey());
    assertEquals("retry from failed execution", commandCaptor.getValue().reasonText());

    ArgumentCaptor<WorkflowEventRecord> eventCaptor =
        ArgumentCaptor.forClass(WorkflowEventRecord.class);
    verify(eventWritePort, times(1)).append(eventCaptor.capture());
    WorkflowEventRecord recoveryEvent = eventCaptor.getValue();
    assertEquals(WorkflowEventType.RECOVERY_RETRIED, recoveryEvent.eventType());
    assertEquals(WorkflowState.FAILED, recoveryEvent.priorState());
    assertEquals(WorkflowState.EXECUTING, recoveryEvent.resultingState());
    assertTrue(recoveryEvent.interventionMarker());
    assertEquals("execution", recoveryEvent.details().get("failedStage"));
    assertEquals("evt_failure-aaaa1", recoveryEvent.details().get("triggeringEventId"));
    assertEquals(IDEMPOTENCY_KEY, recoveryEvent.details().get("idempotencyKey"));
    assertEquals("corr-retry-1", recoveryEvent.details().get("correlationId"));

    ArgumentCaptor<RecoveryActionWriteCommand> writeCaptor =
        ArgumentCaptor.forClass(RecoveryActionWriteCommand.class);
    verify(recoveryRecordPort, times(1)).insert(writeCaptor.capture());
    assertEquals("retry", writeCaptor.getValue().actionType());
    assertEquals("pending", writeCaptor.getValue().resultStatus());
    assertEquals(IDEMPOTENCY_KEY, writeCaptor.getValue().idempotencyKey());
    assertEquals("evt_failure-aaaa1", writeCaptor.getValue().triggeringEventPublicId());

    verify(runnerBroker, times(1))
        .dispatch(
            eq(RUN),
            eq(RunnerStage.EXECUTION),
            eq(IDEMPOTENCY_KEY + ":runner"),
            any(ActorContext.class));
    verify(recoveryRecordPort, times(1)).markSucceeded(IDEMPOTENCY_KEY);
    verify(recoveryRecordPort, never()).markFailed(any());
  }

  @Test
  void retryWithOperatorReasonRoutesReasonIntoCommandAndEventDetails() {
    stubFailedRun();
    stubFailureEventPresent();
    stubLastFailedRunner(RunnerStage.EXECUTION);
    when(recoveryRecordPort.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.empty());
    when(recoveryRecordPort.insert(any(RecoveryActionWriteCommand.class)))
        .thenReturn(recoveryActionSnapshot("rcv_recov-aaaaa", "pending"));
    when(recoveryRecordPort.markSucceeded(IDEMPOTENCY_KEY))
        .thenReturn(recoveryActionSnapshot("rcv_recov-aaaaa", "succeeded"));
    stubBrokerDispatch("rex_new1-bbbbb", RunnerStage.EXECUTION);

    String operatorReason = "transient broker outage cleared at 12:00";
    service.retry(RUN, IDEMPOTENCY_KEY, actor(), operatorReason);

    ArgumentCaptor<RetryWorkflowCommand> commandCaptor =
        ArgumentCaptor.forClass(RetryWorkflowCommand.class);
    verify(workflowCommandService).retryWorkflow(commandCaptor.capture());
    assertEquals(operatorReason, commandCaptor.getValue().reasonText());

    ArgumentCaptor<WorkflowEventRecord> eventCaptor =
        ArgumentCaptor.forClass(WorkflowEventRecord.class);
    verify(eventWritePort).append(eventCaptor.capture());
    assertEquals(operatorReason, eventCaptor.getValue().reason());
    assertEquals(operatorReason, eventCaptor.getValue().details().get("reason"));
  }

  @Test
  void retryOnNonFailedRunRaisesRetryNotApplicableWithoutMutatingState() {
    when(runReadPort.findByPublicId(RUN))
        .thenReturn(
            Optional.of(new WorkflowRunSnapshot(RUN, WorkflowState.EXECUTING, null, 1L, 0, false)));
    when(recoveryRecordPort.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.empty());

    DomainException error =
        assertThrows(
            DomainException.class, () -> service.retry(RUN, IDEMPOTENCY_KEY, actor(), null));
    assertEquals(DomainErrorCode.RETRY_NOT_APPLICABLE, error.errorCode());
    assertEquals(RUN, error.details().get("runId"));
    assertEquals("Executing", error.details().get("currentState"));

    verify(workflowCommandService, never()).retryWorkflow(any());
    verify(eventWritePort, never()).append(any());
    verify(recoveryRecordPort, never()).insert(any(RecoveryActionWriteCommand.class));
    verify(runnerBroker, never()).dispatch(any(), any(), any(), any());
  }

  @Test
  void retryRejectsInvalidIdempotencyKeyBeforeAnyPersistenceLookup() {
    DomainException error =
        assertThrows(DomainException.class, () -> service.retry(RUN, "bad key", actor(), null));
    assertEquals(DomainErrorCode.INVALID_IDEMPOTENCY_KEY, error.errorCode());

    verify(recoveryRecordPort, never()).findByIdempotencyKey(any());
    verify(runReadPort, never()).findByPublicId(any());
    verify(workflowCommandService, never()).retryWorkflow(any());
  }

  @Test
  void retryWithSucceededPriorActionForSameRunReturnsReplayedResultWithoutSideEffects() {
    RecoveryActionSnapshot prior =
        new RecoveryActionSnapshot(
            "rcv_prior-bbbbb",
            4242L,
            RUN,
            "retry",
            "evt_trig0-bbbbb",
            "evt_rsl00-bbbbb",
            "alex",
            ActorType.HUMAN,
            IDEMPOTENCY_KEY,
            "succeeded",
            OffsetDateTime.parse("2026-05-15T11:55:00Z"));
    when(recoveryRecordPort.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.of(prior));

    RetryRecoveryResult result = service.retry(RUN, IDEMPOTENCY_KEY, actor(), null);

    assertTrue(result.replayed());
    assertEquals("rcv_prior-bbbbb", result.recoveryActionPublicId());
    assertEquals("evt_rsl00-bbbbb", result.recoveryRetriedEventPublicId());
    assertNull(result.newRunnerExecutionPublicId());

    verify(runReadPort, never()).findByPublicId(any());
    verify(workflowCommandService, never()).retryWorkflow(any());
    verify(eventWritePort, never()).append(any());
    verify(recoveryRecordPort, never()).insert(any(RecoveryActionWriteCommand.class));
    verify(runnerBroker, never()).dispatch(any(), any(), any(), any());
  }

  @Test
  void retryWithPriorActionForDifferentRunRaisesIdempotencyKeyConflict() {
    RecoveryActionSnapshot prior =
        new RecoveryActionSnapshot(
            "rcv_prior-ccccc",
            4242L,
            "run_otherrun9999",
            "retry",
            "evt_trigOther-cccc",
            "evt_rslOther-cccc",
            "alex",
            ActorType.HUMAN,
            IDEMPOTENCY_KEY,
            "succeeded",
            OffsetDateTime.parse("2026-05-15T11:55:00Z"));
    when(recoveryRecordPort.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.of(prior));

    DomainException error =
        assertThrows(
            DomainException.class, () -> service.retry(RUN, IDEMPOTENCY_KEY, actor(), null));
    assertEquals(DomainErrorCode.IDEMPOTENCY_KEY_CONFLICT, error.errorCode());
    assertEquals("run_otherrun9999", error.details().get("priorRunId"));
    assertEquals(RUN, error.details().get("requestedRunId"));

    verify(runReadPort, never()).findByPublicId(any());
    verify(runnerBroker, never()).dispatch(any(), any(), any(), any());
  }

  @Test
  void retryWithPriorActionInPendingOrFailedStateRaisesIdempotencyKeyConflict() {
    RecoveryActionSnapshot priorPending =
        new RecoveryActionSnapshot(
            "rcv_prior-ddddd",
            4242L,
            RUN,
            "retry",
            null,
            null,
            "alex",
            ActorType.HUMAN,
            IDEMPOTENCY_KEY,
            "pending",
            OffsetDateTime.parse("2026-05-15T11:55:00Z"));
    when(recoveryRecordPort.findByIdempotencyKey(IDEMPOTENCY_KEY))
        .thenReturn(Optional.of(priorPending));

    DomainException pendingError =
        assertThrows(
            DomainException.class, () -> service.retry(RUN, IDEMPOTENCY_KEY, actor(), null));
    assertEquals(DomainErrorCode.IDEMPOTENCY_KEY_CONFLICT, pendingError.errorCode());
    assertEquals("pending", pendingError.details().get("priorResultStatus"));

    RecoveryActionSnapshot priorFailed =
        new RecoveryActionSnapshot(
            "rcv_prior-eeeee",
            4242L,
            RUN,
            "retry",
            null,
            null,
            "alex",
            ActorType.HUMAN,
            IDEMPOTENCY_KEY,
            "failed",
            OffsetDateTime.parse("2026-05-15T11:55:00Z"));
    when(recoveryRecordPort.findByIdempotencyKey(IDEMPOTENCY_KEY))
        .thenReturn(Optional.of(priorFailed));

    DomainException failedError =
        assertThrows(
            DomainException.class, () -> service.retry(RUN, IDEMPOTENCY_KEY, actor(), null));
    assertEquals(DomainErrorCode.IDEMPOTENCY_KEY_CONFLICT, failedError.errorCode());
    assertEquals("failed", failedError.details().get("priorResultStatus"));
  }

  @Test
  void retryConcurrentLoserSeesReplayWhenWinnerCompletedSucceededBetweenChecks() {
    // Concurrent-retry race (review F532): the pre-insert findByIdempotencyKey returns empty
    // (the sibling thread's row had not committed yet), but the recovery_actions insert then
    // throws IDEMPOTENCY_KEY_CONFLICT because the winning thread's row landed in between.
    // By the time we re-read, the winner has also flipped result_status to `succeeded` — the
    // loser must return the documented replay shape rather than surface the conflict.
    stubFailedRun();
    stubFailureEventPresent();
    stubLastFailedRunner(RunnerStage.EXECUTION);
    // Pre-insert: empty.
    // Post-insert (after we catch the conflict): the winner's row, now succeeded.
    RecoveryActionSnapshot winnerSnapshot =
        new RecoveryActionSnapshot(
            "rcv_winner-1234",
            7777L,
            RUN,
            "retry",
            "evt_failure-aaaa1",
            "evt_winret-bbbbb",
            "alex",
            ActorType.HUMAN,
            IDEMPOTENCY_KEY,
            "succeeded",
            OffsetDateTime.parse("2026-05-15T11:55:00Z"));
    when(recoveryRecordPort.findByIdempotencyKey(IDEMPOTENCY_KEY))
        .thenReturn(Optional.empty()) // pre-insert check
        .thenReturn(Optional.of(winnerSnapshot)); // post-conflict re-read
    when(recoveryRecordPort.insert(any(RecoveryActionWriteCommand.class)))
        .thenThrow(
            new DomainException(
                DomainErrorCode.IDEMPOTENCY_KEY_CONFLICT,
                "recovery_actions unique violation",
                Map.of("idempotencyKey", IDEMPOTENCY_KEY)));

    RetryRecoveryResult result = service.retry(RUN, IDEMPOTENCY_KEY, actor(), null);

    assertTrue(result.replayed());
    assertEquals("rcv_winner-1234", result.recoveryActionPublicId());
    assertEquals("evt_winret-bbbbb", result.recoveryRetriedEventPublicId());
    assertNull(result.newRunnerExecutionPublicId());
    // Loser must NOT redispatch the runner — the winner already did.
    verify(runnerBroker, never()).dispatch(any(), any(), any(), any());
    verify(recoveryRecordPort, never()).markSucceeded(any());
  }

  @Test
  void retryConcurrentLoserPropagatesConflictWhenPriorRunStillPending() {
    // Same race as above but the winning thread is still mid-flight (result_status='pending')
    // when the loser re-reads — operator must wait or use a fresh key; cannot replay a result
    // that has not been confirmed terminal yet.
    stubFailedRun();
    stubFailureEventPresent();
    stubLastFailedRunner(RunnerStage.EXECUTION);
    RecoveryActionSnapshot winnerSnapshot =
        new RecoveryActionSnapshot(
            "rcv_winner-pending",
            7777L,
            RUN,
            "retry",
            "evt_failure-aaaa1",
            "evt_winret-pendin",
            "alex",
            ActorType.HUMAN,
            IDEMPOTENCY_KEY,
            "pending",
            OffsetDateTime.parse("2026-05-15T11:55:00Z"));
    when(recoveryRecordPort.findByIdempotencyKey(IDEMPOTENCY_KEY))
        .thenReturn(Optional.empty())
        .thenReturn(Optional.of(winnerSnapshot));
    DomainException unique =
        new DomainException(
            DomainErrorCode.IDEMPOTENCY_KEY_CONFLICT,
            "recovery_actions unique violation",
            Map.of("idempotencyKey", IDEMPOTENCY_KEY));
    when(recoveryRecordPort.insert(any(RecoveryActionWriteCommand.class))).thenThrow(unique);

    DomainException error =
        assertThrows(
            DomainException.class, () -> service.retry(RUN, IDEMPOTENCY_KEY, actor(), null));
    assertEquals(DomainErrorCode.IDEMPOTENCY_KEY_CONFLICT, error.errorCode());
    verify(runnerBroker, never()).dispatch(any(), any(), any(), any());
  }

  @Test
  void retryConcurrentLoserPropagatesConflictWhenInnerRetryWorkflowSurfacedTheConflict() {
    // The conflict can originate from EITHER the recovery_actions unique constraint OR the
    // inner WorkflowCommandService.retryWorkflow idempotency_records insert. When it comes
    // from the inner surface, thread B's recovery_actions row was never written (prep tx
    // rolled back at workflowCommandService.retryWorkflow), so findByIdempotencyKey returns
    // empty and resolveConcurrentReplay propagates the conflict by design. Pins the
    // ordering-invariant documented in the class Javadoc. (review A2 / 2026-05-16)
    stubFailedRun();
    stubFailureEventPresent();
    stubLastFailedRunner(RunnerStage.EXECUTION);
    // Pre-insert check sees nothing — empty BOTH times (no recovery_actions row was ever
    // written by either thread because the inner retryWorkflow conflict aborts thread B's
    // prep tx before insert).
    when(recoveryRecordPort.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.empty());
    DomainException innerConflict =
        new DomainException(
            DomainErrorCode.IDEMPOTENCY_KEY_CONFLICT,
            "WorkflowCommandService.retryWorkflow idempotency conflict",
            Map.of("idempotencyKey", IDEMPOTENCY_KEY, "commandType", "RetryWorkflowCommand"));
    doThrow(innerConflict)
        .when(workflowCommandService)
        .retryWorkflow(any(RetryWorkflowCommand.class));

    DomainException error =
        assertThrows(
            DomainException.class, () -> service.retry(RUN, IDEMPOTENCY_KEY, actor(), null));
    assertEquals(DomainErrorCode.IDEMPOTENCY_KEY_CONFLICT, error.errorCode());
    // Loser never reached recovery_actions insert and never dispatched.
    verify(recoveryRecordPort, never()).insert(any(RecoveryActionWriteCommand.class));
    verify(runnerBroker, never()).dispatch(any(), any(), any(), any());
  }

  @Test
  void retryConcurrentLoserRefusesReplayWhenWinnerRowCarriesNullResultingEventId() {
    // Defensive: recovery_actions.resulting_event_id is `ON DELETE SET NULL` per V1 schema, so
    // a succeeded prior row could carry a null event id (linked event archived/deleted). The
    // loser must propagate the conflict rather than emit a replay with a null event id.
    // (review E13 / 2026-05-16)
    stubFailedRun();
    stubFailureEventPresent();
    stubLastFailedRunner(RunnerStage.EXECUTION);
    RecoveryActionSnapshot winnerWithNullEvent =
        new RecoveryActionSnapshot(
            "rcv_winner-noevent",
            7777L,
            RUN,
            "retry",
            "evt_failure-aaaa1",
            null,
            "alex",
            ActorType.HUMAN,
            IDEMPOTENCY_KEY,
            "succeeded",
            OffsetDateTime.parse("2026-05-15T11:55:00Z"));
    when(recoveryRecordPort.findByIdempotencyKey(IDEMPOTENCY_KEY))
        .thenReturn(Optional.empty())
        .thenReturn(Optional.of(winnerWithNullEvent));
    when(recoveryRecordPort.insert(any(RecoveryActionWriteCommand.class)))
        .thenThrow(
            new DomainException(
                DomainErrorCode.IDEMPOTENCY_KEY_CONFLICT,
                "recovery_actions unique violation",
                Map.of("idempotencyKey", IDEMPOTENCY_KEY)));

    DomainException error =
        assertThrows(
            DomainException.class, () -> service.retry(RUN, IDEMPOTENCY_KEY, actor(), null));
    assertEquals(DomainErrorCode.IDEMPOTENCY_KEY_CONFLICT, error.errorCode());
    verify(runnerBroker, never()).dispatch(any(), any(), any(), any());
  }

  @Test
  void describeFailureOnFailedRunWithoutFailureEventReturnsAwaitManualReconciliation() {
    // describeFailure must not recommend `retry` when retry would itself reject the call
    // because no workflow.stateChanged→Failed event exists to link the audit row.
    // (review E5 / 2026-05-16)
    stubFailedRun();
    when(eventReadPort.findLatestFailureEvent(RUN)).thenReturn(Optional.empty());
    when(eventReadPort.findLatestCorrelationId(RUN)).thenReturn(Optional.empty());
    when(artifactOperationPort.hasFailedOrFailedOrphanForRun(RUN)).thenReturn(false);
    when(runnerRecordPort.findByWorkflowRunPublicIdAndStatusIn(eq(RUN), any()))
        .thenReturn(List.of());

    FailureDescription failure = service.describeFailure(RUN);
    assertEquals(WorkflowState.FAILED, failure.currentState());
    assertEquals(
        "await_manual_reconciliation",
        failure.nextSafeAction(),
        "Missing failure event makes retry impossible — must not recommend retry");
    assertNull(failure.failedStage());
  }

  @Test
  void describeFailureOnFailedRunWithoutFailedRunnerExecutionReturnsAwaitManualReconciliation() {
    // Symmetric to the above: no failed runner_execution → retry would throw
    // `RETRY_NOT_APPLICABLE`, so describeFailure must surface inconsistency rather than
    // recommend retry. (review E6 / 2026-05-16)
    stubFailedRun();
    stubFailureEventPresent();
    when(eventReadPort.findLatestCorrelationId(RUN)).thenReturn(Optional.empty());
    when(artifactOperationPort.hasFailedOrFailedOrphanForRun(RUN)).thenReturn(false);
    // No failed runner_execution rows.
    when(runnerRecordPort.findByWorkflowRunPublicIdAndStatusIn(eq(RUN), any()))
        .thenReturn(List.of());

    FailureDescription failure = service.describeFailure(RUN);
    assertEquals(WorkflowState.FAILED, failure.currentState());
    assertEquals("await_manual_reconciliation", failure.nextSafeAction());
    assertNull(failure.failedStage());
  }

  @Test
  void describeFailureNormalizesFailureTimestampAndLastActivityTimestampToUtc() {
    // Status JSON timestamps must be UTC regardless of the session timezone the JPA mapper
    // returns — matches the recovery.retried event's withOffsetSameInstant(UTC) normalization
    // applied at append time. (review E8 / 2026-05-16)
    stubFailedRun();
    // Failure event timestamp in +03:00 offset (Helsinki); should render UTC.
    OffsetDateTime failureAtNonUtc = OffsetDateTime.parse("2026-05-15T14:50:00+03:00");
    WorkflowEventRecord failureEvent =
        new WorkflowEventRecord(
            "evt_failure-utc01",
            RUN,
            WorkflowEventType.WORKFLOW_STATE_CHANGED,
            WorkflowState.EXECUTING,
            WorkflowState.FAILED,
            "system",
            ActorType.SYSTEM,
            "runner timeout",
            org.dradgo.domain.registry.FailureCategory.RUNNER_TIMEOUT,
            false,
            failureAtNonUtc,
            Map.of());
    when(eventReadPort.findLatestFailureEvent(RUN)).thenReturn(Optional.of(failureEvent));
    // Runner last_activity_at in -05:00 offset; should also render UTC.
    OffsetDateTime activityAtNonUtc = OffsetDateTime.parse("2026-05-15T06:45:00-05:00");
    // RunnerExecutionSnapshot: (publicId, runId, stage, status, ctxVersion, lastActivityAt,
    // timeoutAt, failureCategory, completedAt, createdAt, archivedAt).
    RunnerExecutionSnapshot runner =
        new RunnerExecutionSnapshot(
            "rex_failed-utc01",
            RUN,
            RunnerStage.EXECUTION,
            RunnerExecutionStatus.FAILED,
            1,
            activityAtNonUtc, // lastActivityAt — under test
            OffsetDateTime.parse("2026-05-15T12:30:00Z"), // timeoutAt
            org.dradgo.domain.registry.FailureCategory.RUNNER_TIMEOUT,
            failureAtNonUtc, // completedAt
            OffsetDateTime.parse("2026-05-15T11:30:00Z"), // createdAt
            null);
    when(runnerRecordPort.findByWorkflowRunPublicIdAndStatusIn(eq(RUN), any()))
        .thenReturn(List.of(runner));
    when(eventReadPort.findLatestCorrelationId(RUN)).thenReturn(Optional.empty());
    when(artifactOperationPort.hasFailedOrFailedOrphanForRun(RUN)).thenReturn(false);

    FailureDescription failure = service.describeFailure(RUN);
    assertEquals(
        ZoneOffset.UTC,
        failure.failureTimestamp().getOffset(),
        "failureTimestamp must render in UTC offset");
    assertEquals(
        ZoneOffset.UTC,
        failure.lastActivityTimestamp().getOffset(),
        "lastActivityTimestamp must render in UTC offset");
    // Instants must match the input wall-clock instants (only the offset changes).
    assertEquals(failureAtNonUtc.toInstant(), failure.failureTimestamp().toInstant());
    assertEquals(activityAtNonUtc.toInstant(), failure.lastActivityTimestamp().toInstant());
  }

  @Test
  void describeFailureReturnsNullFailureCategoryWhenFailureEventCarriesNone() {
    // Coverage gap: prior tests all preset RUNNER_TIMEOUT. Pin the null branch so a future
    // schema change to allow null failure_category in workflow_events is observed.
    // (review B18 / 2026-05-16)
    stubFailedRun();
    stubLastFailedRunner(RunnerStage.EXECUTION);
    WorkflowEventRecord failureEventWithoutCategory =
        new WorkflowEventRecord(
            "evt_failure-nocat1",
            RUN,
            WorkflowEventType.WORKFLOW_STATE_CHANGED,
            WorkflowState.EXECUTING,
            WorkflowState.FAILED,
            "system",
            ActorType.SYSTEM,
            "runner exited",
            null, // failureCategory absent
            false,
            FAILURE_AT,
            Map.of());
    when(eventReadPort.findLatestFailureEvent(RUN))
        .thenReturn(Optional.of(failureEventWithoutCategory));
    when(eventReadPort.findLatestCorrelationId(RUN)).thenReturn(Optional.empty());
    when(artifactOperationPort.hasFailedOrFailedOrphanForRun(RUN)).thenReturn(false);

    FailureDescription failure = service.describeFailure(RUN);
    assertEquals(WorkflowState.FAILED, failure.currentState());
    assertNull(
        failure.failureCategory(),
        "failureCategory must be null when the failure event carries no category");
    // Other diagnostic fields still populate from the available data.
    assertEquals("execution", failure.failedStage());
  }

  @Test
  void retryOnFailedRunWithoutFailureEventRaisesRetryNotApplicable() {
    stubFailedRun();
    when(recoveryRecordPort.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.empty());
    when(eventReadPort.findLatestFailureEvent(RUN)).thenReturn(Optional.empty());

    DomainException error =
        assertThrows(
            DomainException.class, () -> service.retry(RUN, IDEMPOTENCY_KEY, actor(), null));
    assertEquals(DomainErrorCode.RETRY_NOT_APPLICABLE, error.errorCode());
    assertEquals("no_failure_event_to_link", error.details().get("reason"));

    verify(workflowCommandService, never()).retryWorkflow(any());
    verify(eventWritePort, never()).append(any());
    verify(recoveryRecordPort, never()).insert(any(RecoveryActionWriteCommand.class));
  }

  @Test
  void retryRejectsWhenNoFailedRunnerExecutionExistsToRedispatch() {
    stubFailedRun();
    when(recoveryRecordPort.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.empty());
    stubFailureEventPresent();
    when(runnerRecordPort.findByWorkflowRunPublicIdAndStatusIn(eq(RUN), any()))
        .thenReturn(List.of());

    DomainException error =
        assertThrows(
            DomainException.class, () -> service.retry(RUN, IDEMPOTENCY_KEY, actor(), null));
    assertEquals(DomainErrorCode.RETRY_NOT_APPLICABLE, error.errorCode());
    assertEquals("no_failed_runner_execution_to_redispatch", error.details().get("reason"));
  }

  @Test
  void retryDispatchFailureFlipsRecoveryActionToFailedAndPropagatesException() {
    stubFailedRun();
    stubFailureEventPresent();
    stubLastFailedRunner(RunnerStage.INVESTIGATION);
    when(recoveryRecordPort.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.empty());
    when(recoveryRecordPort.insert(any(RecoveryActionWriteCommand.class)))
        .thenReturn(recoveryActionSnapshot("rcv_recov-ccccc", "pending"));
    when(runnerBroker.dispatch(eq(RUN), eq(RunnerStage.INVESTIGATION), any(), any()))
        .thenThrow(new IllegalStateException("broker network failure"));

    IllegalStateException error =
        assertThrows(
            IllegalStateException.class, () -> service.retry(RUN, IDEMPOTENCY_KEY, actor(), null));
    assertTrue(error.getMessage().contains("broker network failure"));
    verify(recoveryRecordPort, times(1)).markFailed(IDEMPOTENCY_KEY);
    verify(recoveryRecordPort, never()).markSucceeded(any());
  }

  @Test
  void retryDispatchFailurePreservesOriginalBrokerExceptionWhenMarkFailedAlsoFails() {
    stubFailedRun();
    stubFailureEventPresent();
    stubLastFailedRunner(RunnerStage.INVESTIGATION);
    when(recoveryRecordPort.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.empty());
    when(recoveryRecordPort.insert(any(RecoveryActionWriteCommand.class)))
        .thenReturn(recoveryActionSnapshot("rcv_recov-ccccc", "pending"));
    when(runnerBroker.dispatch(eq(RUN), eq(RunnerStage.INVESTIGATION), any(), any()))
        .thenThrow(new IllegalStateException("broker network failure"));
    when(recoveryRecordPort.markFailed(IDEMPOTENCY_KEY))
        .thenThrow(
            new DomainException(DomainErrorCode.INTERNAL_ERROR, "mark failed blew up", Map.of()));

    IllegalStateException error =
        assertThrows(
            IllegalStateException.class, () -> service.retry(RUN, IDEMPOTENCY_KEY, actor(), null));
    assertTrue(error.getMessage().contains("broker network failure"));
    assertEquals(1, error.getSuppressed().length);
  }

  @Test
  void retryRaisesInternalErrorWhenMarkSucceededFailsToReachTerminalResultStatus() {
    stubFailedRun();
    stubFailureEventPresent();
    stubLastFailedRunner(RunnerStage.EXECUTION);
    when(recoveryRecordPort.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.empty());
    when(recoveryRecordPort.insert(any(RecoveryActionWriteCommand.class)))
        .thenReturn(recoveryActionSnapshot("rcv_recov-aaaaa", "pending"));
    stubBrokerDispatch("rex_new1-bbbbb", RunnerStage.EXECUTION);
    DomainException rootCause =
        new DomainException(DomainErrorCode.INTERNAL_ERROR, "mark succeeded blew up", Map.of());
    when(recoveryRecordPort.markSucceeded(IDEMPOTENCY_KEY)).thenThrow(rootCause);

    // AC9: recovery_actions.result_status must reach a terminal value before retry reports
    // success. When the markSucceeded flip fails after the runner has dispatched, the row
    // stays at `pending`; surfacing INTERNAL_ERROR (with the new-runner id in details) flags
    // the degraded state to the operator instead of silently lying about audit terminality.
    // (review F526)
    DomainException error =
        assertThrows(
            DomainException.class, () -> service.retry(RUN, IDEMPOTENCY_KEY, actor(), null));
    assertEquals(DomainErrorCode.INTERNAL_ERROR, error.errorCode());
    assertEquals("result_status_flip_failed_after_dispatch", error.details().get("reason"));
    assertEquals("rex_new1-bbbbb", error.details().get("newRunnerExecutionId"));
    assertEquals("rcv_recov-aaaaa", error.details().get("recoveryActionId"));
    assertEquals(IDEMPOTENCY_KEY, error.details().get("idempotencyKey"));
    assertEquals(1, error.getSuppressed().length);
    assertEquals(rootCause, error.getSuppressed()[0]);
    // The broker dispatched, the audit row is still in `pending` — this is the documented
    // degraded state the operator must reconcile, not a behaviour bug.
    verify(runnerBroker)
        .dispatch(
            eq(RUN),
            eq(RunnerStage.EXECUTION),
            eq(IDEMPOTENCY_KEY + ":runner"),
            any(ActorContext.class));
  }

  @Test
  void retryAppendOnlyOrchestrationUsesOnlyTransitionAndRecoveryEventMutationPaths() {
    stubFailedRun();
    stubFailureEventPresent();
    stubLastFailedRunner(RunnerStage.EXECUTION);
    when(recoveryRecordPort.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.empty());
    when(recoveryRecordPort.insert(any(RecoveryActionWriteCommand.class)))
        .thenReturn(recoveryActionSnapshot("rcv_recov-aaaaa", "pending"));
    when(recoveryRecordPort.markSucceeded(IDEMPOTENCY_KEY))
        .thenReturn(recoveryActionSnapshot("rcv_recov-aaaaa", "succeeded"));
    stubBrokerDispatch("rex_new1-bbbbb", RunnerStage.EXECUTION);

    service.retry(RUN, IDEMPOTENCY_KEY, actor(), null);

    verify(workflowCommandService, times(1)).retryWorkflow(any(RetryWorkflowCommand.class));
    verify(eventWritePort, times(1)).append(any(WorkflowEventRecord.class));
    verify(recoveryRecordPort, times(1)).findByIdempotencyKey(IDEMPOTENCY_KEY);
    verify(recoveryRecordPort, times(1)).insert(any(RecoveryActionWriteCommand.class));
    verify(recoveryRecordPort, times(1)).markSucceeded(IDEMPOTENCY_KEY);
    verify(recoveryRecordPort, never()).markFailed(any());
    verifyNoMoreInteractions(
        workflowCommandService, eventWritePort, artifactOperationPort, recoveryRecordPort);
  }

  @Test
  void describeFailureOnFailedRunWithoutArtifactOpConflictReturnsRetry() {
    stubFailedRun();
    stubFailureEventPresent();
    stubLastFailedRunner(RunnerStage.EXECUTION);
    when(eventReadPort.findLatestCorrelationId(RUN)).thenReturn(Optional.of("corr-99"));
    when(artifactOperationPort.hasFailedOrFailedOrphanForRun(RUN)).thenReturn(false);

    FailureDescription failure = service.describeFailure(RUN);

    assertEquals(WorkflowState.FAILED, failure.currentState());
    assertEquals("execution", failure.failedStage());
    assertEquals("Executing", failure.lastSuccessfulStage());
    assertEquals(FAILURE_AT, failure.failureTimestamp());
    assertEquals("runner_timeout", failure.failureCategory());
    assertEquals(LAST_ACTIVITY_AT, failure.lastActivityTimestamp());
    assertEquals("retry", failure.nextSafeAction());
    assertEquals("corr-99", failure.diagnosticReferenceCorrelationId());
  }

  @Test
  void describeFailureLastActivityUsesMostRecentRunnerRegardlessOfStatus() {
    stubFailedRun();
    stubFailureEventPresent();
    when(eventReadPort.findLatestCorrelationId(RUN)).thenReturn(Optional.empty());
    when(artifactOperationPort.hasFailedOrFailedOrphanForRun(RUN)).thenReturn(false);

    RunnerExecutionSnapshot failedSnapshot =
        new RunnerExecutionSnapshot(
            "rex_failed-aaaa1",
            RUN,
            RunnerStage.EXECUTION,
            RunnerExecutionStatus.FAILED,
            1,
            OffsetDateTime.parse("2026-05-15T11:40:00Z"),
            OffsetDateTime.parse("2026-05-15T12:30:00Z"),
            FailureCategory.RUNNER_TIMEOUT,
            FAILURE_AT,
            OffsetDateTime.parse("2026-05-15T11:30:00Z"),
            null);
    // A later non-failed runner row (e.g. the broker recorded a fresh PENDING after retry).
    RunnerExecutionSnapshot pendingSnapshot =
        new RunnerExecutionSnapshot(
            "rex_pending-bbbb2",
            RUN,
            RunnerStage.EXECUTION,
            RunnerExecutionStatus.PENDING,
            2,
            OffsetDateTime.parse("2026-05-15T11:58:00Z"),
            OffsetDateTime.parse("2026-05-15T12:58:00Z"),
            null,
            null,
            OffsetDateTime.parse("2026-05-15T11:55:00Z"),
            null);
    when(runnerRecordPort.findByWorkflowRunPublicIdAndStatusIn(eq(RUN), argThat(includesFailed())))
        .thenReturn(List.of(failedSnapshot));
    when(runnerRecordPort.findByWorkflowRunPublicIdAndStatusIn(
            eq(RUN), argThat(includesAllStatuses())))
        .thenReturn(List.of(failedSnapshot, pendingSnapshot));

    FailureDescription failure = service.describeFailure(RUN);

    assertEquals("execution", failure.failedStage());
    // lastActivityTimestamp picks the PENDING runner's last_activity_at because it has the
    // largest created_at — the failed runner is older.
    assertEquals(OffsetDateTime.parse("2026-05-15T11:58:00Z"), failure.lastActivityTimestamp());
  }

  @Test
  void describeFailureOnFailedRunWithArtifactOpConflictReturnsAwaitManualReconciliation() {
    stubFailedRun();
    stubFailureEventPresent();
    stubLastFailedRunner(RunnerStage.EXECUTION);
    when(eventReadPort.findLatestCorrelationId(RUN)).thenReturn(Optional.empty());
    when(artifactOperationPort.hasFailedOrFailedOrphanForRun(RUN)).thenReturn(true);

    FailureDescription failure = service.describeFailure(RUN);

    assertEquals("await_manual_reconciliation", failure.nextSafeAction());
  }

  @Test
  void describeFailureOnExecutingRunReturnsAwaitOutcomeAndNullDiagnostics() {
    when(runReadPort.findByPublicId(RUN))
        .thenReturn(
            Optional.of(new WorkflowRunSnapshot(RUN, WorkflowState.EXECUTING, null, 1L, 0, false)));
    when(eventReadPort.findLatestCorrelationId(RUN)).thenReturn(Optional.empty());

    FailureDescription failure = service.describeFailure(RUN);

    assertEquals(WorkflowState.EXECUTING, failure.currentState());
    assertNull(failure.failedStage());
    assertNull(failure.failureTimestamp());
    assertEquals("await_outcome", failure.nextSafeAction());
    assertNull(failure.diagnosticReferenceCorrelationId());
  }

  @Test
  void describeFailureOnCompletedRunReturnsViewOnly() {
    when(runReadPort.findByPublicId(RUN))
        .thenReturn(
            Optional.of(new WorkflowRunSnapshot(RUN, WorkflowState.COMPLETED, null, 1L, 0, false)));
    when(eventReadPort.findLatestCorrelationId(RUN)).thenReturn(Optional.empty());

    FailureDescription failure = service.describeFailure(RUN);
    assertEquals("view_only", failure.nextSafeAction());
  }

  @Test
  void describeFailureOnTakenOverAndReconciledAlsoReturnsViewOnly() {
    when(eventReadPort.findLatestCorrelationId(RUN)).thenReturn(Optional.empty());
    when(runReadPort.findByPublicId(RUN))
        .thenReturn(
            Optional.of(
                new WorkflowRunSnapshot(RUN, WorkflowState.TAKEN_OVER, null, 1L, 0, false)));
    assertEquals("view_only", service.describeFailure(RUN).nextSafeAction());

    when(runReadPort.findByPublicId(RUN))
        .thenReturn(
            Optional.of(
                new WorkflowRunSnapshot(RUN, WorkflowState.RECONCILED, null, 1L, 0, false)));
    assertEquals("view_only", service.describeFailure(RUN).nextSafeAction());
  }

  @Test
  void retryDispatchFailureAppendsRecoveryDispatchFailedEventWithBrokerErrorDetails() {
    stubFailedRun();
    stubFailureEventPresent();
    stubLastFailedRunner(RunnerStage.EXECUTION);
    when(recoveryRecordPort.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.empty());
    when(recoveryRecordPort.insert(any(RecoveryActionWriteCommand.class)))
        .thenReturn(recoveryActionSnapshot("rcv_recov-fffff", "pending"));
    DomainException brokerError =
        new DomainException(
            DomainErrorCode.RUNNER_CONTRACT_VIOLATION, "runner adapter unreachable", Map.of());
    when(runnerBroker.dispatch(eq(RUN), eq(RunnerStage.EXECUTION), any(), any()))
        .thenThrow(brokerError);

    DomainException error =
        assertThrows(
            DomainException.class, () -> service.retry(RUN, IDEMPOTENCY_KEY, actor(), "transient"));
    assertEquals(DomainErrorCode.RUNNER_CONTRACT_VIOLATION, error.errorCode());

    ArgumentCaptor<WorkflowEventRecord> appended =
        ArgumentCaptor.forClass(WorkflowEventRecord.class);
    verify(eventWritePort, times(2)).append(appended.capture());
    WorkflowEventRecord dispatchFailed = appended.getAllValues().get(1);
    assertEquals(WorkflowEventType.RECOVERY_DISPATCH_FAILED, dispatchFailed.eventType());
    assertEquals(WorkflowState.EXECUTING, dispatchFailed.priorState());
    assertEquals(WorkflowState.EXECUTING, dispatchFailed.resultingState());
    assertTrue(dispatchFailed.interventionMarker());
    assertTrue(dispatchFailed.reason().startsWith("broker dispatch failed: "));
    assertEquals("execution", dispatchFailed.details().get("failedStage"));
    assertEquals("rcv_recov-fffff", dispatchFailed.details().get("recoveryActionId"));
    assertNotNull(dispatchFailed.details().get("recoveryRetriedEventId"));
    assertEquals(IDEMPOTENCY_KEY, dispatchFailed.details().get("idempotencyKey"));
    assertEquals("RUNNER_CONTRACT_VIOLATION", dispatchFailed.details().get("errorCode"));
    assertEquals("DomainException", dispatchFailed.details().get("errorClass"));
    assertEquals("corr-retry-1", dispatchFailed.details().get("correlationId"));
    assertEquals("transient", dispatchFailed.details().get("reason"));
    assertNull(dispatchFailed.details().get("compensationFailed"));
  }

  @Test
  void retryDispatchFailureFallsBackToRuntimeErrorCodeWhenBrokerThrowsNonDomainException() {
    stubFailedRun();
    stubFailureEventPresent();
    stubLastFailedRunner(RunnerStage.EXECUTION);
    when(recoveryRecordPort.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.empty());
    when(recoveryRecordPort.insert(any(RecoveryActionWriteCommand.class)))
        .thenReturn(recoveryActionSnapshot("rcv_recov-fffff", "pending"));
    when(runnerBroker.dispatch(eq(RUN), eq(RunnerStage.EXECUTION), any(), any()))
        .thenThrow(new IllegalStateException("socket closed"));

    assertThrows(
        IllegalStateException.class, () -> service.retry(RUN, IDEMPOTENCY_KEY, actor(), null));

    ArgumentCaptor<WorkflowEventRecord> appended =
        ArgumentCaptor.forClass(WorkflowEventRecord.class);
    verify(eventWritePort, times(2)).append(appended.capture());
    WorkflowEventRecord dispatchFailed = appended.getAllValues().get(1);
    assertEquals("RUNTIME_ERROR", dispatchFailed.details().get("errorCode"));
    assertEquals("IllegalStateException", dispatchFailed.details().get("errorClass"));
    assertEquals("broker dispatch failed: RUNTIME_ERROR", dispatchFailed.reason());
  }

  @Test
  void
      retryDispatchFailureWithCompensationFailureStampsCompensationFailedFlagInDispatchFailedEvent() {
    stubFailedRun();
    stubFailureEventPresent();
    stubLastFailedRunner(RunnerStage.EXECUTION);
    when(recoveryRecordPort.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.empty());
    when(recoveryRecordPort.insert(any(RecoveryActionWriteCommand.class)))
        .thenReturn(recoveryActionSnapshot("rcv_recov-ggggg", "pending"));
    when(runnerBroker.dispatch(eq(RUN), eq(RunnerStage.EXECUTION), any(), any()))
        .thenThrow(new IllegalStateException("broker network failure"));
    when(recoveryRecordPort.markFailed(IDEMPOTENCY_KEY))
        .thenThrow(
            new DomainException(DomainErrorCode.INTERNAL_ERROR, "mark failed blew up", Map.of()));

    assertThrows(
        IllegalStateException.class, () -> service.retry(RUN, IDEMPOTENCY_KEY, actor(), null));

    ArgumentCaptor<WorkflowEventRecord> appended =
        ArgumentCaptor.forClass(WorkflowEventRecord.class);
    verify(eventWritePort, times(2)).append(appended.capture());
    WorkflowEventRecord dispatchFailed = appended.getAllValues().get(1);
    assertEquals(Boolean.TRUE, dispatchFailed.details().get("compensationFailed"));
  }

  @Test
  void retryDispatchFailureSuppressesAuditAppendFailureOntoOriginalBrokerErrorWithoutMasking() {
    stubFailedRun();
    stubFailureEventPresent();
    stubLastFailedRunner(RunnerStage.EXECUTION);
    when(recoveryRecordPort.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.empty());
    when(recoveryRecordPort.insert(any(RecoveryActionWriteCommand.class)))
        .thenReturn(recoveryActionSnapshot("rcv_recov-hhhhh", "pending"));
    IllegalStateException brokerError = new IllegalStateException("broker network failure");
    when(runnerBroker.dispatch(eq(RUN), eq(RunnerStage.EXECUTION), any(), any()))
        .thenThrow(brokerError);
    // First append (recovery.retried during prep) succeeds; second append (recovery.dispatchFailed
    // in catch) blows up — suppressed onto the broker error, never masking it.
    org.mockito.Mockito.doNothing()
        .doThrow(new RuntimeException("event store unavailable"))
        .when(eventWritePort)
        .append(any());

    IllegalStateException error =
        assertThrows(
            IllegalStateException.class, () -> service.retry(RUN, IDEMPOTENCY_KEY, actor(), null));
    assertTrue(error.getMessage().contains("broker network failure"));
    boolean carriesSuppressedAuditError = false;
    for (Throwable suppressed : error.getSuppressed()) {
      if (suppressed.getMessage() != null
          && suppressed.getMessage().contains("event store unavailable")) {
        carriesSuppressedAuditError = true;
      }
    }
    assertTrue(
        carriesSuppressedAuditError,
        "original broker error should carry the audit-append failure as a suppressed throwable");
  }

  @Test
  void describeFailureExtractsCorrelationIdFromHistoricalEventNotJustTheLatest() {
    when(runReadPort.findByPublicId(RUN))
        .thenReturn(
            Optional.of(new WorkflowRunSnapshot(RUN, WorkflowState.EXECUTING, null, 1L, 0, false)));
    // The port walks newest-first internally; tests stub the resolved value to demonstrate
    // that RecoveryService no longer reads only `findLatestByWorkflowRunPublicId(...)`.
    when(eventReadPort.findLatestCorrelationId(RUN)).thenReturn(Optional.of("corr-from-history"));

    FailureDescription failure = service.describeFailure(RUN);

    assertEquals("corr-from-history", failure.diagnosticReferenceCorrelationId());
  }

  private static org.mockito.ArgumentMatcher<List<RunnerExecutionStatus>> includesFailed() {
    return statuses ->
        statuses != null
            && statuses.contains(RunnerExecutionStatus.FAILED)
            && !statuses.contains(RunnerExecutionStatus.PENDING);
  }

  private static org.mockito.ArgumentMatcher<List<RunnerExecutionStatus>> includesAllStatuses() {
    return statuses ->
        statuses != null
            && statuses.contains(RunnerExecutionStatus.PENDING)
            && statuses.contains(RunnerExecutionStatus.RUNNING)
            && statuses.contains(RunnerExecutionStatus.COMPLETED)
            && statuses.contains(RunnerExecutionStatus.FAILED);
  }

  private void stubFailedRun() {
    when(runReadPort.findByPublicId(RUN))
        .thenReturn(
            Optional.of(new WorkflowRunSnapshot(RUN, WorkflowState.FAILED, null, 5L, 0, false)));
  }

  private void stubFailureEventPresent() {
    when(eventReadPort.findLatestFailureEvent(RUN))
        .thenReturn(
            Optional.of(
                new WorkflowEventRecord(
                    "evt_failure-aaaa1",
                    RUN,
                    WorkflowEventType.WORKFLOW_STATE_CHANGED,
                    WorkflowState.EXECUTING,
                    WorkflowState.FAILED,
                    "system",
                    ActorType.SYSTEM,
                    "runner failure",
                    FailureCategory.RUNNER_TIMEOUT,
                    false,
                    FAILURE_AT,
                    Map.of())));
  }

  private void stubLastFailedRunner(RunnerStage stage) {
    when(runnerRecordPort.findByWorkflowRunPublicIdAndStatusIn(eq(RUN), any()))
        .thenReturn(
            List.of(
                new RunnerExecutionSnapshot(
                    "rex_failed-aaaa1",
                    RUN,
                    stage,
                    RunnerExecutionStatus.FAILED,
                    1,
                    LAST_ACTIVITY_AT,
                    OffsetDateTime.parse("2026-05-15T12:30:00Z"),
                    FailureCategory.RUNNER_TIMEOUT,
                    FAILURE_AT,
                    OffsetDateTime.parse("2026-05-15T11:45:00Z"),
                    null)));
  }

  private void stubBrokerDispatch(String newRexPublicId, RunnerStage stage) {
    RunnerExecutionHandle handle =
        new RunnerExecutionHandle(
            newRexPublicId,
            RUN,
            stage,
            RunnerExecutionStatus.PENDING,
            OffsetDateTime.parse("2026-05-15T12:30:00Z"));
    when(runnerBroker.dispatch(eq(RUN), eq(stage), any(), any()))
        .thenReturn(new RunnerDispatchResult.Replayed(handle));
  }

  private RecoveryActionSnapshot recoveryActionSnapshot(String publicId, String resultStatus) {
    return new RecoveryActionSnapshot(
        publicId,
        1L,
        RUN,
        "retry",
        "evt_failure-aaaa1",
        null,
        "alex",
        ActorType.HUMAN,
        IDEMPOTENCY_KEY,
        resultStatus,
        OffsetDateTime.parse("2026-05-15T12:00:00Z"));
  }

  private static ActorContext actor() {
    return new ActorContext("alex", ActorType.HUMAN, "corr-retry-1");
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
