package org.dradgo.application.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.dradgo.application.idempotency.IdempotencyKeyValidator;
import org.dradgo.application.integration.IntegrationLink;
import org.dradgo.application.integration.IntegrationLinkService;
import org.dradgo.application.recovery.spi.RecoveryActionRecordPort;
import org.dradgo.application.recovery.spi.RecoveryActionSnapshot;
import org.dradgo.application.recovery.spi.RecoveryActionWriteCommand;
import org.dradgo.application.runner.spi.RunnerAdapter;
import org.dradgo.application.runner.spi.RunnerExecutionRecordPort;
import org.dradgo.application.runner.spi.RunnerExecutionSnapshot;
import org.dradgo.application.runner.spi.RunnerTakeoverCancellation;
import org.dradgo.application.workflow.WorkflowCommandService;
import org.dradgo.application.workflow.WorkflowStateChangeResult;
import org.dradgo.application.workflow.commands.TakeoverWorkflowCommand;
import org.dradgo.application.workflow.spi.WorkflowEventReadPort;
import org.dradgo.application.workflow.spi.WorkflowEventRecord;
import org.dradgo.application.workflow.spi.WorkflowRunReadPort;
import org.dradgo.application.workflow.spi.WorkflowRunSnapshot;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.IntegrationSyncStatus;
import org.dradgo.domain.registry.RunnerExecutionStatus;
import org.dradgo.domain.registry.RunnerStage;
import org.dradgo.domain.registry.WorkflowEventType;
import org.dradgo.domain.registry.WorkflowState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Unit coverage for {@link DeveloperTakeoverService} (story 3.22): happy-path takeover (cancels
 * in-flight + queued, inserts the developer-attributed recovery_actions row, marks succeeded),
 * illegal-transition propagation, idempotent replay + idempotency conflict, best-effort
 * container-cancel failure tolerance, and the SLF4J log-line pins (Logback ListAppender — mirror
 * RecoveryLoggingContractTest).
 */
class DeveloperTakeoverServiceTest {

  private static final String RUN = "run_takeover1234";
  private static final String IDEMPOTENCY_KEY = "idem-takeover-1234567890";
  private static final String PR_REF = "octo/widgets#42";
  private static final String REASON = "developer continuing manually";
  private static final OffsetDateTime FIXED_NOW = OffsetDateTime.parse("2026-06-15T12:00:00Z");

  private WorkflowRunReadPort runReadPort;
  private WorkflowEventReadPort eventReadPort;
  private RunnerExecutionRecordPort runnerRecordPort;
  private WorkflowCommandService workflowCommandService;
  private RecoveryActionRecordPort recoveryRecordPort;
  private IntegrationLinkService integrationLinkService;
  private RunnerAdapter runnerAdapter;
  private DeveloperTakeoverService service;
  private ListAppender<ILoggingEvent> appender;

  @BeforeEach
  void setUp() {
    runReadPort = mock(WorkflowRunReadPort.class);
    eventReadPort = mock(WorkflowEventReadPort.class);
    runnerRecordPort = mock(RunnerExecutionRecordPort.class);
    workflowCommandService = mock(WorkflowCommandService.class);
    recoveryRecordPort = mock(RecoveryActionRecordPort.class);
    integrationLinkService = mock(IntegrationLinkService.class);
    runnerAdapter = mock(RunnerAdapter.class);
    service =
        new DeveloperTakeoverService(
            runReadPort,
            eventReadPort,
            runnerRecordPort,
            workflowCommandService,
            recoveryRecordPort,
            new IdempotencyKeyValidator(),
            integrationLinkService,
            runnerAdapter,
            Clock.fixed(FIXED_NOW.toInstant(), ZoneOffset.UTC),
            callthroughTemplate(),
            callthroughTemplate());

    appender = new ListAppender<>();
    appender.start();
    ((Logger) LoggerFactory.getLogger(DeveloperTakeoverService.class)).addAppender(appender);
  }

