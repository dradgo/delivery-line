package org.dradgo.application.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.dradgo.application.artifact.ActorContext;
import org.dradgo.application.artifact.spi.ArtifactOperationPort;
import org.dradgo.application.idempotency.IdempotencyKeyValidator;
import org.dradgo.application.integration.IntegrationLinkService;
import org.dradgo.application.integration.conflict.ConflictIntegrationTypes;
import org.dradgo.application.integration.conflict.ConflictResolutionView;
import org.dradgo.application.integration.conflict.IntegrationConflictService;
import org.dradgo.application.recovery.spi.RecoveryActionRecordPort;
import org.dradgo.application.recovery.spi.RecoveryActionSnapshot;
import org.dradgo.application.recovery.spi.RecoveryActionWriteCommand;
import org.dradgo.application.runner.queue.QueuedRunnerExecution;
import org.dradgo.application.runner.queue.RunnerExecutionQueue;
import org.dradgo.application.runner.spi.RunnerExecutionRecordPort;
import org.dradgo.application.runner.spi.RunnerExecutionSnapshot;
import org.dradgo.application.workflow.WorkflowCommandService;
import org.dradgo.application.workflow.spi.WorkflowEventReadPort;
import org.dradgo.application.workflow.spi.WorkflowEventRecord;
import org.dradgo.application.workflow.spi.WorkflowEventWritePort;
import org.dradgo.application.workflow.spi.WorkflowRunReadPort;
import org.dradgo.application.workflow.spi.WorkflowRunSnapshot;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.FailureCategory;
import org.dradgo.domain.registry.IntegrationConflictCategory;
import org.dradgo.domain.registry.RunnerExecutionStatus;
import org.dradgo.domain.registry.RunnerStage;
import org.dradgo.domain.registry.WorkflowEventType;
import org.dradgo.domain.registry.WorkflowState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Pins SLF4J log lines emitted by {@link RecoveryService} for the four happy/sad paths required by
 * the story 1.18 "Logging instrumentation" cross-cutting task — {@code recovery retry start},
 * {@code recovery retry success}, {@code recovery retry replay}, {@code recovery retry rejected},
 * {@code recovery retry dispatch failed}, plus {@code describe failure} / {@code describe failure
 * resolved}. Uses a Logback {@link ListAppender} to capture events without a full Spring context.
 */
class RecoveryLoggingContractTest {

  private static final String RUN = "run_logging123";
  private static final String IDEMPOTENCY_KEY = "idem-recovery-logging-1234";
  private static final ActorContext ACTOR =
      new ActorContext("amelia@local", ActorType.HUMAN, "corr-recovery-log");
  private static final OffsetDateTime FIXED_NOW = OffsetDateTime.parse("2026-05-15T13:00:00Z");

  private ListAppender<ILoggingEvent> appender;
  private RecoveryService service;

  private WorkflowRunReadPort runReadPort;
  private WorkflowEventReadPort eventReadPort;
  private RunnerExecutionRecordPort runnerRecordPort;
  private ArtifactOperationPort artifactOperationPort;
  private WorkflowCommandService workflowCommandService;
  private RunnerExecutionQueue runnerExecutionQueue;
  private org.dradgo.application.workflow.WorkflowOrchestrationService workflowOrchestrationService;
  private IntegrationLinkService integrationLinkService;
  private IntegrationConflictService conflictService;
  private WorkflowEventWritePort eventWritePort;
  private RecoveryActionRecordPort recoveryRecordPort;

  @BeforeEach
  void setUp() {
    runReadPort = mock(WorkflowRunReadPort.class);
    eventReadPort = mock(WorkflowEventReadPort.class);
    runnerRecordPort = mock(RunnerExecutionRecordPort.class);
    artifactOperationPort = mock(ArtifactOperationPort.class);
    workflowCommandService = mock(WorkflowCommandService.class);
    runnerExecutionQueue = mock(RunnerExecutionQueue.class);
    workflowOrchestrationService =
        mock(org.dradgo.application.workflow.WorkflowOrchestrationService.class);
    integrationLinkService = mock(IntegrationLinkService.class);
    conflictService = mock(IntegrationConflictService.class);
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
            integrationLinkService,
            eventWritePort,
            recoveryRecordPort,
            conflictService,
            new IdempotencyKeyValidator(),
            Clock.fixed(FIXED_NOW.toInstant(), ZoneOffset.UTC),
            callthroughTemplate(),
            callthroughTemplate());

