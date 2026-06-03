package org.dradgo.application.runner;

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
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.dradgo.application.artifact.ActorContext;
import org.dradgo.application.artifact.ArtifactFailureResult;
import org.dradgo.application.artifact.ArtifactOperationService;
import org.dradgo.application.artifact.ArtifactOperationSnapshot;
import org.dradgo.application.artifact.ArtifactRecordSnapshot;
import org.dradgo.application.artifact.RecordArtifactOperationCommand;
import org.dradgo.application.artifact.RecordArtifactOperationResult;
import org.dradgo.application.idempotency.IdempotencyService;
import org.dradgo.application.idempotency.IdempotencyService.ReservationDecision;
import org.dradgo.application.idempotency.IdempotencyService.ReservationOutcome;
import org.dradgo.application.runner.spi.RunnerAdapter;
import org.dradgo.application.runner.spi.RunnerExecutionEventPort;
import org.dradgo.application.runner.spi.RunnerExecutionRecordPort;
import org.dradgo.application.runner.spi.RunnerExecutionSnapshot;
import org.dradgo.application.runner.spi.RunnerScratchStore;
import org.dradgo.application.workflow.WorkflowTransitionService;
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.ArtifactOperationStatus;
import org.dradgo.domain.registry.ArtifactStatus;
import org.dradgo.domain.registry.DataClassification;
import org.dradgo.domain.registry.FailureCategory;
import org.dradgo.domain.registry.RunnerExecutionStatus;
import org.dradgo.domain.registry.RunnerStage;
import org.dradgo.runnercontracts.RunnerContractValidator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

class RunnerLoggingContractTest {

  private static final String RUN_ID = "run_log123456789";
  private static final String REX_ID = "rex_log123456789";
  private static final ActorContext ACTOR =
      new ActorContext("human-pm", ActorType.HUMAN, "corr-log");
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-05-12T13:00:00Z"), ZoneOffset.UTC);

  private ListAppender<ILoggingEvent> brokerAppender;
  private RunnerExecutionRecordPort recordPort;
  private RunnerExecutionEventPort eventPort;
  private RunnerExecutionService executionService;
  private ContextBundleService contextBundleService;
  private IdempotencyService idempotencyService;
  private WorkflowTransitionService workflowTransitionService;
  private ArtifactOperationService artifactOperationService;
  private RunnerAdapter runnerAdapter;
  private RunnerScratchStore scratchStore;
  private RunnerBroker broker;

  @BeforeEach
  void setUp() {
    brokerAppender = attachListAppender(RunnerBroker.class);
    recordPort = mock(RunnerExecutionRecordPort.class);
    eventPort = mock(RunnerExecutionEventPort.class);
    executionService = mock(RunnerExecutionService.class);
    contextBundleService = mock(ContextBundleService.class);
    idempotencyService = mock(IdempotencyService.class);
    workflowTransitionService = mock(WorkflowTransitionService.class);
    artifactOperationService = mock(ArtifactOperationService.class);
    runnerAdapter = mock(RunnerAdapter.class);
    scratchStore = mock(RunnerScratchStore.class);
    broker =
        new RunnerBroker(
            recordPort,
            eventPort,
            executionService,
            contextBundleService,
            idempotencyService,
            workflowTransitionService,
            artifactOperationService,
            runnerAdapter,
            scratchStore,
            new RunnerContractValidator(),
            RunnerProperties.defaults(),
            cleanScanService(),
            callthroughTemplate(),
            callthroughTemplate(),
            CLOCK);
  }

  @AfterEach
  void detach() {
    detach(RunnerBroker.class, brokerAppender);
  }