  @AfterEach
  void tearDown() {
    ((Logger) LoggerFactory.getLogger(DeveloperTakeoverService.class)).detachAppender(appender);
  }

  @Test
  void takeoverCancelsInFlightAndQueuedInsertsRecoveryActionAndMarksSucceeded() {
    stubHappyTakeover();

    TakeoverResult result = service.takeoverWorkflow(command());

    assertFalse(result.replayed());
    assertEquals(RUN, result.workflowRunId());
    assertEquals("rcv_take-aaaaa", result.recoveryActionPublicId());
    assertEquals(WorkflowState.TAKEN_OVER, result.resultingState());
    assertEquals("evt_takeover-bbbb", result.resultingEventPublicId());
    // queued=1, pending+running=2 in-flight.
    assertEquals(2, result.cancelledInFlightCount());
    assertEquals(1, result.cancelledQueuedCount());
    assertEquals(PR_REF, result.preservedPrReference());
    assertEquals("corr-takeover-1", result.correlationId());

    verify(workflowCommandService, times(1)).takeoverWorkflow(any(TakeoverWorkflowCommand.class));
    // All three dispatchable rows flipped.
    verify(runnerRecordPort).markCancelledForTakeover(eq("rex_queued-1111"), eq(FIXED_NOW));
    verify(runnerRecordPort).markCancelledForTakeover(eq("rex_pending-2222"), eq(FIXED_NOW));
    verify(runnerRecordPort).markCancelledForTakeover(eq("rex_running-3333"), eq(FIXED_NOW));
    // Best-effort container stop ONLY for the previously-running row.
    verify(runnerAdapter, times(1)).cancel("rex_running-3333");
    verify(runnerAdapter, never()).cancel("rex_queued-1111");
    verify(runnerAdapter, never()).cancel("rex_pending-2222");
    verify(recoveryRecordPort).markSucceeded(IDEMPOTENCY_KEY);

    ArgumentCaptor<RecoveryActionWriteCommand> writeCaptor =
        ArgumentCaptor.forClass(RecoveryActionWriteCommand.class);
    verify(recoveryRecordPort).insert(writeCaptor.capture());
    RecoveryActionWriteCommand write = writeCaptor.getValue();
    assertEquals("takeover", write.actionType());
    assertEquals("developer", write.reviewerRole());
    assertEquals(ActorType.HUMAN, write.actorType());
    assertEquals("pending", write.resultStatus());
    assertEquals(IDEMPOTENCY_KEY, write.idempotencyKey());
    assertEquals("evt_trigger-aaaa", write.triggeringEventPublicId());
    assertEquals("evt_takeover-bbbb", write.resultingEventPublicId());
  }

  @Test
  void takeoverFromTerminalRunPropagatesIllegalTransitionWithoutInsertOrCancel() {
    when(recoveryRecordPort.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.empty());
    when(workflowCommandService.takeoverWorkflow(any(TakeoverWorkflowCommand.class)))
        .thenThrow(
            new DomainException(
                DomainErrorCode.ILLEGAL_TRANSITION,
                "illegal transition to TakenOver from Completed",
                Map.of("runId", RUN)));

    DomainException error =
        assertThrows(DomainException.class, () -> service.takeoverWorkflow(command()));
    assertEquals(DomainErrorCode.ILLEGAL_TRANSITION, error.errorCode());

    verify(recoveryRecordPort, never()).insert(any(RecoveryActionWriteCommand.class));
    verify(runnerRecordPort, never()).markCancelledForTakeover(any(), any());
    verify(runnerAdapter, never()).cancel(any());
    verify(recoveryRecordPort, never()).markSucceeded(any());
    assertTrue(
        logged(Level.WARN, "developer takeover rejected").stream()
            .anyMatch(m -> m.contains("reason=illegal_transition")));
  }

