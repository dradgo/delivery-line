package org.dradgo.application.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
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
import org.dradgo.application.recovery.spi.RecoveryActionRecordPort;
import org.dradgo.application.recovery.spi.RecoveryActionSnapshot;
import org.dradgo.application.recovery.spi.RecoveryActionWriteCommand;
import org.dradgo.application.runner.RunnerBroker;
import org.dradgo.application.runner.RunnerDispatchResult;
import org.dradgo.application.runner.RunnerExecutionHandle;
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
  private RunnerBroker runnerBroker;
  private WorkflowEventWritePort eventWritePort;
  private RecoveryActionRecordPort recoveryRecordPort;

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
        .thenReturn(Optional.of(new WorkflowRunSnapshot(RUN, WorkflowState.EXECUTING, null, 1L)));

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
    when(runnerBroker.dispatch(eq(RUN), any(RunnerStage.class), any(), any()))
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
    when(runnerBroker.dispatch(eq(RUN), any(RunnerStage.class), any(), any()))
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
    when(runnerBroker.dispatch(eq(RUN), any(RunnerStage.class), any(), any()))
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
        .thenReturn(Optional.of(new WorkflowRunSnapshot(RUN, WorkflowState.EXECUTING, null, 1L)));
    when(eventReadPort.findLatestByWorkflowRunPublicId(RUN)).thenReturn(Optional.empty());

    service.describeFailure(RUN);

    ILoggingEvent entry = findFirst(Level.INFO, "describe failure workflowRunId");
    assertTrue(entry.getFormattedMessage().contains(RUN));
    ILoggingEvent exit = findFirst(Level.INFO, "describe failure resolved");
    assertTrue(exit.getFormattedMessage().contains("currentState=Executing"));
    assertTrue(exit.getFormattedMessage().contains("nextSafeAction=await_outcome"));
  }

  private void stubFailedRunPath() {
    when(runReadPort.findByPublicId(RUN))
        .thenReturn(Optional.of(new WorkflowRunSnapshot(RUN, WorkflowState.FAILED, null, 5L)));
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
    RunnerExecutionHandle handle =
        new RunnerExecutionHandle(
            "rex_log1-bbbbb",
            RUN,
            RunnerStage.EXECUTION,
            RunnerExecutionStatus.PENDING,
            FIXED_NOW.plusMinutes(30));
    when(runnerBroker.dispatch(eq(RUN), eq(RunnerStage.EXECUTION), any(), any()))
        .thenReturn(new RunnerDispatchResult.Replayed(handle));
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