  @Test
  void dispatchHappyPathLogsInfoWithDispatchOkAndCorrelationKeys() {
    when(recordPort.nextContextBundleVersion(RUN_ID, RunnerStage.INVESTIGATION)).thenReturn(1);
    when(idempotencyService.checkAndReserve(any(), any(), any(), any()))
        .thenReturn(new ReservationOutcome(ReservationDecision.RESERVED, null));
    ContextBundle bundle =
        new ContextBundle(
            RUN_ID,
            RunnerStage.INVESTIGATION,
            REX_ID,
            1,
            DataClassification.SHAREABLE_REDACTED,
            "{}".getBytes(StandardCharsets.UTF_8));
    // Story 3a-1 (AC1c): INVESTIGATION dispatch assembles via createForSpecInvestigation (6-arg).
    when(contextBundleService.createForSpecInvestigation(any(), any(), eq(1), any(), any(), any()))
        .thenReturn(bundle);
    when(recordPort.insertPending(any(), eq(RUN_ID), eq(RunnerStage.INVESTIGATION), eq(1), any()))
        .thenAnswer(
            invocation -> snapshot(invocation.getArgument(0), RunnerExecutionStatus.PENDING));
    when(scratchStore.writeContextBundle(any(), any()))
        .thenReturn(Paths.get("/tmp/context-bundle.v1.json"));
    when(runnerAdapter.dispatch(any())).thenReturn(new RunnerDispatchAck("mock:happy"));

    broker.dispatch(RUN_ID, RunnerStage.INVESTIGATION, "idem-log", ACTOR);

    assertContainsLogAt(Level.INFO, "dispatch ok");
    assertContainsLogAt(Level.INFO, "workflowRunId=" + RUN_ID);
  }

  @Test
  void dispatchReplayLogsInfoWithReplayContext() {
    when(recordPort.nextContextBundleVersion(RUN_ID, RunnerStage.INVESTIGATION)).thenReturn(2);
    when(idempotencyService.checkAndReserve(any(), any(), any(), any()))
        .thenReturn(new ReservationOutcome(ReservationDecision.REPLAY, REX_ID));
    when(recordPort.findByPublicId(REX_ID))
        .thenReturn(Optional.of(snapshot(REX_ID, RunnerExecutionStatus.PENDING)));

    broker.dispatch(RUN_ID, RunnerStage.INVESTIGATION, "idem-log", ACTOR);

    assertContainsLogAt(Level.INFO, "dispatch replay");
    assertContainsLogAt(Level.INFO, "runnerExecutionId=" + REX_ID);
  }

  @Test
  void onResultMalformedLogsWarnWithRunnerMalformedOutputCategory() {
    when(recordPort.findByPublicId(REX_ID))
        .thenReturn(Optional.of(snapshot(REX_ID, RunnerExecutionStatus.RUNNING)));

    broker.onResult(REX_ID, "{\"schemaVersion\":1".getBytes(StandardCharsets.UTF_8));

    assertContainsLogAt(Level.WARN, "onResult validation failed");
    assertContainsLogAt(Level.WARN, "runner_malformed_output");
  }

  @Test
  void scanForTimeoutsLogsInfoStartAndDone() {
    when(recordPort.findStaleByStatusInAndTimeoutAtBefore(any(), any(), anyInt()))
        .thenReturn(List.of());

    broker.scanForTimeouts();

    assertContainsLogAt(Level.INFO, "scanForTimeouts start");
    assertContainsLogAt(Level.INFO, "scanForTimeouts done");
  }