  @Test
  void takeoverWithSucceededPriorActionForSameRunReturnsReplayWithoutSideEffects() {
    when(recoveryRecordPort.findByIdempotencyKey(IDEMPOTENCY_KEY))
        .thenReturn(
            Optional.of(priorAction("rcv_prior-bbbbb", RUN, "succeeded", "evt_prior-cccc")));
    when(runReadPort.findByPublicId(RUN)).thenReturn(Optional.of(run(WorkflowState.TAKEN_OVER)));
    when(workflowCommandService.takeoverWorkflow(any(TakeoverWorkflowCommand.class)))
        .thenReturn(
            new WorkflowStateChangeResult(RUN, WorkflowState.TAKEN_OVER, "corr-takeover-1"));

    TakeoverResult result = service.takeoverWorkflow(command());

    assertTrue(result.replayed());
    assertEquals("rcv_prior-bbbbb", result.recoveryActionPublicId());
    assertEquals(WorkflowState.TAKEN_OVER, result.resultingState());
    assertEquals("evt_prior-cccc", result.resultingEventPublicId());
    assertNull(result.cancelledInFlightCount());
    assertNull(result.cancelledQueuedCount());
    assertNull(result.preservedPrReference());

    verify(workflowCommandService).takeoverWorkflow(any());
    verify(recoveryRecordPort, never()).insert(any(RecoveryActionWriteCommand.class));
    verify(runnerRecordPort, never()).markCancelledForTakeover(any(), any());
    verify(runnerAdapter, never()).cancel(any());
    assertFalse(logged(Level.WARN, "developer takeover replay").isEmpty());
  }

  @Test
  void takeoverRejectsNonHumanActorBeforeSideEffects() {
    TakeoverWorkflowCommand nonHuman =
        new TakeoverWorkflowCommand(
            RUN, "automation", ActorType.SYSTEM, IDEMPOTENCY_KEY, "corr-takeover-1", REASON);

    DomainException error =
        assertThrows(DomainException.class, () -> service.takeoverWorkflow(nonHuman));

    assertEquals(DomainErrorCode.INVALID_COMMAND_PAYLOAD, error.errorCode());
    verifyNoInteractions(
        workflowCommandService, recoveryRecordPort, runnerRecordPort, runnerAdapter);
  }

  @Test
  void takeoverRejectsBlankReasonBeforeSideEffects() {
    TakeoverWorkflowCommand blankReason =
        new TakeoverWorkflowCommand(
            RUN, "alex", ActorType.HUMAN, IDEMPOTENCY_KEY, "corr-takeover-1", " ");

    DomainException error =
        assertThrows(DomainException.class, () -> service.takeoverWorkflow(blankReason));

    assertEquals(DomainErrorCode.INVALID_COMMAND_PAYLOAD, error.errorCode());
    verifyNoInteractions(
        workflowCommandService, recoveryRecordPort, runnerRecordPort, runnerAdapter);
  }

  @Test
  void takeoverRejectsSucceededNonTakeoverRecoveryActionAsReplay() {
    RecoveryActionSnapshot retry =
        new RecoveryActionSnapshot(
            "rcv_retry-1111",
            1L,
            RUN,
            "retry",
            "evt_trigger-aaaa",
            "evt_retry-bbbb",
            "alex",
            ActorType.HUMAN,
            IDEMPOTENCY_KEY,
            "succeeded",
            FIXED_NOW,
            null);
    when(recoveryRecordPort.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.of(retry));

    DomainException error =
        assertThrows(DomainException.class, () -> service.takeoverWorkflow(command()));

    assertEquals(DomainErrorCode.IDEMPOTENCY_KEY_CONFLICT, error.errorCode());
    verify(workflowCommandService, never()).takeoverWorkflow(any());
  }

  @Test
  void takeoverWithPriorActionForDifferentRunRaisesIdempotencyConflict() {
    when(recoveryRecordPort.findByIdempotencyKey(IDEMPOTENCY_KEY))
        .thenReturn(
            Optional.of(
                priorAction("rcv_prior-ccccc", "run_otherrun9999", "succeeded", "evt_o-dddd")));

    DomainException error =
        assertThrows(DomainException.class, () -> service.takeoverWorkflow(command()));
    assertEquals(DomainErrorCode.IDEMPOTENCY_KEY_CONFLICT, error.errorCode());
    assertEquals("run_otherrun9999", error.details().get("priorRunId"));
    assertEquals(RUN, error.details().get("requestedRunId"));
    verify(workflowCommandService, never()).takeoverWorkflow(any());
  }