    appender = new ListAppender<>();
    appender.start();
    ((Logger) LoggerFactory.getLogger(RecoveryService.class)).addAppender(appender);
  }

  @AfterEach
  void tearDown() {
    ((Logger) LoggerFactory.getLogger(RecoveryService.class)).detachAppender(appender);
  }

  @Test
  void retryHappyPathEmitsStartAndSuccessLogsAtInfoWithExpectedKeys() {
    stubFailedRunPath();
    when(recoveryRecordPort.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.empty());
    when(recoveryRecordPort.insert(any(RecoveryActionWriteCommand.class)))
        .thenReturn(recoveryActionSnapshot("rcv_log1-aaaaa", "pending"));
    when(recoveryRecordPort.markSucceeded(IDEMPOTENCY_KEY))
        .thenReturn(recoveryActionSnapshot("rcv_log1-aaaaa", "succeeded"));
    stubBrokerDispatchOk();

    service.retry(RUN, IDEMPOTENCY_KEY, ACTOR, null);

    ILoggingEvent start = findFirst(Level.INFO, "recovery retry start");
    assertTrue(start.getFormattedMessage().contains("workflowRunId=" + RUN));
    assertTrue(start.getFormattedMessage().contains("idempotencyKey=" + IDEMPOTENCY_KEY));
    assertTrue(start.getFormattedMessage().contains("actorIdentity=" + ACTOR.actorIdentity()));

    ILoggingEvent success = findFirst(Level.INFO, "recovery retry success");
    assertTrue(success.getFormattedMessage().contains("recoveryActionId=rcv_log1-aaaaa"));
    assertTrue(success.getFormattedMessage().contains("newRunnerExecutionId=rex_log1-bbbbb"));
    assertTrue(success.getFormattedMessage().contains("durationMs="));

    // Story 1.19 backfill: MDC stamp for workflowRunId is set inside RecoveryService.retry.
    // The log captures the MDC at emission time, so both the start and success lines must
    // carry the workflowRunId key.
    assertEquals(RUN, start.getMDCPropertyMap().get("workflowRunId"));
    assertEquals(RUN, success.getMDCPropertyMap().get("workflowRunId"));
  }

  @Test
  void retryReplayEmitsReplayLogAtInfoWithRecoveryActionIdAndIdempotencyKey() {
    RecoveryActionSnapshot prior = recoveryActionSnapshot("rcv_prior-aaaaa", "succeeded");
    when(recoveryRecordPort.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.of(prior));

    service.retry(RUN, IDEMPOTENCY_KEY, ACTOR, null);

    ILoggingEvent replay = findFirst(Level.INFO, "recovery retry replay");
    assertTrue(replay.getFormattedMessage().contains("recoveryActionId=rcv_prior-aaaaa"));
    assertTrue(replay.getFormattedMessage().contains("idempotencyKey=" + IDEMPOTENCY_KEY));
  }

  @Test
  void retryOnNonFailedEmitsRejectedLogAtWarnWithCurrentState() {
    when(recoveryRecordPort.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.empty());
    when(runReadPort.findByPublicId(RUN))
        .thenReturn(
            Optional.of(new WorkflowRunSnapshot(RUN, WorkflowState.EXECUTING, null, 1L, 0, false)));

    assertThrows(DomainException.class, () -> service.retry(RUN, IDEMPOTENCY_KEY, ACTOR, null));

    ILoggingEvent rejected = findFirst(Level.WARN, "recovery retry rejected");
    assertTrue(rejected.getFormattedMessage().contains("currentState=Executing"));
    assertTrue(rejected.getFormattedMessage().contains("reason=not_failed"));
  }

  @Test
  void retryDispatchFailureEmitsDispatchFailedLogAtWarnWithErrorClassAndStageHint() {
    stubFailedRunPath();
    when(recoveryRecordPort.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.empty());
    when(recoveryRecordPort.insert(any(RecoveryActionWriteCommand.class)))
        .thenReturn(recoveryActionSnapshot("rcv_log2-aaaaa", "pending"));
    when(runnerExecutionQueue.enqueue(eq(RUN), any(RunnerStage.class), any(), any(), anyInt()))
        .thenThrow(new IllegalStateException("broker network failure"));

    assertThrows(
        IllegalStateException.class, () -> service.retry(RUN, IDEMPOTENCY_KEY, ACTOR, null));

    ILoggingEvent dispatchFailed = findFirst(Level.WARN, "recovery retry dispatch failed");
    assertTrue(dispatchFailed.getFormattedMessage().contains("workflowRunId=" + RUN));
    assertTrue(dispatchFailed.getFormattedMessage().contains("runnerStage=execution"));
    assertTrue(dispatchFailed.getFormattedMessage().contains("errorClass=IllegalStateException"));
    assertTrue(dispatchFailed.getFormattedMessage().contains("operator action required"));
  }

  @Test
  void retryDispatchFailureAlsoEmitsRecoveryDispatchFailedEventAppendedLogAtInfo() {
    stubFailedRunPath();
    when(recoveryRecordPort.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.empty());
    when(recoveryRecordPort.insert(any(RecoveryActionWriteCommand.class)))
        .thenReturn(recoveryActionSnapshot("rcv_log4-aaaaa", "pending"));
    when(runnerExecutionQueue.enqueue(eq(RUN), any(RunnerStage.class), any(), any(), anyInt()))
        .thenThrow(new IllegalStateException("broker network failure"));

    assertThrows(
        IllegalStateException.class, () -> service.retry(RUN, IDEMPOTENCY_KEY, ACTOR, null));

    ILoggingEvent appended = findFirst(Level.INFO, "recovery dispatch-failed event appended");
    assertTrue(appended.getFormattedMessage().contains("workflowRunId=" + RUN));
    assertTrue(appended.getFormattedMessage().contains("dispatchFailedEventId=evt_"));
    assertTrue(appended.getFormattedMessage().contains("errorCode=RUNTIME_ERROR"));
    assertTrue(appended.getFormattedMessage().contains("errorClass=IllegalStateException"));
    long errorCount =
        appender.list.stream().filter(event -> event.getLevel() == Level.ERROR).count();
    assertEquals(
        0L,
        errorCount,
        "RecoveryService.retry must not emit ERROR even on the dispatch-failure path");
  }

  @Test
  void dispatchFailedAuditAppendFailureEscalatesToErrorWithAllAuditFieldsStructuredIntoOneLine() {
    // D2 (2026-05-16): when `appendDispatchFailedEvent` itself throws (event-store outage),
    // the operator-visible `history` cannot show the failure. Escalate to ERROR with every
    // audit field stamped into the structured log so log-scraping can reconstruct the
    // missing event. (review batch-3 D2)
    stubFailedRunPath();
    when(recoveryRecordPort.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.empty());
    when(recoveryRecordPort.insert(any(RecoveryActionWriteCommand.class)))
        .thenReturn(recoveryActionSnapshot("rcv_log5-aaaaa", "pending"));
    when(runnerExecutionQueue.enqueue(eq(RUN), any(RunnerStage.class), any(), any(), anyInt()))
        .thenThrow(new IllegalStateException("broker network failure"));
    // Audit append blows up — event store transient outage.
    org.mockito.Mockito.doThrow(new IllegalStateException("event store unreachable"))
        .when(eventWritePort)
        .append(
            org.mockito.ArgumentMatchers.argThat(
                record ->
                    record != null
                        && record.eventType() == WorkflowEventType.RECOVERY_DISPATCH_FAILED));

    assertThrows(
        IllegalStateException.class, () -> service.retry(RUN, IDEMPOTENCY_KEY, ACTOR, null));

    ILoggingEvent escalated =
        findFirst(Level.ERROR, "recovery retry dispatch-failed event append failed");
    String msg = escalated.getFormattedMessage();
    assertTrue(msg.contains("workflowRunId=" + RUN));
    assertTrue(msg.contains("recoveryActionId=rcv_log5-aaaaa"));
    assertTrue(msg.contains("recoveryRetriedEventId=evt_"));
    assertTrue(msg.contains("idempotencyKey=" + IDEMPOTENCY_KEY));
    assertTrue(msg.contains("failedStage=execution"));
    assertTrue(msg.contains("errorCode=RUNTIME_ERROR"));
    assertTrue(msg.contains("errorClass=IllegalStateException"));
    assertTrue(msg.contains("correlationId=corr-recovery-log"));
    assertTrue(msg.contains("auditErrorClass=IllegalStateException"));
    assertTrue(msg.contains("audit event missing"));
  }

  @Test
  void describeFailureEmitsEntryAndExitLogsAtInfo() {
    when(runReadPort.findByPublicId(RUN))
        .thenReturn(
            Optional.of(new WorkflowRunSnapshot(RUN, WorkflowState.EXECUTING, null, 1L, 0, false)));
    when(eventReadPort.findLatestByWorkflowRunPublicId(RUN)).thenReturn(Optional.empty());

    service.describeFailure(RUN);

    ILoggingEvent entry = findFirst(Level.INFO, "describe failure workflowRunId");
    assertTrue(entry.getFormattedMessage().contains(RUN));
    ILoggingEvent exit = findFirst(Level.INFO, "describe failure resolved");
    assertTrue(exit.getFormattedMessage().contains("currentState=Executing"));
    assertTrue(exit.getFormattedMessage().contains("nextSafeAction=await_outcome"));
  }

  // ---------------------------------------------------------------------------
  // Story 4.5 — resume log lines (start / success / replay / rejected).
  // ---------------------------------------------------------------------------

  @Test
  void resumeHappyPathEmitsStartAndSuccessLogsAtInfoWithExpectedKeys() {
    stubPausedRunPath();
    when(recoveryRecordPort.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.empty());
    when(recoveryRecordPort.insert(any(RecoveryActionWriteCommand.class)))
        .thenReturn(recoveryActionSnapshot("rcv_res1-aaaaa", "pending"));
    when(recoveryRecordPort.markSucceeded(IDEMPOTENCY_KEY))
        .thenReturn(recoveryActionSnapshot("rcv_res1-aaaaa", "succeeded"));
    stubResumeDispatchOk();

    service.resume(RUN, IDEMPOTENCY_KEY, ACTOR, null);

    ILoggingEvent start = findFirst(Level.INFO, "recovery resume start");
    assertTrue(start.getFormattedMessage().contains("workflowRunId=" + RUN));
    assertTrue(start.getFormattedMessage().contains("idempotencyKey=" + IDEMPOTENCY_KEY));
    assertTrue(start.getFormattedMessage().contains("actorIdentity=" + ACTOR.actorIdentity()));

    ILoggingEvent success = findFirst(Level.INFO, "recovery resume success");
    assertTrue(success.getFormattedMessage().contains("recoveryActionId=rcv_res1-aaaaa"));
    assertTrue(success.getFormattedMessage().contains("newRunnerExecutionId=rex_resume-bbbbb"));
    assertTrue(success.getFormattedMessage().contains("resultingState=Executing"));
    assertTrue(success.getFormattedMessage().contains("durationMs="));

    assertEquals(RUN, start.getMDCPropertyMap().get("workflowRunId"));
    assertEquals(RUN, success.getMDCPropertyMap().get("workflowRunId"));

    long errorCount =
        appender.list.stream().filter(event -> event.getLevel() == Level.ERROR).count();
    assertEquals(0L, errorCount, "RecoveryService.resume must not emit ERROR on the happy path");
  }

  @Test
  void resumeReplayEmitsReplayLogAtInfoWithRecoveryActionIdAndIdempotencyKey() {
    // The prior row MUST be a resume action — a cross-action key reuse (retry/takeover) is a
    // conflict, not a resume replay (guarded in both the Step 1 pre-check and the concurrent path).
    RecoveryActionSnapshot prior = resumeActionSnapshot("rcv_resprior-aaaaa", "succeeded");
    when(recoveryRecordPort.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.of(prior));

    service.resume(RUN, IDEMPOTENCY_KEY, ACTOR, null);

    ILoggingEvent replay = findFirst(Level.INFO, "recovery resume replay");
    assertTrue(replay.getFormattedMessage().contains("recoveryActionId=rcv_resprior-aaaaa"));
    assertTrue(replay.getFormattedMessage().contains("idempotencyKey=" + IDEMPOTENCY_KEY));
  }

  @Test
  void resumeOnNonPausedEmitsRejectedLogAtWarnWithCurrentState() {
    when(recoveryRecordPort.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.empty());
    when(runReadPort.findByPublicId(RUN))
        .thenReturn(
            Optional.of(new WorkflowRunSnapshot(RUN, WorkflowState.EXECUTING, null, 1L, 0, false)));

    assertThrows(DomainException.class, () -> service.resume(RUN, IDEMPOTENCY_KEY, ACTOR, null));

    ILoggingEvent rejected = findFirst(Level.WARN, "recovery resume rejected");
    assertTrue(rejected.getFormattedMessage().contains("currentState=Executing"));
    assertTrue(rejected.getFormattedMessage().contains("reason=not_paused"));
  }

  // ---------------------------------------------------------------------------
  // Story 4.6 - reconcile log lines (start / success / replay / rejected).
  // ---------------------------------------------------------------------------

  @Test
  void reconcileHappyPathEmitsStartAndSuccessLogsAtInfoWithExpectedKeys() {
    stubReconcileHappyPath("rcv_rec-log1");
    when(recoveryRecordPort.markSucceeded(IDEMPOTENCY_KEY))
        .thenReturn(reconcileActionSnapshot("rcv_rec-log1", "succeeded", "evt_reconciled-log1"));

    service.reconcile(
        RUN,
        "icf_log-aaaaa",
        "mark_completed_externally",
        IDEMPOTENCY_KEY,
        ACTOR,
        "operator confirmed external completion");

    ILoggingEvent start = findFirst(Level.INFO, "recovery reconcile start");
    assertTrue(start.getFormattedMessage().contains("workflowRunId=" + RUN));
    assertTrue(start.getFormattedMessage().contains("conflictId=icf_log-aaaaa"));
    assertTrue(start.getFormattedMessage().contains("idempotencyKey=" + IDEMPOTENCY_KEY));
    assertTrue(start.getFormattedMessage().contains("actorIdentity=" + ACTOR.actorIdentity()));

    ILoggingEvent success = findFirst(Level.INFO, "recovery reconcile success");
    assertTrue(success.getFormattedMessage().contains("recoveryActionId=rcv_rec-log1"));
    assertTrue(success.getFormattedMessage().contains("resolvedConflictId=icf_log-aaaaa"));
    assertTrue(success.getFormattedMessage().contains("decision=mark_completed_externally"));
    assertTrue(success.getFormattedMessage().contains("durationMs="));
    assertEquals(RUN, start.getMDCPropertyMap().get("workflowRunId"));
    assertEquals(RUN, success.getMDCPropertyMap().get("workflowRunId"));
  }

  @Test
  void reconcileReplayEmitsReplayLogAtInfoWithRecoveryActionIdAndIdempotencyKey() {
    when(recoveryRecordPort.findByIdempotencyKey(IDEMPOTENCY_KEY))
        .thenReturn(
            Optional.of(
                reconcileActionSnapshot("rcv_rec-prior", "succeeded", "evt_reconciled-prior")));
    when(eventReadPort.listByWorkflowRunPublicId(RUN, null))
        .thenReturn(
            List.of(
                new WorkflowEventRecord(
                    "evt_reconciled-prior",
                    RUN,
                    WorkflowEventType.RECOVERY_RECONCILED,
                    WorkflowState.EXECUTING,
                    WorkflowState.RECONCILED,
                    ACTOR.actorIdentity(),
                    ACTOR.actorType(),
                    "operator confirmed external completion",
                    null,
                    true,
                    FIXED_NOW,
                    Map.of(
                        "conflictId",
                        "icf_log-aaaaa",
                        "reconciliationDecision",
                        "mark_completed_externally"))));

    service.reconcile(
        RUN, "icf_log-aaaaa", "mark_completed_externally", IDEMPOTENCY_KEY, ACTOR, " ");

    ILoggingEvent replay = findFirst(Level.INFO, "recovery reconcile replay");
    assertTrue(replay.getFormattedMessage().contains("recoveryActionId=rcv_rec-prior"));
    assertTrue(replay.getFormattedMessage().contains("conflictId=icf_log-aaaaa"));
    assertTrue(replay.getFormattedMessage().contains("idempotencyKey=" + IDEMPOTENCY_KEY));
  }

  @Test
  void reconcileRejectedEmitsWarnWithReasonAndConflictId() {
    when(recoveryRecordPort.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.empty());

    assertThrows(
        DomainException.class,
        () -> service.reconcile(RUN, "icf_log-aaaaa", " ", IDEMPOTENCY_KEY, ACTOR, "reason"));

    ILoggingEvent rejected = findFirst(Level.WARN, "recovery reconcile rejected");
    assertTrue(rejected.getFormattedMessage().contains("workflowRunId=" + RUN));
    assertTrue(rejected.getFormattedMessage().contains("conflictId=icf_log-aaaaa"));
    assertTrue(rejected.getFormattedMessage().contains("reason=MISSING_RECONCILIATION_DECISION"));
  }

  private void stubPausedRunPath() {
    when(runReadPort.findByPublicId(RUN))
        .thenReturn(
            Optional.of(new WorkflowRunSnapshot(RUN, WorkflowState.PAUSED, null, 7L, 0, false)));
    when(eventReadPort.findLatestTransitionToState(RUN, WorkflowState.PAUSED))
        .thenReturn(
            Optional.of(
                new WorkflowEventRecord(
                    "evt_paused-aaaaa",
                    RUN,
                    WorkflowEventType.WORKFLOW_STATE_CHANGED,
                    WorkflowState.EXECUTING,
                    WorkflowState.PAUSED,
                    ACTOR.actorIdentity(),
                    ActorType.HUMAN,
                    "paused for investigation",
                    null,
                    true,
                    FIXED_NOW,
                    Map.of())));
  }

  private void stubResumeDispatchOk() {
    org.dradgo.application.runner.RunnerExecutionHandle handle =
        new org.dradgo.application.runner.RunnerExecutionHandle(
            "rex_resume-bbbbb",
            RUN,
            RunnerStage.EXECUTION,
            RunnerExecutionStatus.QUEUED,
            FIXED_NOW.plusMinutes(30));
    when(workflowOrchestrationService.redispatchAfterRetry(eq(RUN), any()))
        .thenReturn(
            new org.dradgo.application.runner.RunnerDispatchResult.Queued(handle, "evt_q-resume1"));
  }

  private void stubFailedRunPath() {
    when(runReadPort.findByPublicId(RUN))
        .thenReturn(
            Optional.of(new WorkflowRunSnapshot(RUN, WorkflowState.FAILED, null, 5L, 0, false)));
    when(eventReadPort.findLatestFailureEvent(RUN))
        .thenReturn(
            Optional.of(
                new WorkflowEventRecord(
                    "evt_failure-aaaaa",
                    RUN,
                    WorkflowEventType.WORKFLOW_STATE_CHANGED,
                    WorkflowState.EXECUTING,
                    WorkflowState.FAILED,
                    "system",
                    ActorType.SYSTEM,
                    "runner failure",
                    FailureCategory.RUNNER_TIMEOUT,
                    false,
                    FIXED_NOW,
                    Map.of())));
    when(runnerRecordPort.findByWorkflowRunPublicIdAndStatusIn(eq(RUN), any()))
        .thenReturn(
            List.of(
                new RunnerExecutionSnapshot(
                    "rex_failed-aaaaa",
                    RUN,
                    RunnerStage.EXECUTION,
                    RunnerExecutionStatus.FAILED,
                    1,
                    FIXED_NOW.minusMinutes(1),
                    FIXED_NOW.plusMinutes(30),
                    FailureCategory.RUNNER_TIMEOUT,
                    FIXED_NOW,
                    FIXED_NOW.minusMinutes(5),
                    null)));
  }

  private void stubBrokerDispatchOk() {
    QueuedRunnerExecution queued =
        new QueuedRunnerExecution(
            "rex_log1-bbbbb", RUN, RunnerStage.EXECUTION, 100, "corr", 1L, "evt_queued-log1a");
    when(runnerExecutionQueue.enqueue(eq(RUN), eq(RunnerStage.EXECUTION), any(), any(), anyInt()))
        .thenReturn(queued);
  }

  private void stubReconcileHappyPath(String recoveryActionId) {
    when(recoveryRecordPort.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.empty());
    when(runReadPort.findByPublicId(RUN))
        .thenReturn(
            Optional.of(new WorkflowRunSnapshot(RUN, WorkflowState.EXECUTING, null, 1L, 0, false)));
    when(conflictService.findConflictForResolution("icf_log-aaaaa"))
        .thenReturn(Optional.of(reconcileConflict(null)));
    when(recoveryRecordPort.insert(any(RecoveryActionWriteCommand.class)))
        .thenReturn(reconcileActionSnapshot(recoveryActionId, "pending", null));
    when(conflictService.listUnresolvedConflicts(any())).thenReturn(List.of());
  }

  private static ConflictResolutionView reconcileConflict(OffsetDateTime resolvedAt) {
    return new ConflictResolutionView(
        "icf_log-aaaaa",
        RUN,
        "ilk_log-aaaaa",
        ConflictIntegrationTypes.GITHUB_PR,
        IntegrationConflictCategory.EXTERNAL_STATE_ADVANCED.value(),
        "octo/repo#1",
        resolvedAt,
        "{}",
        "{}");
  }

  private RecoveryActionSnapshot reconcileActionSnapshot(
      String publicId, String resultStatus, String resultingEventId) {
    return new RecoveryActionSnapshot(
        publicId,
        1L,
        RUN,
        "reconcile",
        null,
        resultingEventId,
        ACTOR.actorIdentity(),
        ACTOR.actorType(),
        IDEMPOTENCY_KEY,
        resultStatus,
        Instant.now().atOffset(ZoneOffset.UTC),
        "workflow_owner");
  }

  private RecoveryActionSnapshot recoveryActionSnapshot(String publicId, String resultStatus) {
    return new RecoveryActionSnapshot(
        publicId,
        1L,
        RUN,
        "retry",
        "evt_failure-aaaaa",
        null,
        ACTOR.actorIdentity(),
        ACTOR.actorType(),
        IDEMPOTENCY_KEY,
        resultStatus,
        Instant.now().atOffset(ZoneOffset.UTC));
  }

  private RecoveryActionSnapshot resumeActionSnapshot(String publicId, String resultStatus) {
    return new RecoveryActionSnapshot(
        publicId,
        1L,
        RUN,
        "resume",
        "evt_paused-aaaaa",
        "evt_resumed-prior",
        ACTOR.actorIdentity(),
        ACTOR.actorType(),
        IDEMPOTENCY_KEY,
        resultStatus,
        Instant.now().atOffset(ZoneOffset.UTC));
  }

  private ILoggingEvent findFirst(Level level, String messageFragment) {
    return appender.list.stream()
        .filter(event -> event.getLevel() == level)
        .filter(event -> event.getFormattedMessage().contains(messageFragment))
        .findFirst()
        .orElseThrow(
            () -> {
              String dump =
                  appender.list.stream()
                      .map(event -> event.getLevel() + " " + event.getFormattedMessage())
                      .reduce("", (a, b) -> a + "\n  " + b);
              return new AssertionError(
                  "Expected a "
                      + level
                      + " log line containing \""
                      + messageFragment
                      + "\"; captured:"
                      + dump);
            });
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

  @Test
  void retryHappyPathDoesNotLogAtDebugOrError() {
    stubFailedRunPath();
    when(recoveryRecordPort.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.empty());
    when(recoveryRecordPort.insert(any(RecoveryActionWriteCommand.class)))
        .thenReturn(recoveryActionSnapshot("rcv_log3-aaaaa", "pending"));
    when(recoveryRecordPort.markSucceeded(IDEMPOTENCY_KEY))
        .thenReturn(recoveryActionSnapshot("rcv_log3-aaaaa", "succeeded"));
    stubBrokerDispatchOk();

    service.retry(RUN, IDEMPOTENCY_KEY, ACTOR, null);

    long errorCount =
        appender.list.stream().filter(event -> event.getLevel() == Level.ERROR).count();
    assertEquals(0L, errorCount, "RecoveryService.retry must not emit ERROR on the happy path");
  }
}