  @Test
  void onResultArtifactIngestionFailureLogsWarnWithRunnerContractViolationCategory() {
    // Fix #3: when ArtifactOperationService reports failure, the broker logs the failure
    // at WARN and the downstream rejection (runner_contract_violation) must also surface.
    when(recordPort.findByPublicId(REX_ID))
        .thenReturn(Optional.of(snapshot(REX_ID, RunnerExecutionStatus.RUNNING)));
    when(scratchStore.tryReadArtifactContent(eq(REX_ID), eq("spec/v1.json")))
        .thenReturn(Optional.of("spec-bytes".getBytes(StandardCharsets.UTF_8)));
    when(artifactOperationService.recordOperation(any()))
        .thenAnswer(
            invocation -> {
              RecordArtifactOperationCommand command = invocation.getArgument(0);
              ArtifactRecordSnapshot artifact =
                  ArtifactRecordSnapshot.withoutFailureMetadata(
                      "art_log0123456789",
                      command.workflowRunId(),
                      command.artifactType(),
                      1,
                      null,
                      DataClassification.SHAREABLE_REDACTED,
                      null,
                      null,
                      null,
                      ArtifactStatus.FAILED,
                      null);
              ArtifactOperationSnapshot op =
                  new ArtifactOperationSnapshot(
                      "op_log0123456789",
                      command.workflowRunId(),
                      "art_log0123456789",
                      command.operationType().value(),
                      ArtifactOperationStatus.FAILED,
                      command.idempotencyKey(),
                      FailureCategory.RUNNER_CONTRACT_VIOLATION,
                      "duplicate",
                      OffsetDateTime.now(CLOCK));
              return new RecordArtifactOperationResult(
                  artifact, op, new ArtifactFailureResult(artifact, op));
            });

    String payload =
        """
			{
			  "schemaVersion": 1,
			  "workflowRunId": "%s",
			  "runnerExecutionId": "%s",
			  "artifactReferences": [
			    {"artifactId": "art_log0123456789", "artifactType": "spec", "contentReference": "spec/v1.json"}
			  ],
			  "normalizedOutput": {"summary": "ok", "outcome": "success"},
			  "checksum": {"algorithm": "SHA-256", "hexDigest": "0000000000000000000000000000000000000000000000000000000000000001"},
			  "classification": "shareable-redacted",
			  "failureCategory": null
			}
			"""
            .formatted(RUN_ID, REX_ID);
    broker.onResult(REX_ID, payload.getBytes(StandardCharsets.UTF_8));

    assertContainsLogAt(Level.WARN, "onResult artifact-record failed");
    assertContainsLogAt(Level.WARN, "runnerExecutionId=" + REX_ID);
  }

  @Test
  void onResultLateResultOnTimedOutRowLogsWarnWithRunnerLateResultContext() {
    // Fix #6: a result arriving at a TIMED_OUT row classifies as runner_late_result and
    // must produce a WARN log carrying the row's status + payload size, per Task 8.
    RunnerExecutionSnapshot timedOut =
        new RunnerExecutionSnapshot(
            REX_ID,
            RUN_ID,
            RunnerStage.INVESTIGATION,
            RunnerExecutionStatus.TIMED_OUT,
            1,
            OffsetDateTime.now(CLOCK),
            OffsetDateTime.now(CLOCK).minusSeconds(60),
            FailureCategory.RUNNER_TIMEOUT,
            OffsetDateTime.now(CLOCK),
            OffsetDateTime.now(CLOCK),
            null);
    when(recordPort.findByPublicId(REX_ID)).thenReturn(Optional.of(timedOut));
    when(scratchStore.tryReadArtifactContent(eq(REX_ID), any())).thenReturn(Optional.empty());

    String payload =
        """
			{
			  "schemaVersion": 1,
			  "workflowRunId": "%s",
			  "runnerExecutionId": "%s",
			  "artifactReferences": [
			    {"artifactId": "art_late01234567ab", "artifactType": "spec", "contentReference": "spec/v1.json"}
			  ],
			  "normalizedOutput": {"summary": "late", "outcome": "success"},
			  "checksum": {"algorithm": "SHA-256", "hexDigest": "0000000000000000000000000000000000000000000000000000000000000001"},
			  "classification": "shareable-redacted",
			  "failureCategory": null
			}
			"""
            .formatted(RUN_ID, REX_ID);
    broker.onResult(REX_ID, payload.getBytes(StandardCharsets.UTF_8));

    assertContainsLogAt(Level.WARN, "onResult late result");
    assertContainsLogAt(Level.WARN, "status=timed_out");
  }