  @Test
  void takeoverWithNonSucceededPriorActionRaisesIdempotencyConflict() {
    when(recoveryRecordPort.findByIdempotencyKey(IDEMPOTENCY_KEY))
        .thenReturn(Optional.of(priorAction("rcv_prior-ddddd", RUN, "pending", null)));

    DomainException error =
        assertThrows(DomainException.class, () -> service.takeoverWorkflow(command()));
    assertEquals(DomainErrorCode.IDEMPOTENCY_KEY_CONFLICT, error.errorCode());
    assertEquals("pending", error.details().get("priorResultStatus"));
    verify(workflowCommandService, never()).takeoverWorkflow(any());
  }

  @Test
  void takeoverRejectsInvalidIdempotencyKeyBeforeAnyLookup() {
    TakeoverWorkflowCommand bad =
        new TakeoverWorkflowCommand(
            RUN, "alex", ActorType.HUMAN, "bad key", "corr-takeover-1", REASON);

    DomainException error =
        assertThrows(DomainException.class, () -> service.takeoverWorkflow(bad));
    assertEquals(DomainErrorCode.INVALID_IDEMPOTENCY_KEY, error.errorCode());
    verify(recoveryRecordPort, never()).findByIdempotencyKey(any());
    verify(workflowCommandService, never()).takeoverWorkflow(any());
  }

  @Test
  void concurrentLoserSeesReplayWhenWinnerSucceededBetweenChecks() {
    // Pre-check empty; insert then conflicts (sibling won); post-conflict re-read = winner
    // succeeded.
    when(recoveryRecordPort.findByIdempotencyKey(IDEMPOTENCY_KEY))
        .thenReturn(Optional.empty())
        .thenReturn(Optional.of(priorAction("rcv_winner-1234", RUN, "succeeded", "evt_win-eeee")));
    when(workflowCommandService.takeoverWorkflow(any(TakeoverWorkflowCommand.class)))
        .thenReturn(
            new WorkflowStateChangeResult(RUN, WorkflowState.TAKEN_OVER, "corr-takeover-1"));
    when(eventReadPort.findLatestByWorkflowRunPublicId(RUN)).thenReturn(Optional.empty());
    when(eventReadPort.findLatestTransitionToState(RUN, WorkflowState.TAKEN_OVER))
        .thenReturn(Optional.of(takeoverEvent()));
    when(runnerRecordPort.findByWorkflowRunPublicIdAndStatusIn(eq(RUN), any()))
        .thenReturn(List.of());
    when(integrationLinkService.findActiveGitHubPrLink(RUN)).thenReturn(Optional.empty());
    when(runReadPort.findByPublicId(RUN)).thenReturn(Optional.of(run(WorkflowState.TAKEN_OVER)));
    when(recoveryRecordPort.insert(any(RecoveryActionWriteCommand.class)))
        .thenThrow(
            new DomainException(
                DomainErrorCode.IDEMPOTENCY_KEY_CONFLICT,
                "recovery_actions unique violation",
                Map.of("idempotencyKey", IDEMPOTENCY_KEY)));

    TakeoverResult result = service.takeoverWorkflow(command());

    assertTrue(result.replayed());
    assertEquals("rcv_winner-1234", result.recoveryActionPublicId());
    verify(recoveryRecordPort, never()).markSucceeded(any());
    verify(runnerAdapter, never()).cancel(any());
  }

  @Test
  void concurrentLoserConvergesOnReplayWhenWinnerRowStillPending() {
    // Pre-check empty; insert conflicts (sibling won); the winner's recovery_actions row is
    // committed but STILL `pending` (its markSucceeded flip has not landed yet). The loser must
    // converge on replay — the committed row proves the takeover transition + cancels are durable —
    // rather than propagate the conflict.
    when(recoveryRecordPort.findByIdempotencyKey(IDEMPOTENCY_KEY))
        .thenReturn(Optional.empty())
        .thenReturn(Optional.of(priorAction("rcv_winner-9999", RUN, "pending", "evt_win-ffff")));
    when(workflowCommandService.takeoverWorkflow(any(TakeoverWorkflowCommand.class)))
        .thenReturn(
            new WorkflowStateChangeResult(RUN, WorkflowState.TAKEN_OVER, "corr-takeover-1"));
    when(eventReadPort.findLatestByWorkflowRunPublicId(RUN)).thenReturn(Optional.empty());
    when(eventReadPort.findLatestTransitionToState(RUN, WorkflowState.TAKEN_OVER))
        .thenReturn(Optional.of(takeoverEvent()));
    when(runnerRecordPort.findByWorkflowRunPublicIdAndStatusIn(eq(RUN), any()))
        .thenReturn(List.of());
    when(integrationLinkService.findActiveGitHubPrLink(RUN)).thenReturn(Optional.empty());
    when(runReadPort.findByPublicId(RUN)).thenReturn(Optional.of(run(WorkflowState.TAKEN_OVER)));
    when(recoveryRecordPort.insert(any(RecoveryActionWriteCommand.class)))
        .thenThrow(
            new DomainException(
                DomainErrorCode.IDEMPOTENCY_KEY_CONFLICT,
                "recovery_actions unique violation",
                Map.of("idempotencyKey", IDEMPOTENCY_KEY)));

    TakeoverResult result = service.takeoverWorkflow(command());

    assertTrue(result.replayed());
    assertEquals("rcv_winner-9999", result.recoveryActionPublicId());
    // The loser must NOT re-run side effects the winner owns.
    verify(recoveryRecordPort, never()).markSucceeded(any());
    verify(runnerAdapter, never()).cancel(any());
  }

  @Test
  void concurrentLoserPropagatesConflictWhenWinnerRowIsForADifferentRun() {
    // Insert conflicts, but the committed sibling row is bound to a DIFFERENT run — a genuine
    // idempotency-key reuse conflict that must propagate, not converge.
    when(recoveryRecordPort.findByIdempotencyKey(IDEMPOTENCY_KEY))
        .thenReturn(Optional.empty())
        .thenReturn(
            Optional.of(priorAction("rcv_other-7777", "run_otherrun8888", "pending", null)));
    when(workflowCommandService.takeoverWorkflow(any(TakeoverWorkflowCommand.class)))
        .thenReturn(
            new WorkflowStateChangeResult(RUN, WorkflowState.TAKEN_OVER, "corr-takeover-1"));
    when(eventReadPort.findLatestByWorkflowRunPublicId(RUN)).thenReturn(Optional.empty());
    when(eventReadPort.findLatestTransitionToState(RUN, WorkflowState.TAKEN_OVER))
        .thenReturn(Optional.of(takeoverEvent()));
    when(runnerRecordPort.findByWorkflowRunPublicIdAndStatusIn(eq(RUN), any()))
        .thenReturn(List.of());
    when(integrationLinkService.findActiveGitHubPrLink(RUN)).thenReturn(Optional.empty());
    when(recoveryRecordPort.insert(any(RecoveryActionWriteCommand.class)))
        .thenThrow(
            new DomainException(
                DomainErrorCode.IDEMPOTENCY_KEY_CONFLICT,
                "recovery_actions unique violation",
                Map.of("idempotencyKey", IDEMPOTENCY_KEY)));

    DomainException error =
        assertThrows(DomainException.class, () -> service.takeoverWorkflow(command()));
    assertEquals(DomainErrorCode.IDEMPOTENCY_KEY_CONFLICT, error.errorCode());
    verify(recoveryRecordPort, never()).markSucceeded(any());
  }