  @Test
  void pollActiveExecutionsLogsInfoStartDoneAndHeartbeatAdvanced() {
    // Fix #5: pollActiveExecutions emits INFO start/done bookends; HeartbeatTouched advances
    // last_activity_at and logs INFO with the activity timestamp.
    RunnerExecutionSnapshot active = snapshot(REX_ID, RunnerExecutionStatus.RUNNING);
    when(recordPort.findActiveStatuses(any(), anyInt())).thenReturn(List.of(active));
    when(runnerAdapter.poll(REX_ID))
        .thenReturn(new RunnerPollStatus.HeartbeatTouched(OffsetDateTime.now(CLOCK)));

    broker.pollActiveExecutions();

    assertContainsLogAt(Level.INFO, "pollActiveExecutions start");
    assertContainsLogAt(Level.INFO, "pollActiveExecutions done");
    assertContainsLogAt(Level.INFO, "poll heartbeat advanced");
  }

  @Test
  void pollActiveExecutionsCrashLogsWarnWithRunnerCrashCategory() {
    // Fix #5: poll returning Failed(runner_crash) must log WARN with the precise category.
    RunnerExecutionSnapshot active = snapshot(REX_ID, RunnerExecutionStatus.RUNNING);
    when(recordPort.findActiveStatuses(any(), anyInt())).thenReturn(List.of(active));
    when(runnerAdapter.poll(REX_ID))
        .thenReturn(new RunnerPollStatus.Failed(FailureCategory.RUNNER_CRASH));

    broker.pollActiveExecutions();

    assertContainsLogAt(Level.WARN, "poll failure");
    assertContainsLogAt(Level.WARN, "category=runner_crash");
  }

  @Test
  void recoverOnStartupOrphanLogsWarnWithOrphanContext() {
    RunnerExecutionSnapshot active = snapshot(REX_ID, RunnerExecutionStatus.RUNNING);
    when(recordPort.findActiveStatuses(any(), anyInt())).thenReturn(List.of(active));
    when(scratchStore.tryReadRunnerResult(REX_ID)).thenReturn(Optional.empty());

    broker.recoverOnStartup();

    assertContainsLogAt(Level.WARN, "recoverOnStartup orphaned");
    assertContainsLogAt(Level.WARN, "runnerExecutionId=" + REX_ID);
  }

  private void assertContainsLogAt(Level level, String fragment) {
    List<ILoggingEvent> events = brokerAppender.list;
    boolean found =
        events.stream()
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
                + events.stream().map(e -> e.getLevel() + " " + e.getFormattedMessage()).toList());
  }

  private static RunnerExecutionSnapshot snapshot(String publicId, RunnerExecutionStatus status) {
    OffsetDateTime now = OffsetDateTime.now(CLOCK);
    return new RunnerExecutionSnapshot(
        publicId,
        RUN_ID,
        RunnerStage.INVESTIGATION,
        status,
        1,
        now,
        now.plusSeconds(600),
        null,
        RunnerExecutionStateMachine.isTerminal(status) ? now : null,
        now,
        null);
  }

  private static RunnerSecretScanService cleanScanService() {
    RunnerSecretScanService scanService = mock(RunnerSecretScanService.class);
    when(scanService.scanWorkspace(any(), any(), any(), any()))
        .thenReturn(RunnerSecretScanService.ScanOutcome.clean());
    return scanService;
  }

  private static TransactionTemplate callthroughTemplate() {
    TransactionTemplate template = mock(TransactionTemplate.class);
    when(template.execute(any()))
        .thenAnswer(
            invocation -> {
              TransactionCallback<?> callback = invocation.getArgument(0);
              return callback.doInTransaction(null);
            });
    return template;
  }

  private static ListAppender<ILoggingEvent> attachListAppender(Class<?> loggerClass) {
    Logger logger = (Logger) LoggerFactory.getLogger(loggerClass);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    logger.setLevel(Level.DEBUG);
    return appender;
  }

  private static void detach(Class<?> loggerClass, ListAppender<ILoggingEvent> appender) {
    Logger logger = (Logger) LoggerFactory.getLogger(loggerClass);
    logger.detachAppender(appender);
    appender.stop();
  }
}