  @Test
  void bestEffortContainerCancelFailureIsSwallowedAndTakeoverStillSucceeds() {
    stubHappyTakeover();
    org.mockito.Mockito.doThrow(new RuntimeException("docker daemon unreachable"))
        .when(runnerAdapter)
        .cancel("rex_running-3333");

    TakeoverResult result = service.takeoverWorkflow(command());

    assertFalse(result.replayed());
    verify(recoveryRecordPort).markSucceeded(IDEMPOTENCY_KEY);
    assertFalse(
        logged(Level.WARN, "developer takeover best-effort container-cancel failed").isEmpty());
  }

  @Test
  void cancellationUsesLockedPreviousStatusWhenQueuedSnapshotBecameRunning() {
    stubHappyTakeover();
    when(runnerRecordPort.findByWorkflowRunPublicIdAndStatusIn(eq(RUN), any()))
        .thenReturn(List.of(runnerRow("rex_raced-4444", RunnerExecutionStatus.QUEUED)));
    when(runnerRecordPort.markCancelledForTakeover("rex_raced-4444", FIXED_NOW))
        .thenReturn(
            Optional.of(
                new RunnerTakeoverCancellation("rex_raced-4444", RunnerExecutionStatus.RUNNING)));

    TakeoverResult result = service.takeoverWorkflow(command());

    assertEquals(1, result.cancelledInFlightCount());
    assertEquals(0, result.cancelledQueuedCount());
    verify(runnerAdapter).cancel("rex_raced-4444");
  }

  @Test
  void happyPathEmitsStartSuccessAndPerRunnerLogsWithoutLeakingReasonText() {
    stubHappyTakeover();

    service.takeoverWorkflow(command());

    List<String> start = logged(Level.INFO, "developer takeover start");
    assertFalse(start.isEmpty());
    assertTrue(start.get(0).contains("workflowRunId=" + RUN));
    assertTrue(start.get(0).contains("idempotencyKey=" + IDEMPOTENCY_KEY));
    assertTrue(start.get(0).contains("actorIdentity=alex"));

    List<String> success = logged(Level.INFO, "developer takeover success");
    assertFalse(success.isEmpty());
    assertTrue(success.get(0).contains("recoveryActionId=rcv_take-aaaaa"));
    assertTrue(success.get(0).contains("cancelledInFlightCount=2"));
    assertTrue(success.get(0).contains("cancelledQueuedCount=1"));
    assertTrue(success.get(0).contains("resultingState=TakenOver"));

    assertEquals(3, logged(Level.INFO, "developer takeover cancelled runner").size());

    // Forbidden: the reviewer reasonText must never appear in any log line.
    assertTrue(
        appender.list.stream().noneMatch(e -> e.getFormattedMessage().contains(REASON)),
        "reviewer reasonText must not be logged");
  }

  @Test
  void takeoverStampsFullAuditContextInMdcAndRestoresAfter() {
    stubHappyTakeover();

    service.takeoverWorkflow(command());

    // The success line carries the full takeover audit context in MDC (review finding): run,
    // correlation, actor identity/type, idempotency key, and the resulting recovery_actions id.
    ILoggingEvent success =
        appender.list.stream()
            .filter(e -> e.getFormattedMessage().contains("developer takeover success"))
            .findFirst()
            .orElseThrow();
    Map<String, String> mdc = success.getMDCPropertyMap();
    assertEquals(RUN, mdc.get("workflowRunId"));
    assertEquals("corr-takeover-1", mdc.get("correlationId"));
    assertEquals("alex", mdc.get("actorIdentity"));
    assertEquals("human", mdc.get("actorType"));
    assertEquals(IDEMPOTENCY_KEY, mdc.get("idempotencyKey"));
    assertEquals("rcv_take-aaaaa", mdc.get("recoveryActionId"));

    // Scopes are restored after the call — no MDC leak past the service boundary.
    assertNull(MDC.get("workflowRunId"));
    assertNull(MDC.get("correlationId"));
    assertNull(MDC.get("actorIdentity"));
    assertNull(MDC.get("actorType"));
    assertNull(MDC.get("idempotencyKey"));
    assertNull(MDC.get("recoveryActionId"));
  }

  @Test
  void markSucceededFailureAfterCommitRaisesInternalErrorPreservingTakeoverAndAuditContext() {
    // The prep tx (transition + cancel-flips + recovery_actions insert) commits; only the
    // out-of-band markSucceeded flip fails. The takeover effect is durable, but the recovery_actions
    // row is stuck `pending` — the service surfaces INTERNAL_ERROR ("operator reconciliation
    // required") rather than silently reporting success. Highest-consequence failure mode.
    stubHappyTakeover();
    RuntimeException boom = new RuntimeException("result-status db write failed");
    when(recoveryRecordPort.markSucceeded(IDEMPOTENCY_KEY)).thenThrow(boom);

    DomainException error =
        assertThrows(DomainException.class, () -> service.takeoverWorkflow(command()));

    assertEquals(DomainErrorCode.INTERNAL_ERROR, error.errorCode());
    assertEquals("result_status_flip_failed_after_takeover", error.details().get("reason"));
    assertEquals(RUN, error.details().get("runId"));
    assertEquals("rcv_take-aaaaa", error.details().get("recoveryActionId"));
    assertEquals(IDEMPOTENCY_KEY, error.details().get("idempotencyKey"));
    // The original failure is chained as a suppressed cause (not swallowed).
    assertEquals(1, error.getSuppressed().length);
    assertSame(boom, error.getSuppressed()[0]);

    // The takeover effect IS durable — the prep tx ran fully before the failing flip.
    verify(workflowCommandService, times(1)).takeoverWorkflow(any(TakeoverWorkflowCommand.class));
    verify(runnerRecordPort).markCancelledForTakeover(eq("rex_running-3333"), eq(FIXED_NOW));
    verify(recoveryRecordPort).insert(any(RecoveryActionWriteCommand.class));
    verify(recoveryRecordPort).markSucceeded(IDEMPOTENCY_KEY);

    // The degraded path is logged at ERROR with the operator-reconciliation signal, without leaking
    // the reviewer reasonText.
    List<String> errorLogs =
        logged(Level.ERROR, "developer takeover completion status update failed");
    assertFalse(errorLogs.isEmpty());
    assertTrue(errorLogs.get(0).contains("recoveryActionId=rcv_take-aaaaa"));
    assertTrue(
        appender.list.stream().noneMatch(e -> e.getFormattedMessage().contains(REASON)),
        "reviewer reasonText must not be logged");

    // MDC scopes are restored even on this throwing path — no leak past the service boundary.
    assertNull(MDC.get("workflowRunId"));
    assertNull(MDC.get("correlationId"));
    assertNull(MDC.get("actorIdentity"));
    assertNull(MDC.get("actorType"));
    assertNull(MDC.get("idempotencyKey"));
    assertNull(MDC.get("recoveryActionId"));
  }

  private void stubHappyTakeover() {
    when(recoveryRecordPort.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.empty());
    when(workflowCommandService.takeoverWorkflow(any(TakeoverWorkflowCommand.class)))
        .thenReturn(
            new WorkflowStateChangeResult(RUN, WorkflowState.TAKEN_OVER, "corr-takeover-1"));
    when(eventReadPort.findLatestByWorkflowRunPublicId(RUN)).thenReturn(Optional.of(priorEvent()));
    when(eventReadPort.findLatestTransitionToState(RUN, WorkflowState.TAKEN_OVER))
        .thenReturn(Optional.of(takeoverEvent()));
    when(runnerRecordPort.findByWorkflowRunPublicIdAndStatusIn(eq(RUN), any()))
        .thenReturn(
            List.of(
                runnerRow("rex_queued-1111", RunnerExecutionStatus.QUEUED),
                runnerRow("rex_pending-2222", RunnerExecutionStatus.PENDING),
                runnerRow("rex_running-3333", RunnerExecutionStatus.RUNNING)));
    when(runnerRecordPort.markCancelledForTakeover(any(), eq(FIXED_NOW)))
        .thenAnswer(
            invocation -> {
              String id = invocation.getArgument(0);
              RunnerExecutionStatus previous =
                  id.contains("queued")
                      ? RunnerExecutionStatus.QUEUED
                      : id.contains("pending")
                          ? RunnerExecutionStatus.PENDING
                          : RunnerExecutionStatus.RUNNING;
              return Optional.of(new RunnerTakeoverCancellation(id, previous));
            });
    when(integrationLinkService.findActiveGitHubPrLink(RUN))
        .thenReturn(Optional.of(githubPrLink()));
    when(recoveryRecordPort.insert(any(RecoveryActionWriteCommand.class)))
        .thenReturn(priorAction("rcv_take-aaaaa", RUN, "pending", "evt_takeover-bbbb"));
    when(recoveryRecordPort.markSucceeded(IDEMPOTENCY_KEY))
        .thenReturn(priorAction("rcv_take-aaaaa", RUN, "succeeded", "evt_takeover-bbbb"));
  }

  private static TakeoverWorkflowCommand command() {
    return new TakeoverWorkflowCommand(
        RUN, "alex", ActorType.HUMAN, IDEMPOTENCY_KEY, "corr-takeover-1", REASON);
  }

  private static WorkflowEventRecord priorEvent() {
    return new WorkflowEventRecord(
        "evt_trigger-aaaa",
        RUN,
        WorkflowEventType.WORKFLOW_STATE_CHANGED,
        WorkflowState.EXECUTING,
        WorkflowState.WAITING_FOR_REVIEW,
        "system",
        ActorType.SYSTEM,
        "runner completed",
        null,
        false,
        OffsetDateTime.parse("2026-06-15T11:50:00Z"),
        Map.of());
  }

  private static WorkflowEventRecord takeoverEvent() {
    return new WorkflowEventRecord(
        "evt_takeover-bbbb",
        RUN,
        WorkflowEventType.WORKFLOW_STATE_CHANGED,
        WorkflowState.WAITING_FOR_REVIEW,
        WorkflowState.TAKEN_OVER,
        "alex",
        ActorType.HUMAN,
        REASON,
        null,
        true,
        FIXED_NOW,
        Map.of());
  }

  private static RunnerExecutionSnapshot runnerRow(String publicId, RunnerExecutionStatus status) {
    return new RunnerExecutionSnapshot(
        publicId,
        RUN,
        RunnerStage.EXECUTION,
        status,
        1,
        OffsetDateTime.parse("2026-06-15T11:55:00Z"),
        OffsetDateTime.parse("2026-06-15T12:30:00Z"),
        null,
        null,
        OffsetDateTime.parse("2026-06-15T11:50:00Z"),
        null);
  }

  private static IntegrationLink githubPrLink() {
    return new IntegrationLink(
        "ilk_pr00-aaaaa",
        RUN,
        "github_pr",
        PR_REF,
        IntegrationSyncStatus.SYNCED,
        FIXED_NOW.toInstant(),
        FIXED_NOW.toInstant(),
        null);
  }

  private static WorkflowRunSnapshot run(WorkflowState state) {
    return new WorkflowRunSnapshot(RUN, state, null, 7L, 0, false);
  }

  private static RecoveryActionSnapshot priorAction(
      String publicId, String runId, String resultStatus, String resultingEventPublicId) {
    return new RecoveryActionSnapshot(
        publicId,
        1L,
        runId,
        "takeover",
        "evt_trigger-aaaa",
        resultingEventPublicId,
        "alex",
        ActorType.HUMAN,
        IDEMPOTENCY_KEY,
        resultStatus,
        FIXED_NOW,
        "developer");
  }

  private List<String> logged(Level level, String needle) {
    return appender.list.stream()
        .filter(e -> e.getLevel() == level)
        .map(ILoggingEvent::getFormattedMessage)
        .filter(m -> m.contains(needle))
        .toList();
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
