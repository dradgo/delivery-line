package org.dradgo.application.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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
import org.dradgo.application.runner.spi.RecoverableRunnerAdapter;
import org.dradgo.application.runner.spi.RunnerAdapter;
import org.dradgo.application.runner.spi.RunnerExecutionEventPort;
import org.dradgo.application.runner.spi.RunnerExecutionRecordPort;
import org.dradgo.application.runner.spi.RunnerExecutionSnapshot;
import org.dradgo.application.runner.spi.RunnerScratchStore;
import org.dradgo.application.runner.spi.TicketSummaryProvider;
import org.dradgo.application.runner.workspace.RepositoryWorkspaceService;
import org.dradgo.application.runner.workspace.spi.GitCommandException;
import org.dradgo.application.workflow.WorkflowTransitionService;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.FailureCategory;
import org.dradgo.domain.registry.IdempotencyRecordStatus;
import org.dradgo.domain.registry.IntegrationFailureCategory;
import org.dradgo.domain.registry.RunnerExecutionStatus;
import org.dradgo.domain.registry.RunnerStage;
import org.dradgo.domain.registry.WorkflowEventType;
import org.dradgo.domain.registry.WorkflowState;
import org.dradgo.runnercontracts.RunnerContractValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

class RunnerBrokerUnitTest {

  private static final String RUN_ID = "run_brkr12345678";
  private static final String REX_ID = "rex_brkr12345678";
  private static final ActorContext ACTOR =
      new ActorContext("human-pm", ActorType.HUMAN, "corr-1234");
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-05-12T12:00:00Z"), ZoneOffset.UTC);

  private RunnerExecutionRecordPort recordPort;
  private RunnerExecutionEventPort eventPort;
  private RunnerExecutionService executionService;
  private ContextBundleService contextBundleService;
  private IdempotencyService idempotencyService;
  private WorkflowTransitionService workflowTransitionService;
  private ArtifactOperationService artifactOperationService;
  private RunnerAdapter runnerAdapter;
  private RunnerScratchStore scratchStore;
  private RunnerProperties runnerProperties;
  private RunnerSecretScanService secretScanService;
  private RunnerBroker broker;

  @BeforeEach
  void setUp() {
    recordPort = mock(RunnerExecutionRecordPort.class);
    eventPort = mock(RunnerExecutionEventPort.class);
    executionService = mock(RunnerExecutionService.class);
    contextBundleService = mock(ContextBundleService.class);
    idempotencyService = mock(IdempotencyService.class);
    workflowTransitionService = mock(WorkflowTransitionService.class);
    artifactOperationService = mock(ArtifactOperationService.class);
    runnerAdapter = mock(RunnerAdapter.class);
    scratchStore = mock(RunnerScratchStore.class);
    runnerProperties = RunnerProperties.defaults();
    // Story 3.5: default to a clean scan; the secret-leak tests re-stub this mock per case.
    secretScanService = mock(RunnerSecretScanService.class);
    when(secretScanService.scanWorkspace(any(), any(), any(), any()))
        .thenReturn(RunnerSecretScanService.ScanOutcome.clean());
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
            runnerProperties,
            secretScanService,
            callthroughTemplate(),
            callthroughTemplate(),
            CLOCK);
  }

  @Test
  void dispatchHappyPathInsertsRowAppendsEventWritesBundleAndDelegatesToAdapter() {
    when(recordPort.nextContextBundleVersion(RUN_ID, RunnerStage.INVESTIGATION)).thenReturn(1);
    when(idempotencyService.checkAndReserve(
            eq("idem-1"), eq("RunnerBroker.dispatch"), any(), any()))
        .thenReturn(new ReservationOutcome(ReservationDecision.RESERVED, null));
    ContextBundle bundle =
        new ContextBundle(
            RUN_ID,
            RunnerStage.INVESTIGATION,
            "rex_placeholder1234",
            1,
            org.dradgo.domain.registry.DataClassification.SHAREABLE_REDACTED,
            "{}".getBytes(StandardCharsets.UTF_8));
    // Story 3a-1 (AC1c): the INVESTIGATION stage assembles its bundle via
    // createForSpecInvestigation. Story 3a-2 adds the trailing nullable repo-context summary param
    // (null on this no-repo path — the broker's repositoryWorkspaceService is null in the unit
    // ctor).
    when(contextBundleService.createForSpecInvestigation(
            eq(RUN_ID), any(), eq(1), any(), any(), eq(ACTOR), any()))
        .thenReturn(bundle);
    when(recordPort.insertPending(any(), eq(RUN_ID), eq(RunnerStage.INVESTIGATION), eq(1), any()))
        .thenAnswer(
            invocation -> snapshot(invocation.getArgument(0), RunnerExecutionStatus.PENDING));
    when(scratchStore.writeContextBundle(any(), any()))
        .thenReturn(Paths.get("/tmp/context-bundle.v1.json"));
    when(runnerAdapter.dispatch(any())).thenReturn(new RunnerDispatchAck("mock:happy-spec"));

    RunnerDispatchResult result =
        broker.dispatch(RUN_ID, RunnerStage.INVESTIGATION, "idem-1", ACTOR);

    assertInstanceOf(RunnerDispatchResult.Dispatched.class, result);
    verify(recordPort)
        .insertPending(any(), eq(RUN_ID), eq(RunnerStage.INVESTIGATION), eq(1), any());
    verify(eventPort)
        .append(
            eq(RUN_ID),
            eq(WorkflowEventType.RUNNER_STARTED),
            eq(ACTOR),
            any(),
            any(),
            any(),
            any());
    verify(idempotencyService).complete(eq("idem-1"), any(), eq(IdempotencyRecordStatus.COMPLETED));
    verify(scratchStore).writeContextBundle(any(), any(byte[].class));
    // Story 3b.1 (AC6) — an INVESTIGATION dispatch carries no sub-stage, so the adapter keeps the
    // unchanged "investigation" token.
    ArgumentCaptor<RunnerDispatchRequest> requestCaptor =
        ArgumentCaptor.forClass(RunnerDispatchRequest.class);
    verify(runnerAdapter).dispatch(requestCaptor.capture());
    assertNull(requestCaptor.getValue().subStage());
  }

  @Test
  void dispatchReplayShortCircuitsWithoutCallingAdapter() {
    when(recordPort.nextContextBundleVersion(RUN_ID, RunnerStage.INVESTIGATION)).thenReturn(2);
    when(idempotencyService.checkAndReserve(eq("idem-replay"), any(), any(), any()))
        .thenReturn(new ReservationOutcome(ReservationDecision.REPLAY, REX_ID));
    when(recordPort.findByPublicId(REX_ID))
        .thenReturn(Optional.of(snapshot(REX_ID, RunnerExecutionStatus.PENDING)));

    RunnerDispatchResult result =
        broker.dispatch(RUN_ID, RunnerStage.INVESTIGATION, "idem-replay", ACTOR);

    assertInstanceOf(RunnerDispatchResult.Replayed.class, result);
    assertEquals(REX_ID, result.handle().runnerExecutionId());
    verify(runnerAdapter, never()).dispatch(any());
    verify(recordPort, never()).insertPending(any(), any(), any(), anyInt(), any());
    verify(eventPort, never()).append(any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  void dispatchContextBundleViolationMarksIdempotencyFailedAndPropagates() {
    when(recordPort.nextContextBundleVersion(RUN_ID, RunnerStage.INVESTIGATION)).thenReturn(1);
    when(idempotencyService.checkAndReserve(eq("idem-bad"), any(), any(), any()))
        .thenReturn(new ReservationOutcome(ReservationDecision.RESERVED, null));
    when(contextBundleService.createForSpecInvestigation(
            any(), any(), anyInt(), any(), any(), any(), any()))
        .thenThrow(new DomainException(DomainErrorCode.RUNNER_CONTRACT_VIOLATION, "bundle bad"));

    DomainException error =
        assertThrows(
            DomainException.class,
            () -> broker.dispatch(RUN_ID, RunnerStage.INVESTIGATION, "idem-bad", ACTOR));
    assertEquals(DomainErrorCode.RUNNER_CONTRACT_VIOLATION, error.errorCode());

    verify(idempotencyService).complete(eq("idem-bad"), any(), eq(IdempotencyRecordStatus.FAILED));
    verify(runnerAdapter, never()).dispatch(any());
    verify(recordPort, never()).insertPending(any(), any(), any(), anyInt(), any());
  }

  @Test
  void dispatchExecutionResolvesRepoContextDerivesSubStageAndThreadsRepoRefIntoRequest() {
    // Story 3.10 (Task 4) — a broker WITH the profile-gated repo-workspace seam present,
    // dispatching
    // the EXECUTION stage: resolve the repo, prepare+summarize the workspace, derive the sub-stage,
    // and compose the repo-aware bundle + thread the repositoryRef onto the dispatch request.
    RepositoryWorkspaceService repoService = mock(RepositoryWorkspaceService.class);
    RunnerBroker repoBroker =
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
            runnerProperties,
            secretScanService,
            callthroughTemplate(),
            callthroughTemplate(),
            CLOCK,
            repoService);

    String branch = "deliveryline/DL-310/stage-12345678";
    RepositoryWorkspaceService.RepositoryMount mount =
        new RepositoryWorkspaceService.RepositoryMount(
            Paths.get("/tmp/repo"), "/workspace/repo", "main", branch);
    org.dradgo.application.runner.workspace.RepositoryContextSummary summary =
        new org.dradgo.application.runner.workspace.RepositoryContextSummary(
            "/workspace/repo", List.of(), "README.md", List.of(), "config:GH-101@1");

    when(recordPort.nextContextBundleVersion(RUN_ID, RunnerStage.EXECUTION)).thenReturn(1);
    when(idempotencyService.checkAndReserve(eq("idem-exec"), any(), any(), any()))
        .thenReturn(new ReservationOutcome(ReservationDecision.RESERVED, null));
    when(repoService.resolveConfiguredRepositoryRef()).thenReturn(Optional.of("GH-101"));
    when(repoService.prepareWorkspace(
            eq(RUN_ID), eq(RunnerStage.EXECUTION), any(), any(), any(), eq("GH-101")))
        .thenReturn(mount);
    when(repoService.summarize(eq(mount), any())).thenReturn(summary);
    when(contextBundleService.deriveExecutionSubStage(RUN_ID))
        .thenReturn(ExecutionSubStage.PR_OUTPUT);
    ContextBundle bundle =
        new ContextBundle(
            RUN_ID,
            RunnerStage.EXECUTION,
            REX_ID,
            1,
            org.dradgo.domain.registry.DataClassification.SHAREABLE_REDACTED,
            "{}".getBytes(StandardCharsets.UTF_8));
    when(contextBundleService.create(
            eq(RUN_ID),
            eq(RunnerStage.EXECUTION),
            any(),
            eq(1),
            any(),
            any(),
            eq(ACTOR),
            eq(ExecutionSubStage.PR_OUTPUT),
            eq(summary),
            eq(branch)))
        .thenReturn(bundle);
    when(recordPort.insertPending(any(), eq(RUN_ID), eq(RunnerStage.EXECUTION), eq(1), any()))
        .thenAnswer(
            invocation -> snapshot(invocation.getArgument(0), RunnerExecutionStatus.PENDING));
    when(scratchStore.writeContextBundle(any(), any()))
        .thenReturn(Paths.get("/tmp/context-bundle.v1.json"));
    when(runnerAdapter.dispatch(any())).thenReturn(new RunnerDispatchAck("mock:exec"));

    RunnerDispatchResult result =
        repoBroker.dispatch(RUN_ID, RunnerStage.EXECUTION, "idem-exec", ACTOR);

    assertInstanceOf(RunnerDispatchResult.Dispatched.class, result);
    verify(repoService)
        .prepareWorkspace(eq(RUN_ID), eq(RunnerStage.EXECUTION), any(), any(), any(), eq("GH-101"));
    verify(contextBundleService)
        .create(
            eq(RUN_ID),
            eq(RunnerStage.EXECUTION),
            any(),
            eq(1),
            any(),
            any(),
            eq(ACTOR),
            eq(ExecutionSubStage.PR_OUTPUT),
            eq(summary),
            eq(branch));
    // The dispatch request carries the resolved repositoryRef so the adapter mounts
    // /workspace/repo.
    ArgumentCaptor<RunnerDispatchRequest> requestCaptor =
        ArgumentCaptor.forClass(RunnerDispatchRequest.class);
    verify(runnerAdapter).dispatch(requestCaptor.capture());
    assertEquals("GH-101", requestCaptor.getValue().repositoryRef());
    // Story 3b.1 (AC1) — the EXECUTION dispatch carries the derived sub-stage so the adapter emits
    // the precise pr-output / implementation-plan token instead of the coarse execution token.
    assertEquals(ExecutionSubStage.PR_OUTPUT, requestCaptor.getValue().subStage());
  }

  @Test
  void dispatchExecutionWithProvider_threadsResolvedTicketRefIntoWorkspacePreparation() {
    // Story 3.10 (OQ-1) — the production path: a non-null TicketSummaryProvider resolves the run's
    // ticketRef, which drives the deterministic branch (story 3.9 AC2) and is threaded into
    // prepareWorkspace. The existing EXECUTION test constructs the broker with a NULL provider, so
    // this is the only coverage of resolveExecutionTicketRef's resolved-ticketRef branch.
    RepositoryWorkspaceService repoService = mock(RepositoryWorkspaceService.class);
    TicketSummaryProvider ticketProvider = mock(TicketSummaryProvider.class);
    when(ticketProvider.fetchByWorkflowRun(RUN_ID))
        .thenReturn(new TicketSummary("DL-310", "Export pipeline", "Open the PR."));
    RunnerBroker execBroker = executionBrokerWith(repoService, ticketProvider);

    RunnerDispatchResult result =
        execBroker.dispatch(RUN_ID, RunnerStage.EXECUTION, "idem-exec", ACTOR);

    assertInstanceOf(RunnerDispatchResult.Dispatched.class, result);
    // The resolved ticketRef is passed as the 4th arg of prepareWorkspace (drives the branch).
    verify(repoService)
        .prepareWorkspace(
            eq(RUN_ID), eq(RunnerStage.EXECUTION), any(), eq("DL-310"), any(), eq("GH-101"));
  }

  @Test
  void dispatchExecutionWithProvider_nullTicket_logsNoTicketRefAndPreparesWithNull() {
    RepositoryWorkspaceService repoService = mock(RepositoryWorkspaceService.class);
    TicketSummaryProvider ticketProvider = mock(TicketSummaryProvider.class);
    // The provider returns no ticket (TicketSummary itself forbids a blank ref, so a null ticket is
    // the only reachable no_ticket_ref path) → resolveExecutionTicketRef WARNs and returns null.
    when(ticketProvider.fetchByWorkflowRun(RUN_ID)).thenReturn(null);
    RunnerBroker execBroker = executionBrokerWith(repoService, ticketProvider);

    Logger logger = (Logger) LoggerFactory.getLogger(RunnerBroker.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      RunnerDispatchResult result =
          execBroker.dispatch(RUN_ID, RunnerStage.EXECUTION, "idem-exec", ACTOR);

      assertInstanceOf(RunnerDispatchResult.Dispatched.class, result);
      verify(repoService)
          .prepareWorkspace(
              eq(RUN_ID), eq(RunnerStage.EXECUTION), any(), isNull(), any(), eq("GH-101"));
      assertTrue(
          appender.list.stream()
              .anyMatch(
                  e ->
                      e.getLevel() == Level.WARN
                          && e.getFormattedMessage().contains("ticketRef unresolved")
                          && e.getFormattedMessage().contains("reason=no_ticket_ref")),
          "expected a no_ticket_ref WARN");
    } finally {
      logger.detachAppender(appender);
    }
  }

  @Test
  void dispatchExecutionWithProvider_resolutionThrows_logsFailureAndPreparesWithNull() {
    RepositoryWorkspaceService repoService = mock(RepositoryWorkspaceService.class);
    TicketSummaryProvider ticketProvider = mock(TicketSummaryProvider.class);
    // The provider throws → resolveExecutionTicketRef catches, WARNs with the cause class, returns
    // null, and dispatch proceeds (best-effort, no-ticket branch).
    when(ticketProvider.fetchByWorkflowRun(RUN_ID))
        .thenThrow(new IllegalStateException("linear down"));
    RunnerBroker execBroker = executionBrokerWith(repoService, ticketProvider);

    Logger logger = (Logger) LoggerFactory.getLogger(RunnerBroker.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      RunnerDispatchResult result =
          execBroker.dispatch(RUN_ID, RunnerStage.EXECUTION, "idem-exec", ACTOR);

      assertInstanceOf(RunnerDispatchResult.Dispatched.class, result);
      verify(repoService)
          .prepareWorkspace(
              eq(RUN_ID), eq(RunnerStage.EXECUTION), any(), isNull(), any(), eq("GH-101"));
      assertTrue(
          appender.list.stream()
              .anyMatch(
                  e ->
                      e.getLevel() == Level.WARN
                          && e.getFormattedMessage().contains("ticketRef resolution failed")
                          && e.getFormattedMessage().contains("cause=IllegalStateException")),
          "expected a resolution-failed WARN naming the cause class");
    } finally {
      logger.detachAppender(appender);
    }
  }

  /**
   * Builds an EXECUTION-stage broker wired with a repo-workspace seam and the given {@link
   * TicketSummaryProvider}, with every collaborator on the dispatch path stubbed (repo resolved,
   * workspace prepared+summarized, sub-stage derived, bundle composed, row inserted, adapter
   * acked). The {@code prepareWorkspace} ticketRef arg is left as {@code any()} so callers verify
   * the actual resolved value.
   */
  private RunnerBroker executionBrokerWith(
      RepositoryWorkspaceService repoService, TicketSummaryProvider ticketProvider) {
    RunnerBroker execBroker =
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
            runnerProperties,
            secretScanService,
            callthroughTemplate(),
            callthroughTemplate(),
            CLOCK,
            repoService,
            () -> null,
            ticketProvider,
            () -> null);

    RepositoryWorkspaceService.RepositoryMount mount =
        new RepositoryWorkspaceService.RepositoryMount(
            Paths.get("/tmp/repo"),
            "/workspace/repo",
            "main",
            "deliveryline/DL-310/stage-12345678");
    org.dradgo.application.runner.workspace.RepositoryContextSummary summary =
        new org.dradgo.application.runner.workspace.RepositoryContextSummary(
            "/workspace/repo", List.of(), "README.md", List.of(), "config:GH-101@1");

    when(recordPort.nextContextBundleVersion(RUN_ID, RunnerStage.EXECUTION)).thenReturn(1);
    when(idempotencyService.checkAndReserve(eq("idem-exec"), any(), any(), any()))
        .thenReturn(new ReservationOutcome(ReservationDecision.RESERVED, null));
    when(repoService.resolveConfiguredRepositoryRef()).thenReturn(Optional.of("GH-101"));
    when(repoService.prepareWorkspace(
            eq(RUN_ID), eq(RunnerStage.EXECUTION), any(), any(), any(), eq("GH-101")))
        .thenReturn(mount);
    when(repoService.summarize(eq(mount), any())).thenReturn(summary);
    when(contextBundleService.deriveExecutionSubStage(RUN_ID))
        .thenReturn(ExecutionSubStage.PR_OUTPUT);
    when(contextBundleService.create(
            eq(RUN_ID),
            eq(RunnerStage.EXECUTION),
            any(),
            eq(1),
            any(),
            any(),
            eq(ACTOR),
            any(),
            any(),
            any()))
        .thenReturn(
            new ContextBundle(
                RUN_ID,
                RunnerStage.EXECUTION,
                REX_ID,
                1,
                org.dradgo.domain.registry.DataClassification.SHAREABLE_REDACTED,
                "{}".getBytes(StandardCharsets.UTF_8)));
    when(recordPort.insertPending(any(), eq(RUN_ID), eq(RunnerStage.EXECUTION), eq(1), any()))
        .thenAnswer(
            invocation -> snapshot(invocation.getArgument(0), RunnerExecutionStatus.PENDING));
    when(scratchStore.writeContextBundle(any(), any()))
        .thenReturn(Paths.get("/tmp/context-bundle.v1.json"));
    when(runnerAdapter.dispatch(any())).thenReturn(new RunnerDispatchAck("mock:exec"));
    return execBroker;
  }

  @Test
  void onResultMalformedOutputMarksFailedWithoutWorkflowTransition() {
    when(recordPort.findByPublicId(REX_ID))
        .thenReturn(Optional.of(snapshot(REX_ID, RunnerExecutionStatus.RUNNING)));

    byte[] malformed = "{\"schemaVersion\":1".getBytes(StandardCharsets.UTF_8);
    broker.onResult(REX_ID, malformed);

    verify(executionService).recordFailed(REX_ID, FailureCategory.RUNNER_MALFORMED_OUTPUT);
    verify(eventPort)
        .append(
            eq(RUN_ID),
            eq(WorkflowEventType.RUNNER_FAILED),
            any(),
            any(),
            eq(FailureCategory.RUNNER_MALFORMED_OUTPUT),
            any(),
            any());
    // AC5 split: malformed output does NOT change workflow state.
    verify(workflowTransitionService, never())
        .transition(any(), any(), any(), any(), any(), any(FailureCategory.class), any());
  }

  @Test
  void onResultContractViolationDrivesWorkflowToFailed() {
    when(recordPort.findByPublicId(REX_ID))
        .thenReturn(Optional.of(snapshot(REX_ID, RunnerExecutionStatus.RUNNING)));

    // Valid JSON but schema-violating (missing required fields).
    String payloadJson =
        "{\"schemaVersion\":1,\"workflowRunId\":\""
            + RUN_ID
            + "\",\"runnerExecutionId\":\""
            + REX_ID
            + "\"}";
    byte[] payload = payloadJson.getBytes(StandardCharsets.UTF_8);
    broker.onResult(REX_ID, payload);

    verify(executionService).recordFailed(REX_ID, FailureCategory.RUNNER_CONTRACT_VIOLATION);
    ArgumentCaptor<FailureCategory> categoryCaptor = ArgumentCaptor.forClass(FailureCategory.class);
    verify(workflowTransitionService)
        .transition(
            eq(RUN_ID),
            eq(WorkflowState.FAILED),
            any(),
            any(),
            any(),
            categoryCaptor.capture(),
            any());
    assertEquals(FailureCategory.RUNNER_CONTRACT_VIOLATION, categoryCaptor.getValue());
  }

  @Test
  void onResultNonZeroExitDrivesWorkflowFailedWithNonZeroExitCategory() {
    when(recordPort.findByPublicId(REX_ID))
        .thenReturn(Optional.of(snapshot(REX_ID, RunnerExecutionStatus.RUNNING)));

    String payload =
        """
			{
			  "schemaVersion": 1,
			  "workflowRunId": "%s",
			  "runnerExecutionId": "%s",
			  "artifactReferences": [
			    {"artifactId": "art_test01234567", "artifactType": "spec", "contentReference": "spec/v1.json"}
			  ],
			  "normalizedOutput": {"summary": "boom", "outcome": "failure"},
			  "checksum": {"algorithm": "SHA-256", "hexDigest": "0000000000000000000000000000000000000000000000000000000000000001"},
			  "classification": "shareable-redacted",
			  "failureCategory": "runner_non_zero_exit"
			}
			"""
            .formatted(RUN_ID, REX_ID);
    broker.onResult(REX_ID, payload.getBytes(StandardCharsets.UTF_8));

    verify(executionService).recordFailed(REX_ID, FailureCategory.RUNNER_NON_ZERO_EXIT);
    verify(workflowTransitionService)
        .transition(
            eq(RUN_ID),
            eq(WorkflowState.FAILED),
            any(),
            any(),
            any(),
            eq(FailureCategory.RUNNER_NON_ZERO_EXIT),
            any());
    verify(artifactOperationService, never()).recordOperation(any());
    verify(executionService, never()).recordCompleted(any());
  }

  @Test
  void onResultHappyPathRecordsArtifactsAndMarksCompleted() {
    when(recordPort.findByPublicId(REX_ID))
        .thenReturn(Optional.of(snapshot(REX_ID, RunnerExecutionStatus.RUNNING)));
    byte[] artifactBytes = "spec-payload-bytes".getBytes(StandardCharsets.UTF_8);
    when(scratchStore.tryReadArtifactContent(eq(REX_ID), eq("spec/v1.json")))
        .thenReturn(Optional.of(artifactBytes));
    when(artifactOperationService.recordOperation(any()))
        .thenAnswer(
            invocation -> {
              RecordArtifactOperationCommand command = invocation.getArgument(0);
              ArtifactRecordSnapshot artifact =
                  ArtifactRecordSnapshot.withoutFailureMetadata(
                      "art_test01234567",
                      command.workflowRunId(),
                      command.artifactType(),
                      1,
                      null,
                      org.dradgo.domain.registry.DataClassification.SHAREABLE_REDACTED,
                      null,
                      null,
                      null,
                      org.dradgo.domain.registry.ArtifactStatus.PENDING,
                      null);
              ArtifactOperationSnapshot op =
                  new ArtifactOperationSnapshot(
                      "op_test01234567",
                      command.workflowRunId(),
                      "art_test01234567",
                      command.operationType().value(),
                      org.dradgo.domain.registry.ArtifactOperationStatus.PENDING,
                      command.idempotencyKey(),
                      null,
                      null,
                      OffsetDateTime.now(CLOCK));
              return new RecordArtifactOperationResult(artifact, op);
            });

    String payload =
        """
			{
			  "schemaVersion": 1,
			  "workflowRunId": "%s",
			  "runnerExecutionId": "%s",
			  "artifactReferences": [
			    {"artifactId": "art_test01234567", "artifactType": "spec", "contentReference": "spec/v1.json"}
			  ],
			  "normalizedOutput": {"summary": "ok", "outcome": "success"},
			  "checksum": {"algorithm": "SHA-256", "hexDigest": "0000000000000000000000000000000000000000000000000000000000000001"},
			  "classification": "shareable-redacted",
			  "failureCategory": null
			}
			"""
            .formatted(RUN_ID, REX_ID);
    broker.onResult(REX_ID, payload.getBytes(StandardCharsets.UTF_8));

    ArgumentCaptor<RecordArtifactOperationCommand> commandCaptor =
        ArgumentCaptor.forClass(RecordArtifactOperationCommand.class);
    verify(artifactOperationService, times(1)).recordOperation(commandCaptor.capture());
    RecordArtifactOperationCommand captured = commandCaptor.getValue();
    // Real artifact file bytes, not the artifact-reference JSON.
    assertEquals(
        "spec-payload-bytes", new String(captured.payloadContent(), StandardCharsets.UTF_8));
    // payloadRef is the leaf filename derived from contentReference, not null.
    assertEquals("v1.json", captured.payloadRef());
    verify(executionService).recordCompleted(REX_ID);
    verify(workflowTransitionService, never())
        .transition(any(), any(), any(), any(), any(), any(FailureCategory.class), any());
    // Story 3.5 AC4: the happy path runs the post-execution secret scan before completing.
    verify(secretScanService).scanWorkspace(eq(REX_ID), any(), any(), eq(RUN_ID));
  }

  @Test
  void onResultGitPushFailureRecordsFailedAndDrivesWorkflowFailed() {
    RepositoryWorkspaceService repoService = mock(RepositoryWorkspaceService.class);
    RunnerBroker repoBroker =
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
            runnerProperties,
            secretScanService,
            callthroughTemplate(),
            callthroughTemplate(),
            CLOCK,
            repoService);
    when(recordPort.findByPublicId(REX_ID))
        .thenReturn(Optional.of(snapshot(REX_ID, RunnerExecutionStatus.RUNNING)));
    byte[] artifactBytes = "spec-payload-bytes".getBytes(StandardCharsets.UTF_8);
    when(scratchStore.tryReadArtifactContent(eq(REX_ID), eq("spec/v1.json")))
        .thenReturn(Optional.of(artifactBytes));
    when(artifactOperationService.recordOperation(any()))
        .thenAnswer(
            invocation -> {
              RecordArtifactOperationCommand command = invocation.getArgument(0);
              ArtifactRecordSnapshot artifact =
                  ArtifactRecordSnapshot.withoutFailureMetadata(
                      "art_test01234567",
                      command.workflowRunId(),
                      command.artifactType(),
                      1,
                      null,
                      org.dradgo.domain.registry.DataClassification.SHAREABLE_REDACTED,
                      null,
                      null,
                      null,
                      org.dradgo.domain.registry.ArtifactStatus.PENDING,
                      null);
              ArtifactOperationSnapshot op =
                  new ArtifactOperationSnapshot(
                      "op_test01234567",
                      command.workflowRunId(),
                      "art_test01234567",
                      command.operationType().value(),
                      org.dradgo.domain.registry.ArtifactOperationStatus.PENDING,
                      command.idempotencyKey(),
                      null,
                      null,
                      OffsetDateTime.now(CLOCK));
              return new RecordArtifactOperationResult(artifact, op);
            });
    when(repoService.captureAndPush(REX_ID))
        .thenThrow(
            new GitCommandException(
                IntegrationFailureCategory.GIT_PUSH_REJECTED, "non-fast-forward"));

    String payload =
        """
			{
			  "schemaVersion": 1,
			  "workflowRunId": "%s",
			  "runnerExecutionId": "%s",
			  "artifactReferences": [
			    {"artifactId": "art_test01234567", "artifactType": "spec", "contentReference": "spec/v1.json"}
			  ],
			  "normalizedOutput": {"summary": "ok", "outcome": "success"},
			  "checksum": {"algorithm": "SHA-256", "hexDigest": "0000000000000000000000000000000000000000000000000000000000000001"},
			  "classification": "shareable-redacted",
			  "failureCategory": null
			}
			"""
            .formatted(RUN_ID, REX_ID);

    repoBroker.onResult(REX_ID, payload.getBytes(StandardCharsets.UTF_8));

    verify(executionService).recordFailed(REX_ID, FailureCategory.RUNNER_CONTRACT_VIOLATION);
    verify(executionService, never()).recordCompleted(any());
    verify(workflowTransitionService)
        .transition(
            eq(RUN_ID),
            eq(WorkflowState.FAILED),
            any(),
            any(),
            any(),
            eq(FailureCategory.RUNNER_CONTRACT_VIOLATION),
            any());
  }

  @Test
  void onResultSecretLeakMarksFailedQuarantinesAndDoesNotComplete() {
    // Story 3.5 AC4/AC11(c): a schema-valid result whose workspace leaks a secret is recorded
    // FAILED with runner_secret_leak, emits RUNNER_FAILED (leakedFile + category names),
    // quarantines
    // the workspace, and NEVER reaches recordCompleted.
    when(recordPort.findByPublicId(REX_ID))
        .thenReturn(Optional.of(snapshot(REX_ID, RunnerExecutionStatus.RUNNING)));
    byte[] artifactBytes = "spec-payload-bytes".getBytes(StandardCharsets.UTF_8);
    when(scratchStore.tryReadArtifactContent(eq(REX_ID), eq("spec/v1.json")))
        .thenReturn(Optional.of(artifactBytes));
    when(artifactOperationService.recordOperation(any()))
        .thenAnswer(
            invocation -> {
              RecordArtifactOperationCommand command = invocation.getArgument(0);
              ArtifactRecordSnapshot artifact =
                  ArtifactRecordSnapshot.withoutFailureMetadata(
                      "art_test01234567",
                      command.workflowRunId(),
                      command.artifactType(),
                      1,
                      null,
                      org.dradgo.domain.registry.DataClassification.SHAREABLE_REDACTED,
                      null,
                      null,
                      null,
                      org.dradgo.domain.registry.ArtifactStatus.PENDING,
                      null);
              ArtifactOperationSnapshot op =
                  new ArtifactOperationSnapshot(
                      "op_test01234567",
                      command.workflowRunId(),
                      "art_test01234567",
                      command.operationType().value(),
                      org.dradgo.domain.registry.ArtifactOperationStatus.PENDING,
                      command.idempotencyKey(),
                      null,
                      null,
                      OffsetDateTime.now(CLOCK));
              return new RecordArtifactOperationResult(artifact, op);
            });
    when(secretScanService.scanWorkspace(eq(REX_ID), any(), any(), eq(RUN_ID)))
        .thenReturn(
            new RunnerSecretScanService.ScanOutcome(
                true, "output/result.json", java.util.List.of("injected_provider_key")));

    String payload =
        """
        {
          "schemaVersion": 1,
          "workflowRunId": "%s",
          "runnerExecutionId": "%s",
          "artifactReferences": [
            {"artifactId": "art_test01234567", "artifactType": "spec", "contentReference": "spec/v1.json"}
          ],
          "normalizedOutput": {"summary": "ok", "outcome": "success"},
          "checksum": {"algorithm": "SHA-256", "hexDigest": "0000000000000000000000000000000000000000000000000000000000000001"},
          "classification": "shareable-redacted",
          "failureCategory": null
        }
        """
            .formatted(RUN_ID, REX_ID);
    broker.onResult(REX_ID, payload.getBytes(StandardCharsets.UTF_8));

    verify(executionService).recordFailed(REX_ID, FailureCategory.RUNNER_SECRET_LEAK);
    verify(executionService, never()).recordCompleted(any());
    verify(secretScanService).quarantine(eq(REX_ID), any());
    // Story 3.5 AC4 (review patch): a leak is scoped to the runner_execution + event + quarantine
    // and must NOT drive the workflow-run state — operator-driven recovery owns that path. Pin the
    // load-bearing invariant the production comment asserts so a future change that wires
    // driveWorkflowFailed into the leak branch is caught.
    verify(workflowTransitionService, never())
        .transition(any(), any(), any(), any(), any(), any(), any());
    ArgumentCaptor<java.util.Map<String, Object>> detailsCaptor =
        ArgumentCaptor.forClass(java.util.Map.class);
    verify(eventPort)
        .append(
            eq(RUN_ID),
            eq(WorkflowEventType.RUNNER_FAILED),
            any(),
            eq("runner_secret_leak"),
            eq(FailureCategory.RUNNER_SECRET_LEAK),
            any(),
            detailsCaptor.capture());
    assertEquals("runner_secret_leak", detailsCaptor.getValue().get("failureCategory"));
    assertEquals("output/result.json", detailsCaptor.getValue().get("leakedFile"));
    // Regression lock: the detected categories must reach the event detail as the actual
    // (non-empty) list, never collapsed to a scalar/empty string — the audit trail has to name
    // WHICH detector fired (e.g. [ENV_VALUE]) so a leak can be triaged from the event stream alone.
    assertEquals(
        java.util.List.of("injected_provider_key"),
        detailsCaptor.getValue().get("detectedCategories"));
  }

  @Test
  void onResultArtifactIngestionFailureMarksRunnerExecutionFailedInsteadOfCompleted() {
    // When ArtifactOperationService.recordOperation reports failure, the broker must NOT
    // silently mark the runner execution completed. It must surface the failure as
    // runner_contract_violation and drive the workflow run to FAILED (AC5 split).
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
                      "art_fail0123456789",
                      command.workflowRunId(),
                      command.artifactType(),
                      1,
                      null,
                      org.dradgo.domain.registry.DataClassification.SHAREABLE_REDACTED,
                      null,
                      null,
                      null,
                      org.dradgo.domain.registry.ArtifactStatus.FAILED,
                      null);
              ArtifactOperationSnapshot op =
                  new ArtifactOperationSnapshot(
                      "op_fail0123456789",
                      command.workflowRunId(),
                      "art_fail0123456789",
                      command.operationType().value(),
                      org.dradgo.domain.registry.ArtifactOperationStatus.FAILED,
                      command.idempotencyKey(),
                      FailureCategory.RUNNER_CONTRACT_VIOLATION,
                      "duplicate",
                      OffsetDateTime.now(CLOCK));
              ArtifactFailureResult failureMeta = new ArtifactFailureResult(artifact, op);
              return new RecordArtifactOperationResult(artifact, op, failureMeta);
            });

    String payload =
        """
			{
			  "schemaVersion": 1,
			  "workflowRunId": "%s",
			  "runnerExecutionId": "%s",
			  "artifactReferences": [
			    {"artifactId": "art_fail0123456789", "artifactType": "spec", "contentReference": "spec/v1.json"}
			  ],
			  "normalizedOutput": {"summary": "ok", "outcome": "success"},
			  "checksum": {"algorithm": "SHA-256", "hexDigest": "0000000000000000000000000000000000000000000000000000000000000001"},
			  "classification": "shareable-redacted",
			  "failureCategory": null
			}
			"""
            .formatted(RUN_ID, REX_ID);
    broker.onResult(REX_ID, payload.getBytes(StandardCharsets.UTF_8));

    verify(executionService, never()).recordCompleted(any());
    verify(executionService).recordFailed(REX_ID, FailureCategory.RUNNER_CONTRACT_VIOLATION);
    verify(eventPort)
        .append(
            eq(RUN_ID),
            eq(WorkflowEventType.RUNNER_FAILED),
            any(),
            any(),
            eq(FailureCategory.RUNNER_CONTRACT_VIOLATION),
            any(),
            any());
    verify(workflowTransitionService)
        .transition(
            eq(RUN_ID),
            eq(WorkflowState.FAILED),
            any(),
            any(),
            any(),
            eq(FailureCategory.RUNNER_CONTRACT_VIOLATION),
            any());
  }

  @Test
  void onResultRejectsResultWhenReferencedArtifactFileIsUnreadable() {
    // Missing or unreadable artifact file at the referenced contentReference path ⇒
    // classify as runner_contract_violation, drive workflow run to FAILED (AC5 split).
    when(recordPort.findByPublicId(REX_ID))
        .thenReturn(Optional.of(snapshot(REX_ID, RunnerExecutionStatus.RUNNING)));
    when(scratchStore.tryReadArtifactContent(eq(REX_ID), any())).thenReturn(Optional.empty());

    String payload =
        """
			{
			  "schemaVersion": 1,
			  "workflowRunId": "%s",
			  "runnerExecutionId": "%s",
			  "artifactReferences": [
			    {"artifactId": "art_missing0123456", "artifactType": "spec", "contentReference": "spec/v1.json"}
			  ],
			  "normalizedOutput": {"summary": "ok", "outcome": "success"},
			  "checksum": {"algorithm": "SHA-256", "hexDigest": "0000000000000000000000000000000000000000000000000000000000000001"},
			  "classification": "shareable-redacted",
			  "failureCategory": null
			}
			"""
            .formatted(RUN_ID, REX_ID);
    broker.onResult(REX_ID, payload.getBytes(StandardCharsets.UTF_8));

    verify(artifactOperationService, never()).recordOperation(any());
    verify(executionService).recordFailed(REX_ID, FailureCategory.RUNNER_CONTRACT_VIOLATION);
    verify(workflowTransitionService)
        .transition(
            eq(RUN_ID),
            eq(WorkflowState.FAILED),
            any(),
            any(),
            any(),
            eq(FailureCategory.RUNNER_CONTRACT_VIOLATION),
            any());
  }

  @Test
  void
      onResultDuplicateAgainstCompletedPeerSurfacesAsRunnerDuplicateResultWithoutWorkflowTransition() {
    // Row already in COMPLETED state ⇒ peer contributes its id to observedRunnerExecutionIds,
    // the validator fires DUPLICATE_RUNNER_EXECUTION_ID, broker classifies as
    // runner_duplicate_result,
    // AC5 split: no workflow transition, no double recordFailed on an already-terminal row.
    RunnerExecutionSnapshot completedPeer =
        new RunnerExecutionSnapshot(
            REX_ID,
            RUN_ID,
            RunnerStage.INVESTIGATION,
            RunnerExecutionStatus.COMPLETED,
            1,
            OffsetDateTime.now(CLOCK),
            OffsetDateTime.now(CLOCK).plusSeconds(600),
            null,
            OffsetDateTime.now(CLOCK),
            OffsetDateTime.now(CLOCK),
            null);
    when(recordPort.findByPublicId(REX_ID)).thenReturn(Optional.of(completedPeer));
    when(recordPort.findByWorkflowRunPublicIdAndStatusIn(eq(RUN_ID), any()))
        .thenReturn(List.of(completedPeer));

    String payload =
        """
			{
			  "schemaVersion": 1,
			  "workflowRunId": "%s",
			  "runnerExecutionId": "%s",
			  "artifactReferences": [
			    {"artifactId": "art_dup0123456789", "artifactType": "spec", "contentReference": "spec/v1.json"}
			  ],
			  "normalizedOutput": {"summary": "dup", "outcome": "success"},
			  "checksum": {"algorithm": "SHA-256", "hexDigest": "0000000000000000000000000000000000000000000000000000000000000001"},
			  "classification": "shareable-redacted",
			  "failureCategory": null
			}
			"""
            .formatted(RUN_ID, REX_ID);
    broker.onResult(REX_ID, payload.getBytes(StandardCharsets.UTF_8));

    // Row already terminal — must NOT re-transition the runner_execution row.
    verify(executionService, never()).recordFailed(any(), any());
    // Must emit RUNNER_FAILED with the precise duplicate category.
    verify(eventPort)
        .append(
            eq(RUN_ID),
            eq(WorkflowEventType.RUNNER_FAILED),
            any(),
            any(),
            eq(FailureCategory.RUNNER_DUPLICATE_RESULT),
            any(),
            any());
    // AC5 split: duplicate-result must NOT drive the workflow run.
    verify(workflowTransitionService, never())
        .transition(any(), any(), any(), any(), any(), any(FailureCategory.class), any());
    // Must NOT route a successful artifact for a duplicate.
    verify(artifactOperationService, never()).recordOperation(any());
    verify(executionService, never()).recordCompleted(any());
  }

  @Test
  void onResultBuildsValidationContextWithKnownPeerExecutionIds() {
    // Peers (e.g., a sibling rex in the same workflow run) contribute to knownRunnerExecutionIds so
    // the validator's STALE_METADATA check doesn't fire for legitimate sibling rex ids.
    RunnerExecutionSnapshot current = snapshot(REX_ID, RunnerExecutionStatus.RUNNING);
    RunnerExecutionSnapshot sibling =
        new RunnerExecutionSnapshot(
            "rex_sibling01234",
            RUN_ID,
            RunnerStage.INVESTIGATION,
            RunnerExecutionStatus.COMPLETED,
            1,
            OffsetDateTime.now(CLOCK),
            OffsetDateTime.now(CLOCK).plusSeconds(600),
            null,
            OffsetDateTime.now(CLOCK),
            OffsetDateTime.now(CLOCK),
            null);
    when(recordPort.findByPublicId(REX_ID)).thenReturn(Optional.of(current));
    when(recordPort.findByWorkflowRunPublicIdAndStatusIn(eq(RUN_ID), any()))
        .thenReturn(List.of(current, sibling));
    when(scratchStore.tryReadArtifactContent(eq(REX_ID), eq("spec/v1.json")))
        .thenReturn(Optional.of("payload".getBytes(StandardCharsets.UTF_8)));

    String payload =
        """
			{
			  "schemaVersion": 1,
			  "workflowRunId": "%s",
			  "runnerExecutionId": "%s",
			  "artifactReferences": [
			    {"artifactId": "art_ctx01234567ab", "artifactType": "spec", "contentReference": "spec/v1.json"}
			  ],
			  "normalizedOutput": {"summary": "ok", "outcome": "success"},
			  "checksum": {"algorithm": "SHA-256", "hexDigest": "0000000000000000000000000000000000000000000000000000000000000001"},
			  "classification": "shareable-redacted",
			  "failureCategory": null
			}
			"""
            .formatted(RUN_ID, REX_ID);
    when(artifactOperationService.recordOperation(any()))
        .thenAnswer(
            invocation -> {
              RecordArtifactOperationCommand command = invocation.getArgument(0);
              ArtifactRecordSnapshot artifact =
                  ArtifactRecordSnapshot.withoutFailureMetadata(
                      "art_ctx01234567ab",
                      command.workflowRunId(),
                      command.artifactType(),
                      1,
                      null,
                      org.dradgo.domain.registry.DataClassification.SHAREABLE_REDACTED,
                      null,
                      null,
                      null,
                      org.dradgo.domain.registry.ArtifactStatus.PENDING,
                      null);
              ArtifactOperationSnapshot op =
                  new ArtifactOperationSnapshot(
                      "op_ctx01234567ab",
                      command.workflowRunId(),
                      "art_ctx01234567ab",
                      command.operationType().value(),
                      org.dradgo.domain.registry.ArtifactOperationStatus.PENDING,
                      command.idempotencyKey(),
                      null,
                      null,
                      OffsetDateTime.now(CLOCK));
              return new RecordArtifactOperationResult(artifact, op);
            });
    broker.onResult(REX_ID, payload.getBytes(StandardCharsets.UTF_8));

    // Peer scan was consulted to build the context.
    verify(recordPort, atLeastOnce()).findByWorkflowRunPublicIdAndStatusIn(eq(RUN_ID), any());
    // Validation did NOT reject (no RUNNER_FAILED event).
    verify(eventPort, never())
        .append(any(), eq(WorkflowEventType.RUNNER_FAILED), any(), any(), any(), any(), any());
  }

  @Test
  void scanForTimeoutsFlipsStaleRowAndDrivesWorkflowFailed() {
    RunnerExecutionSnapshot stale = staleSnapshot(REX_ID, RunnerExecutionStatus.RUNNING);
    when(recordPort.findStaleByStatusInAndTimeoutAtBefore(any(), any(), anyInt()))
        .thenReturn(List.of(stale));
    when(recordPort.findByPublicId(REX_ID)).thenReturn(Optional.of(stale));

    int flipped = broker.scanForTimeouts();

    assertEquals(1, flipped);
    verify(executionService).recordTimedOut(REX_ID);
    // Story 3.2 AC8 / Trap T10: RUNNER_FAILED + details.failureCategory=runner_timeout is
    // replaced by the dedicated RUNNER_TIMEOUT event.
    verify(eventPort)
        .append(
            eq(RUN_ID),
            eq(WorkflowEventType.RUNNER_TIMEOUT),
            any(),
            any(),
            eq(FailureCategory.RUNNER_TIMEOUT),
            any(),
            any());
    verify(workflowTransitionService)
        .transition(
            eq(RUN_ID),
            eq(WorkflowState.FAILED),
            any(),
            any(),
            any(),
            eq(FailureCategory.RUNNER_TIMEOUT),
            any());
  }

  @Test
  void scanForTimeoutsSkipsAlreadyTerminalRow() {
    RunnerExecutionSnapshot stale = staleSnapshot(REX_ID, RunnerExecutionStatus.RUNNING);
    RunnerExecutionSnapshot terminal = snapshot(REX_ID, RunnerExecutionStatus.COMPLETED);
    when(recordPort.findStaleByStatusInAndTimeoutAtBefore(any(), any(), anyInt()))
        .thenReturn(List.of(stale));
    when(recordPort.findByPublicId(REX_ID)).thenReturn(Optional.of(terminal));

    int flipped = broker.scanForTimeouts();

    assertEquals(0, flipped);
    verify(executionService, never()).recordTimedOut(any());
    verify(workflowTransitionService, never())
        .transition(any(), any(), any(), any(), any(), any(FailureCategory.class), any());
  }

  @Test
  void scanForTimeoutsSkipsRowWhoseTimeoutAtWasExtendedByHeartbeatBeforeRescan() {
    // Initial scan picked up this row when its deadline was in the past (or about to be),
    // but a heartbeat advanced timeout_at past now() before the per-item transaction opened.
    // The rescan must NOT clobber a live execution to TIMED_OUT.
    RunnerExecutionSnapshot staleInitial = staleSnapshot(REX_ID, RunnerExecutionStatus.RUNNING);
    OffsetDateTime now = OffsetDateTime.now(CLOCK);
    RunnerExecutionSnapshot extendedByHeartbeat =
        new RunnerExecutionSnapshot(
            REX_ID,
            RUN_ID,
            RunnerStage.INVESTIGATION,
            RunnerExecutionStatus.RUNNING,
            1,
            now,
            now.plusSeconds(120),
            null,
            null,
            now,
            null);
    when(recordPort.findStaleByStatusInAndTimeoutAtBefore(any(), any(), anyInt()))
        .thenReturn(List.of(staleInitial));
    when(recordPort.findByPublicId(REX_ID)).thenReturn(Optional.of(extendedByHeartbeat));

    int flipped = broker.scanForTimeouts();

    assertEquals(0, flipped, "extended heartbeat must keep the row alive");
    verify(executionService, never()).recordTimedOut(any());
    verify(eventPort, never())
        .append(any(), eq(WorkflowEventType.RUNNER_FAILED), any(), any(), any(), any(), any());
    // Story 3.2 AC8: dedicated RUNNER_TIMEOUT also must NOT fire on the heartbeat-extended path.
    verify(eventPort, never())
        .append(any(), eq(WorkflowEventType.RUNNER_TIMEOUT), any(), any(), any(), any(), any());
    verify(workflowTransitionService, never())
        .transition(any(), any(), any(), any(), any(), any(FailureCategory.class), any());
  }

  @Test
  void scanForTimeoutsHeartbeatExtendedDeadlineDoesNotInvokeDockerTerminate() {
    // Story 3.2a code-review (2026-05-29) — AC6 Trap T1: a heartbeat that bumps timeout_at past
    // now() between the initial scan and the per-item transaction must make the broker skip the row
    // BEFORE issuing any container stop/kill. This is the docker-path variant of
    // scanForTimeoutsSkipsRowWhoseTimeoutAtWasExtendedByHeartbeatBeforeRescan: that test uses a
    // plain RunnerAdapter (so terminate is unreachable by type); here a RecoverableRunnerAdapter is
    // wired so the assertion that terminate/findContainerIdFor are NEVER called actually proves the
    // heartbeat-race guard runs ahead of the kill block (RunnerBroker.processSingleTimeout).
    RecoverableRunnerAdapter dockerAdapter = mock(RecoverableRunnerAdapter.class);
    runnerAdapter = dockerAdapter;
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
            runnerProperties,
            secretScanService,
            callthroughTemplate(),
            callthroughTemplate(),
            CLOCK);

    RunnerExecutionSnapshot staleInitial = staleSnapshot(REX_ID, RunnerExecutionStatus.RUNNING);
    OffsetDateTime now = OffsetDateTime.now(CLOCK);
    RunnerExecutionSnapshot extendedByHeartbeat =
        new RunnerExecutionSnapshot(
            REX_ID,
            RUN_ID,
            RunnerStage.INVESTIGATION,
            RunnerExecutionStatus.RUNNING,
            1,
            now,
            now.plusSeconds(120),
            null,
            null,
            now,
            null);
    when(recordPort.findStaleByStatusInAndTimeoutAtBefore(any(), any(), anyInt()))
        .thenReturn(List.of(staleInitial));
    when(recordPort.findByPublicId(REX_ID)).thenReturn(Optional.of(extendedByHeartbeat));

    int flipped = broker.scanForTimeouts();

    assertEquals(0, flipped, "heartbeat-extended deadline must not flip the row");
    // The kill path must be unreachable: neither the container lookup nor terminate may run.
    verify(dockerAdapter, never()).findContainerIdFor(any());
    verify(dockerAdapter, never()).terminate(any(), any());
    verify(executionService, never()).recordTimedOut(any());
    verify(eventPort, never())
        .append(any(), eq(WorkflowEventType.RUNNER_TIMEOUT), any(), any(), any(), any(), any());
  }

  @Test
  void onResultLateAgainstTimedOutRowEmitsRunnerLateResultEventWithoutTransition() {
    // AC5 split: a result arriving at a TIMED_OUT row classifies as runner_late_result.
    // The runner-execution row stays TIMED_OUT (no further transition allowed by state machine);
    // a RUNNER_FAILED event is appended with the precise late-result category; the workflow
    // run is intentionally NOT transitioned.
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

    verify(eventPort)
        .append(
            eq(RUN_ID),
            eq(WorkflowEventType.RUNNER_FAILED),
            any(),
            any(),
            eq(FailureCategory.RUNNER_LATE_RESULT),
            any(),
            any());
    // AC5: late-result must NOT drive the workflow run.
    verify(workflowTransitionService, never())
        .transition(any(), any(), any(), any(), any(), any(FailureCategory.class), any());
    verify(executionService).recordFailed(REX_ID, FailureCategory.RUNNER_LATE_RESULT);
    verify(executionService, never()).recordCompleted(any());
  }

  @Test
  void onResultLateAgainstOrphanedRowEmitsRunnerLateResultEventWithoutTransition() {
    RunnerExecutionSnapshot orphaned =
        new RunnerExecutionSnapshot(
            REX_ID,
            RUN_ID,
            RunnerStage.INVESTIGATION,
            RunnerExecutionStatus.ORPHANED,
            1,
            OffsetDateTime.now(CLOCK),
            OffsetDateTime.now(CLOCK).minusSeconds(60),
            FailureCategory.ORPHAN,
            OffsetDateTime.now(CLOCK),
            OffsetDateTime.now(CLOCK),
            null);
    when(recordPort.findByPublicId(REX_ID)).thenReturn(Optional.of(orphaned));
    when(scratchStore.tryReadArtifactContent(eq(REX_ID), any())).thenReturn(Optional.empty());

    String payload =
        """
			{
			  "schemaVersion": 1,
			  "workflowRunId": "%s",
			  "runnerExecutionId": "%s",
			  "artifactReferences": [
			    {"artifactId": "art_orphan0123456", "artifactType": "spec", "contentReference": "spec/v1.json"}
			  ],
			  "normalizedOutput": {"summary": "post-orphan", "outcome": "success"},
			  "checksum": {"algorithm": "SHA-256", "hexDigest": "0000000000000000000000000000000000000000000000000000000000000001"},
			  "classification": "shareable-redacted",
			  "failureCategory": null
			}
			"""
            .formatted(RUN_ID, REX_ID);
    broker.onResult(REX_ID, payload.getBytes(StandardCharsets.UTF_8));

    verify(eventPort)
        .append(
            eq(RUN_ID),
            eq(WorkflowEventType.RUNNER_FAILED),
            any(),
            any(),
            eq(FailureCategory.RUNNER_LATE_RESULT),
            any(),
            any());
    verify(executionService).recordFailed(REX_ID, FailureCategory.RUNNER_LATE_RESULT);
    verify(workflowTransitionService, never())
        .transition(any(), any(), any(), any(), any(), any(FailureCategory.class), any());
  }

  @Test
  void onResultAfterTakeoverCancellationPerformsNoResultSideEffects() {
    RunnerExecutionSnapshot cancelled =
        new RunnerExecutionSnapshot(
            REX_ID,
            RUN_ID,
            RunnerStage.INVESTIGATION,
            RunnerExecutionStatus.CANCELLED_FOR_TAKEOVER,
            1,
            OffsetDateTime.now(CLOCK),
            OffsetDateTime.now(CLOCK).minusSeconds(60),
            null,
            OffsetDateTime.now(CLOCK),
            OffsetDateTime.now(CLOCK),
            null);
    when(recordPort.findByPublicId(REX_ID)).thenReturn(Optional.of(cancelled));

    broker.onResult(REX_ID, "{\"schemaVersion\":1".getBytes(StandardCharsets.UTF_8));

    verify(executionService, never()).recordFailed(any(), any());
    verify(executionService, never()).recordCompleted(any());
    verify(artifactOperationService, never()).recordOperation(any());
    verify(eventPort, never()).append(any(), any(), any(), any(), any(), any(), any());
    verify(workflowTransitionService, never())
        .transition(any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  void onResultLateMalformedPayloadMarksLateResultFailedWithoutArtifactHarvest() {
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

    broker.onResult(REX_ID, "{\"schemaVersion\":1".getBytes(StandardCharsets.UTF_8));

    verify(executionService).recordFailed(REX_ID, FailureCategory.RUNNER_LATE_RESULT);
    verify(artifactOperationService, never()).recordOperation(any());
    verify(workflowTransitionService, never())
        .transition(any(), any(), any(), any(), any(), any(FailureCategory.class), any());
  }

  @Test
  void pollActiveExecutionsAdvancesLastActivityOnHeartbeat() {
    RunnerExecutionSnapshot active = snapshot(REX_ID, RunnerExecutionStatus.RUNNING);
    when(recordPort.findActiveStatuses(any(), anyInt())).thenReturn(List.of(active));
    when(runnerAdapter.poll(REX_ID))
        .thenReturn(new RunnerPollStatus.HeartbeatTouched(OffsetDateTime.now(CLOCK)));

    int processed = broker.pollActiveExecutions();

    assertEquals(1, processed);
    verify(executionService)
        .touchActivity(
            eq(REX_ID), any(OffsetDateTime.class), eq(java.time.Duration.ofSeconds(1200)));
    verify(executionService, never()).recordFailed(any(), any());
    verify(workflowTransitionService, never())
        .transition(any(), any(), any(), any(), any(), any(FailureCategory.class), any());
  }

  @Test
  void pollActiveExecutionsSurfacesRunnerCrashAndDrivesWorkflowFailed() {
    RunnerExecutionSnapshot active = snapshot(REX_ID, RunnerExecutionStatus.RUNNING);
    when(recordPort.findActiveStatuses(any(), anyInt())).thenReturn(List.of(active));
    when(runnerAdapter.poll(REX_ID))
        .thenReturn(new RunnerPollStatus.Failed(FailureCategory.RUNNER_CRASH));

    int processed = broker.pollActiveExecutions();

    assertEquals(1, processed);
    verify(executionService).recordFailed(REX_ID, FailureCategory.RUNNER_CRASH);
    verify(eventPort)
        .append(
            eq(RUN_ID),
            eq(WorkflowEventType.RUNNER_FAILED),
            any(),
            any(),
            eq(FailureCategory.RUNNER_CRASH),
            any(),
            any());
    // AC5 split: runner_crash drives the workflow run to FAILED.
    verify(workflowTransitionService)
        .transition(
            eq(RUN_ID),
            eq(WorkflowState.FAILED),
            any(),
            any(),
            any(),
            eq(FailureCategory.RUNNER_CRASH),
            any());
  }

  @Test
  void pollActiveExecutionsHarvestsCompletedResultAndMarksRunnerExecutionCompleted() {
    // Story 3.1: poll returning Completed must harvest through the active RunnerAdapter.
    // DockerRunnerAdapter stores the result in runner-work/output, not runner-scratch.
    RunnerExecutionSnapshot active = snapshot(REX_ID, RunnerExecutionStatus.RUNNING);
    when(recordPort.findActiveStatuses(any(), anyInt())).thenReturn(List.of(active));
    RunnerExecutionSnapshot captured = snapshotWithRawOutput(REX_ID, RunnerExecutionStatus.RUNNING);
    when(recordPort.findByPublicId(REX_ID)).thenReturn(Optional.of(active), Optional.of(captured));
    when(runnerAdapter.poll(REX_ID)).thenReturn(new RunnerPollStatus.Completed());

    String resultPayload =
        """
			{
			  "schemaVersion": 1,
			  "workflowRunId": "%s",
			  "runnerExecutionId": "%s",
			  "artifactReferences": [
			    {"artifactId": "art_harvest012345", "artifactType": "spec", "contentReference": "spec/v1.json"}
			  ],
			  "normalizedOutput": {"summary": "harvested", "outcome": "success"},
			  "checksum": {"algorithm": "SHA-256", "hexDigest": "0000000000000000000000000000000000000000000000000000000000000001"},
			  "classification": "shareable-redacted",
			  "failureCategory": null
			}
			"""
            .formatted(RUN_ID, REX_ID);
    when(runnerAdapter.tryReadResult(REX_ID))
        .thenReturn(Optional.of(resultPayload.getBytes(StandardCharsets.UTF_8)));
    when(scratchStore.tryReadArtifactContent(eq(REX_ID), eq("spec/v1.json")))
        .thenReturn(Optional.of("harvest-bytes".getBytes(StandardCharsets.UTF_8)));
    when(artifactOperationService.recordOperation(any()))
        .thenAnswer(
            invocation -> {
              RecordArtifactOperationCommand command = invocation.getArgument(0);
              ArtifactRecordSnapshot artifact =
                  ArtifactRecordSnapshot.withoutFailureMetadata(
                      "art_harvest012345",
                      command.workflowRunId(),
                      command.artifactType(),
                      1,
                      null,
                      org.dradgo.domain.registry.DataClassification.SHAREABLE_REDACTED,
                      null,
                      null,
                      null,
                      org.dradgo.domain.registry.ArtifactStatus.PENDING,
                      null);
              ArtifactOperationSnapshot op =
                  new ArtifactOperationSnapshot(
                      "op_harvest012345",
                      command.workflowRunId(),
                      "art_harvest012345",
                      command.operationType().value(),
                      org.dradgo.domain.registry.ArtifactOperationStatus.PENDING,
                      command.idempotencyKey(),
                      null,
                      null,
                      OffsetDateTime.now(CLOCK));
              return new RecordArtifactOperationResult(artifact, op);
            });

    int processed = broker.pollActiveExecutions();

    assertEquals(1, processed);
    verify(runnerAdapter).tryReadResult(REX_ID);
    verify(scratchStore, never()).tryReadRunnerResult(REX_ID);
    verify(artifactOperationService).recordOperation(any());
    verify(executionService).recordCompleted(REX_ID);
    ArgumentCaptor<java.util.Map<String, Object>> detailsCaptor =
        ArgumentCaptor.forClass(java.util.Map.class);
    verify(eventPort)
        .append(
            eq(RUN_ID),
            eq(WorkflowEventType.RUNNER_COMPLETED),
            any(),
            any(),
            any(),
            any(),
            detailsCaptor.capture());
    assertEquals(RUN_ID, detailsCaptor.getValue().get("workflowRunId"));
    assertEquals(2, detailsCaptor.getValue().get("redactionCount"));
    assertEquals(42L, detailsCaptor.getValue().get("rawOutputByteSize"));
    assertEquals("local-only", detailsCaptor.getValue().get("rawOutputClassification"));
  }

  @Test
  void pollActiveExecutionsCompletedNoOpWhenScratchResultIsMissing() {
    // Fix #5 defensive case: adapter reported Completed but the result file isn't on disk
    // (rare, e.g., disk wipe). Broker must return false for that item without crashing,
    // so the scheduled tick can try again on the next pass.
    RunnerExecutionSnapshot active = snapshot(REX_ID, RunnerExecutionStatus.RUNNING);
    when(recordPort.findActiveStatuses(any(), anyInt())).thenReturn(List.of(active));
    when(runnerAdapter.poll(REX_ID)).thenReturn(new RunnerPollStatus.Completed());
    when(runnerAdapter.tryReadResult(REX_ID)).thenReturn(Optional.empty());

    int processed = broker.pollActiveExecutions();

    assertEquals(0, processed);
    verify(artifactOperationService, never()).recordOperation(any());
    verify(executionService, never()).recordCompleted(any());
    verify(executionService, never()).recordFailed(any(), any());
  }

  @Test
  void pollActiveExecutionsTouchesActivityForRunningAndSkipsUnknown() {
    RunnerExecutionSnapshot running = snapshot(REX_ID, RunnerExecutionStatus.RUNNING);
    RunnerExecutionSnapshot pending = snapshot("rex_pending012345", RunnerExecutionStatus.PENDING);
    when(recordPort.findActiveStatuses(any(), anyInt())).thenReturn(List.of(running, pending));
    when(runnerAdapter.poll(REX_ID)).thenReturn(new RunnerPollStatus.Running());
    when(runnerAdapter.poll("rex_pending012345")).thenReturn(new RunnerPollStatus.Unknown());

    int processed = broker.pollActiveExecutions();

    assertEquals(1, processed);
    verify(executionService).touchActivity(eq(REX_ID), eq(java.time.Duration.ofSeconds(1200)));
    verify(executionService, never()).recordFailed(any(), any());
    verify(executionService, never()).recordCompleted(any());
  }

  @Test
  void recoverOnStartupOrphansActiveRowWithoutResultFileAndEmitsReconciledEvent() {
    RunnerExecutionSnapshot active = snapshot(REX_ID, RunnerExecutionStatus.RUNNING);
    when(recordPort.findActiveStatuses(any(), anyInt())).thenReturn(List.of(active));
    when(scratchStore.tryReadRunnerResult(REX_ID)).thenReturn(Optional.empty());

    int handled = broker.recoverOnStartup();

    assertEquals(1, handled);
    verify(executionService).recordOrphaned(REX_ID);
    verify(eventPort)
        .append(
            eq(RUN_ID),
            eq(WorkflowEventType.RECOVERY_RECONCILED),
            any(),
            any(),
            eq(FailureCategory.ORPHAN),
            any(),
            any());
    // AC5: orphan does NOT drive the workflow run to FAILED.
    verify(workflowTransitionService, never())
        .transition(any(), any(), any(), any(), any(), any(FailureCategory.class), any());
  }

  @Test
  void recoverOnStartupResumesActiveRowWhenValidResultFilePresent() {
    RunnerExecutionSnapshot active = snapshot(REX_ID, RunnerExecutionStatus.RUNNING);
    when(recordPort.findActiveStatuses(any(), anyInt())).thenReturn(List.of(active));
    when(recordPort.findByPublicId(REX_ID)).thenReturn(Optional.of(active));
    String payload =
        """
			{
			  "schemaVersion": 1,
			  "workflowRunId": "%s",
			  "runnerExecutionId": "%s",
			  "artifactReferences": [
			    {"artifactId": "art_resume0123456", "artifactType": "spec", "contentReference": "spec/v1.json"}
			  ],
			  "normalizedOutput": {"summary": "resumed", "outcome": "success"},
			  "checksum": {"algorithm": "SHA-256", "hexDigest": "0000000000000000000000000000000000000000000000000000000000000001"},
			  "classification": "shareable-redacted",
			  "failureCategory": null
			}
			"""
            .formatted(RUN_ID, REX_ID);
    when(scratchStore.tryReadRunnerResult(REX_ID))
        .thenReturn(Optional.of(payload.getBytes(StandardCharsets.UTF_8)));
    when(scratchStore.tryReadArtifactContent(eq(REX_ID), eq("spec/v1.json")))
        .thenReturn(Optional.of("resumed-payload".getBytes(StandardCharsets.UTF_8)));
    when(artifactOperationService.recordOperation(any()))
        .thenAnswer(
            invocation -> {
              RecordArtifactOperationCommand command = invocation.getArgument(0);
              ArtifactRecordSnapshot artifact =
                  ArtifactRecordSnapshot.withoutFailureMetadata(
                      "art_resume0123456",
                      command.workflowRunId(),
                      command.artifactType(),
                      1,
                      null,
                      org.dradgo.domain.registry.DataClassification.SHAREABLE_REDACTED,
                      null,
                      null,
                      null,
                      org.dradgo.domain.registry.ArtifactStatus.PENDING,
                      null);
              ArtifactOperationSnapshot op =
                  new ArtifactOperationSnapshot(
                      "op_resume0123456",
                      command.workflowRunId(),
                      "art_resume0123456",
                      command.operationType().value(),
                      org.dradgo.domain.registry.ArtifactOperationStatus.PENDING,
                      command.idempotencyKey(),
                      null,
                      null,
                      OffsetDateTime.now(CLOCK));
              return new RecordArtifactOperationResult(artifact, op);
            });

    int handled = broker.recoverOnStartup();

    assertEquals(1, handled);
    verify(executionService).recordCompleted(REX_ID);
    verify(executionService, never()).recordOrphaned(any());
  }

  @Test
  void onResultOversizedPayloadIsRejectedAtValidatorBoundary() {
    when(recordPort.findByPublicId(REX_ID))
        .thenReturn(Optional.of(snapshot(REX_ID, RunnerExecutionStatus.RUNNING)));

    String payload =
        """
			{
			  "schemaVersion": 1,
			  "workflowRunId": "%s",
			  "runnerExecutionId": "%s",
			  "artifactReferences": [
			    {"artifactId": "art_big0123456789", "artifactType": "spec", "contentReference": "spec/v1.json"}
			  ],
			  "normalizedOutput": {"summary": "%s", "outcome": "success"},
			  "checksum": {"algorithm": "SHA-256", "hexDigest": "0000000000000000000000000000000000000000000000000000000000000001"},
			  "classification": "shareable-redacted",
			  "failureCategory": null
			}
			"""
            .formatted(RUN_ID, REX_ID, "x".repeat(2500));

    broker.onResult(REX_ID, payload.getBytes(StandardCharsets.UTF_8));

    verify(executionService).recordFailed(REX_ID, FailureCategory.RUNNER_CONTRACT_VIOLATION);
    verify(artifactOperationService, never()).recordOperation(any());
  }

  @Test
  void recoverOnStartupOrphansOversizedResultPayloadInsteadOfResuming() {
    RunnerExecutionSnapshot active = snapshot(REX_ID, RunnerExecutionStatus.RUNNING);
    when(recordPort.findActiveStatuses(any(), anyInt())).thenReturn(List.of(active));
    String payload =
        """
			{
			  "schemaVersion": 1,
			  "workflowRunId": "%s",
			  "runnerExecutionId": "%s",
			  "artifactReferences": [
			    {"artifactId": "art_resume0123456", "artifactType": "spec", "contentReference": "spec/v1.json"}
			  ],
			  "normalizedOutput": {"summary": "%s", "outcome": "success"},
			  "checksum": {"algorithm": "SHA-256", "hexDigest": "0000000000000000000000000000000000000000000000000000000000000001"},
			  "classification": "shareable-redacted",
			  "failureCategory": null
			}
			"""
            .formatted(RUN_ID, REX_ID, "x".repeat(2500));
    when(scratchStore.tryReadRunnerResult(REX_ID))
        .thenReturn(Optional.of(payload.getBytes(StandardCharsets.UTF_8)));

    int handled = broker.recoverOnStartup();

    assertEquals(1, handled);
    verify(executionService).recordOrphaned(REX_ID);
    verify(executionService, never()).recordCompleted(any());
  }

  @Test
  void onResultPropagatesNonDuplicateRecordCompletedDomainFailure() {
    RunnerExecutionSnapshot active = snapshot(REX_ID, RunnerExecutionStatus.RUNNING);
    when(recordPort.findByPublicId(REX_ID)).thenReturn(Optional.of(active));
    when(scratchStore.tryReadArtifactContent(eq(REX_ID), eq("spec/v1.json")))
        .thenReturn(Optional.of("spec-bytes".getBytes(StandardCharsets.UTF_8)));
    when(artifactOperationService.recordOperation(any()))
        .thenAnswer(
            invocation -> {
              RecordArtifactOperationCommand command = invocation.getArgument(0);
              ArtifactRecordSnapshot artifact =
                  ArtifactRecordSnapshot.withoutFailureMetadata(
                      "art_propagate0001",
                      command.workflowRunId(),
                      command.artifactType(),
                      1,
                      null,
                      org.dradgo.domain.registry.DataClassification.SHAREABLE_REDACTED,
                      null,
                      null,
                      null,
                      org.dradgo.domain.registry.ArtifactStatus.PENDING,
                      null);
              ArtifactOperationSnapshot op =
                  new ArtifactOperationSnapshot(
                      "op_propagate0001",
                      command.workflowRunId(),
                      "art_propagate0001",
                      command.operationType().value(),
                      org.dradgo.domain.registry.ArtifactOperationStatus.PENDING,
                      command.idempotencyKey(),
                      null,
                      null,
                      OffsetDateTime.now(CLOCK));
              return new RecordArtifactOperationResult(artifact, op);
            });
    when(executionService.recordCompleted(REX_ID))
        .thenThrow(
            new DomainException(
                DomainErrorCode.RUNNER_EXECUTION_NOT_FOUND,
                "runner execution disappeared during completion"));

    String payload =
        """
        {
          "schemaVersion": 1,
          "workflowRunId": "%s",
          "runnerExecutionId": "%s",
          "artifactReferences": [
            {"artifactId": "art_propagate0001", "artifactType": "spec", "contentReference": "spec/v1.json"}
          ],
          "normalizedOutput": {"summary": "ok", "outcome": "success"},
          "checksum": {"algorithm": "SHA-256", "hexDigest": "0000000000000000000000000000000000000000000000000000000000000001"},
          "classification": "shareable-redacted",
          "failureCategory": null
        }
        """
            .formatted(RUN_ID, REX_ID);

    DomainException thrown =
        assertThrows(
            DomainException.class,
            () -> broker.onResult(REX_ID, payload.getBytes(StandardCharsets.UTF_8)));

    assertEquals(DomainErrorCode.RUNNER_EXECUTION_NOT_FOUND, thrown.errorCode());
    verify(eventPort, never())
        .append(any(), eq(WorkflowEventType.RUNNER_COMPLETED), any(), any(), any(), any(), any());
  }

  @Test
  void dockerDispatchedEventRedactsCredentialBearingImageTag() {
    RecoverableRunnerAdapter dockerAdapter = mock(RecoverableRunnerAdapter.class);
    runnerAdapter = dockerAdapter;
    RunnerProperties.Docker dockerConfig =
        new RunnerProperties.Docker(
            org.dradgo.domain.registry.RunnerKind.CODEX,
            java.util.Map.of(
                org.dradgo.domain.registry.RunnerKind.CODEX,
                "user:secret@registry.example.test/deliveryline/codex:latest",
                org.dradgo.domain.registry.RunnerKind.CLAUDE,
                "deliveryline/claude-runner:latest"),
            java.nio.file.Path.of("runner-work"),
            24L,
            3_600_000L,
            java.time.Duration.ofSeconds(30L),
            java.time.Duration.ofSeconds(30L),
            120L,
            "none");
    runnerProperties =
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
            dockerConfig,
            RunnerProperties.defaultSecretEnvNames(),
            false,
            RunnerProperties.SpecStage.defaults(),
            RunnerProperties.PlanStage.defaults(),
            RunnerProperties.ImplementationStage.defaults(),
            RunnerProperties.OpenSpec.defaults(),
            100);
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
            runnerProperties,
            secretScanService,
            callthroughTemplate(),
            callthroughTemplate(),
            CLOCK);
    when(dockerAdapter.emitsDispatchedAfterAck()).thenReturn(true);
    when(dockerAdapter.findContainerIdFor(any())).thenReturn(Optional.of("container_redact"));
    when(dockerAdapter.dispatch(any()))
        .thenReturn(new RunnerDispatchAck("docker:container_redact"));
    when(recordPort.nextContextBundleVersion(RUN_ID, RunnerStage.INVESTIGATION)).thenReturn(1);
    when(idempotencyService.checkAndReserve(any(), any(), any(), any()))
        .thenReturn(new ReservationOutcome(ReservationDecision.RESERVED, null));
    when(contextBundleService.createForSpecInvestigation(
            any(), any(), anyInt(), any(), any(), any(), any()))
        .thenReturn(
            new ContextBundle(
                RUN_ID,
                RunnerStage.INVESTIGATION,
                REX_ID,
                1,
                org.dradgo.domain.registry.DataClassification.SHAREABLE_REDACTED,
                "{}".getBytes(StandardCharsets.UTF_8)));
    when(recordPort.insertPending(any(), any(), any(), anyInt(), any()))
        .thenAnswer(
            invocation -> snapshot(invocation.getArgument(0), RunnerExecutionStatus.PENDING));
    when(scratchStore.writeContextBundle(any(), any()))
        .thenReturn(Paths.get("/tmp/context-bundle.v1.json"));
    when(eventPort.append(any(), any(), any(), any(), any(), any(), any()))
        .thenReturn("evt_dispatched001");

    broker.dispatch(RUN_ID, RunnerStage.INVESTIGATION, "idem-redact", ACTOR);

    ArgumentCaptor<java.util.Map<String, Object>> detailsCaptor =
        ArgumentCaptor.forClass(java.util.Map.class);
    verify(eventPort)
        .append(
            eq(RUN_ID),
            eq(WorkflowEventType.RUNNER_DISPATCHED),
            eq(ACTOR),
            any(),
            any(),
            any(),
            detailsCaptor.capture());
    assertEquals(
        "***@registry.example.test/deliveryline/codex:latest",
        detailsCaptor.getValue().get("image"));
  }

  // ===== Story 3a-1 — success auto-advance delegation + artifact-type guard =====

  @Test
  void specSuccessDelegatesSpecReadyToOrchestrationForInvestigationStage() {
    org.dradgo.application.workflow.WorkflowOrchestrationService orchestration =
        mock(org.dradgo.application.workflow.WorkflowOrchestrationService.class);
    RunnerBroker orchBroker = brokerWithOrchestration(orchestration);
    when(recordPort.findByPublicId(REX_ID))
        .thenReturn(Optional.of(snapshot(REX_ID, RunnerExecutionStatus.RUNNING)));
    when(scratchStore.tryReadArtifactContent(eq(REX_ID), eq("spec/v1.json")))
        .thenReturn(Optional.of("spec-bytes".getBytes(StandardCharsets.UTF_8)));
    stubArtifactRecordSuccess();

    orchBroker.onResult(REX_ID, specResultPayload("spec").getBytes(StandardCharsets.UTF_8));

    verify(executionService).recordCompleted(REX_ID);
    // AC2/AC3: the broker delegates the spec-ready auto-advance to orchestration after completion.
    verify(orchestration)
        .onSpecStageSucceeded(eq(RUN_ID), eq(REX_ID), org.mockito.ArgumentMatchers.anyString());
  }

  @Test
  void artifactTypeMismatchRoutesToFailedAndDoesNotDelegateSuccess() {
    org.dradgo.application.workflow.WorkflowOrchestrationService orchestration =
        mock(org.dradgo.application.workflow.WorkflowOrchestrationService.class);
    RunnerBroker orchBroker = brokerWithOrchestration(orchestration);
    when(recordPort.findByPublicId(REX_ID))
        .thenReturn(Optional.of(snapshot(REX_ID, RunnerExecutionStatus.RUNNING)));

    // AC8: an INVESTIGATION-stage runner emitting an implementationPlan is a contract violation.
    orchBroker.onResult(
        REX_ID, specResultPayload("implementationPlan").getBytes(StandardCharsets.UTF_8));

    verify(executionService).recordFailed(REX_ID, FailureCategory.RUNNER_CONTRACT_VIOLATION);
    verify(executionService, never()).recordCompleted(any());
    verify(artifactOperationService, never()).recordOperation(any());
    verify(orchestration, never()).onSpecStageSucceeded(any(), any(), any());
    verify(workflowTransitionService)
        .transition(
            eq(RUN_ID),
            eq(WorkflowState.FAILED),
            any(),
            any(),
            any(),
            eq(FailureCategory.RUNNER_CONTRACT_VIOLATION),
            any());
  }

  // ===== Story 3.11 — EXECUTION (plan) success auto-advance delegation =====

  @Test
  void planSuccessDelegatesPlanReadyToOrchestrationForExecutionPlanSubStage() {
    org.dradgo.application.workflow.WorkflowOrchestrationService orchestration =
        mock(org.dradgo.application.workflow.WorkflowOrchestrationService.class);
    RunnerBroker orchBroker = brokerWithOrchestration(orchestration);
    when(recordPort.findByPublicId(REX_ID))
        .thenReturn(Optional.of(executionSnapshot(REX_ID, RunnerExecutionStatus.RUNNING)));
    when(contextBundleService.deriveExecutionSubStage(RUN_ID))
        .thenReturn(ExecutionSubStage.IMPLEMENTATION_PLAN);
    stubArtifactRecordSuccess();

    orchBroker.onResult(REX_ID, implementationPlanResultPayload().getBytes(StandardCharsets.UTF_8));

    verify(executionService).recordCompleted(REX_ID);
    // AC2/AC3: the broker delegates the plan-ready auto-advance to orchestration after completion.
    verify(orchestration)
        .onPlanStageSucceeded(eq(RUN_ID), eq(REX_ID), org.mockito.ArgumentMatchers.anyString());
    verify(orchestration, never()).onSpecStageSucceeded(any(), any(), any());
  }

  // ===== Story 3.12 — EXECUTION (pr-output) success auto-advance + ref validation/enrichment =====

  @Test
  void prOutputSuccessDelegatesPrOutputReadyToOrchestrationForExecutionPrOutputSubStage() {
    // Story 3.12 (AC5) — the pr-output twin of the plan delegation: the broker ingests + completes
    // then delegates the pr-output-ready auto-advance (Executing -> WaitingForReview). No repo
    // workspace here (captureAndPush returns empty), so AC9 format validation passes on the valid
    // reported refs and there is nothing to drift-check or enrich.
    org.dradgo.application.workflow.WorkflowOrchestrationService orchestration =
        mock(org.dradgo.application.workflow.WorkflowOrchestrationService.class);
    RunnerBroker orchBroker = brokerWithOrchestration(orchestration);
    when(recordPort.findByPublicId(REX_ID))
        .thenReturn(Optional.of(executionSnapshot(REX_ID, RunnerExecutionStatus.RUNNING)));
    when(contextBundleService.deriveExecutionSubStage(RUN_ID))
        .thenReturn(ExecutionSubStage.PR_OUTPUT);
    stubArtifactRecordSuccess();

    orchBroker.onResult(REX_ID, prOutputResultPayload().getBytes(StandardCharsets.UTF_8));

    verify(executionService).recordCompleted(REX_ID);
    verify(orchestration)
        .onPrOutputStageSucceeded(eq(RUN_ID), eq(REX_ID), org.mockito.ArgumentMatchers.anyString());
    verify(orchestration, never()).onPlanStageSucceeded(any(), any(), any());
  }

  @Test
  void prOutputSuccessWithPushOutcomeLinksGitHubPrFromAuthoritativeRefs() {
    // Story 3.15 (Task 7) — when captureAndPush produced a PR, the broker writes the durable
    // github_pr linkage from the AUTHORITATIVE refs. Reported refs equal the actual outcome here so
    // AC9 format + AC3 drift both pass and the seam is reached.
    org.dradgo.application.workflow.WorkflowOrchestrationService orchestration =
        mock(org.dradgo.application.workflow.WorkflowOrchestrationService.class);
    RepositoryWorkspaceService repoService = mock(RepositoryWorkspaceService.class);
    org.dradgo.application.integration.IntegrationLinkService linkService =
        mock(org.dradgo.application.integration.IntegrationLinkService.class);
    RunnerBroker broker = brokerWithRepoAndLink(orchestration, repoService, linkService);

    String branch = "feature/x";
    String commitSha = "abcdef1234567890abcdef1234567890abcdef12";
    String prRef = "PR-1";
    when(recordPort.findByPublicId(REX_ID))
        .thenReturn(Optional.of(executionSnapshot(REX_ID, RunnerExecutionStatus.RUNNING)));
    when(contextBundleService.deriveExecutionSubStage(RUN_ID))
        .thenReturn(ExecutionSubStage.PR_OUTPUT);
    stubArtifactRecordSuccess();
    when(repoService.captureAndPush(REX_ID))
        .thenReturn(
            Optional.of(
                new RepositoryWorkspaceService.RepositoryPushOutcome(
                    commitSha, branch, prRef, true)));

    broker.onResult(
        REX_ID, prOutputResultPayload(branch, commitSha, prRef).getBytes(StandardCharsets.UTF_8));

    verify(executionService).recordCompleted(REX_ID);
    verify(linkService)
        .linkGitHubPr(
            eq(RUN_ID),
            eq(prRef),
            org.mockito.ArgumentMatchers.isNull(),
            eq(branch),
            eq(commitSha),
            any(org.dradgo.application.artifact.ActorContext.class),
            org.mockito.ArgumentMatchers.anyString());
  }

  @Test
  void prOutputLinkageFailureIsSwallowedAndDoesNotBlockCompletion() {
    // Story 3.15 (Task 7) — a linkage-only failure (e.g. cross-run conflict) must not unwind the
    // committed runner outcome or block the WaitingForReview advance (best-effort + swallow).
    org.dradgo.application.workflow.WorkflowOrchestrationService orchestration =
        mock(org.dradgo.application.workflow.WorkflowOrchestrationService.class);
    RepositoryWorkspaceService repoService = mock(RepositoryWorkspaceService.class);
    org.dradgo.application.integration.IntegrationLinkService linkService =
        mock(org.dradgo.application.integration.IntegrationLinkService.class);
    RunnerBroker broker = brokerWithRepoAndLink(orchestration, repoService, linkService);

    String branch = "feature/x";
    String commitSha = "abcdef1234567890abcdef1234567890abcdef12";
    String prRef = "PR-1";
    when(recordPort.findByPublicId(REX_ID))
        .thenReturn(Optional.of(executionSnapshot(REX_ID, RunnerExecutionStatus.RUNNING)));
    when(contextBundleService.deriveExecutionSubStage(RUN_ID))
        .thenReturn(ExecutionSubStage.PR_OUTPUT);
    stubArtifactRecordSuccess();
    when(repoService.captureAndPush(REX_ID))
        .thenReturn(
            Optional.of(
                new RepositoryWorkspaceService.RepositoryPushOutcome(
                    commitSha, branch, prRef, true)));
    org.mockito.Mockito.doThrow(
            new org.dradgo.domain.DomainException(
                org.dradgo.domain.registry.DomainErrorCode.INTEGRATION_LINK_CONFLICT, "conflict"))
        .when(linkService)
        .linkGitHubPr(any(), any(), any(), any(), any(), any(), any());

    broker.onResult(
        REX_ID, prOutputResultPayload(branch, commitSha, prRef).getBytes(StandardCharsets.UTF_8));

    // Completion + auto-advance still happen despite the linkage failure.
    verify(executionService).recordCompleted(REX_ID);
    verify(orchestration)
        .onPrOutputStageSucceeded(eq(RUN_ID), eq(REX_ID), org.mockito.ArgumentMatchers.anyString());
  }

  @Test
  void prOutputMalformedReportedRefRoutesToFailedAndDoesNotDelegate() {
    // Story 3.12 (AC9) — an untrusted pr-output runner reporting a malformed prReference (here the
    // GitHub-shorthand "org/repo#n" form, which passes the wire schema but is NOT the documented
    // PR-<n> / canonical-URL format) raises RUNNER_OUTPUT_VALIDATION_FAILED and routes to Failed
    // via
    // the contract-violation path — never completing or delegating the success advance.
    org.dradgo.application.workflow.WorkflowOrchestrationService orchestration =
        mock(org.dradgo.application.workflow.WorkflowOrchestrationService.class);
    RunnerBroker orchBroker = brokerWithOrchestration(orchestration);
    when(recordPort.findByPublicId(REX_ID))
        .thenReturn(Optional.of(executionSnapshot(REX_ID, RunnerExecutionStatus.RUNNING)));
    when(contextBundleService.deriveExecutionSubStage(RUN_ID))
        .thenReturn(ExecutionSubStage.PR_OUTPUT);
    stubArtifactRecordSuccess();

    orchBroker.onResult(
        REX_ID,
        prOutputResultPayload(
                "feature/x", "abcdef1234567890abcdef1234567890abcdef12", "mock-org/mock-repo#1")
            .getBytes(StandardCharsets.UTF_8));

    verify(executionService).recordFailed(REX_ID, FailureCategory.RUNNER_CONTRACT_VIOLATION);
    verify(executionService, never()).recordCompleted(any());
    verify(orchestration, never()).onPrOutputStageSucceeded(any(), any(), any());
    ArgumentCaptor<java.util.Map<String, Object>> details =
        ArgumentCaptor.forClass(java.util.Map.class);
    verify(eventPort)
        .append(
            eq(RUN_ID),
            eq(WorkflowEventType.RUNNER_FAILED),
            any(),
            eq("runner_output_validation_failed"),
            eq(FailureCategory.RUNNER_CONTRACT_VIOLATION),
            any(),
            details.capture());
    assertEquals(
        DomainErrorCode.RUNNER_OUTPUT_VALIDATION_FAILED.value(),
        details.getValue().get("errorCode"));
    verify(workflowTransitionService)
        .transition(
            eq(RUN_ID),
            eq(WorkflowState.FAILED),
            any(),
            any(),
            any(),
            eq(FailureCategory.RUNNER_CONTRACT_VIOLATION),
            any());
  }

  @Test
  void prOutputRefDriftFromActualGitStateRoutesToFailedAndDoesNotDelegate() {
    // Story 3.12 (AC3) — the runner-reported branch disagrees with the actual captureAndPush
    // branch:
    // RUNNER_PR_REF_DRIFT routes to Failed and never delegates the success advance.
    org.dradgo.application.workflow.WorkflowOrchestrationService orchestration =
        mock(org.dradgo.application.workflow.WorkflowOrchestrationService.class);
    RepositoryWorkspaceService repoService = mock(RepositoryWorkspaceService.class);
    when(repoService.captureAndPush(REX_ID))
        .thenReturn(
            Optional.of(
                new RepositoryWorkspaceService.RepositoryPushOutcome(
                    "abcdef1234567890abcdef1234567890abcdef12", "actual-branch", "PR-1", true)));
    RunnerBroker orchBroker = brokerWithOrchestrationAndRepo(orchestration, repoService);
    when(recordPort.findByPublicId(REX_ID))
        .thenReturn(Optional.of(executionSnapshot(REX_ID, RunnerExecutionStatus.RUNNING)));
    when(contextBundleService.deriveExecutionSubStage(RUN_ID))
        .thenReturn(ExecutionSubStage.PR_OUTPUT);
    stubArtifactRecordSuccess();

    // Reported branch "feature/x" drifts from the actual "actual-branch".
    orchBroker.onResult(REX_ID, prOutputResultPayload().getBytes(StandardCharsets.UTF_8));

    verify(executionService).recordFailed(REX_ID, FailureCategory.RUNNER_CONTRACT_VIOLATION);
    verify(executionService, never()).recordCompleted(any());
    verify(orchestration, never()).onPrOutputStageSucceeded(any(), any(), any());
    ArgumentCaptor<java.util.Map<String, Object>> details =
        ArgumentCaptor.forClass(java.util.Map.class);
    verify(eventPort)
        .append(
            eq(RUN_ID),
            eq(WorkflowEventType.RUNNER_FAILED),
            any(),
            eq("runner_pr_ref_drift"),
            eq(FailureCategory.RUNNER_CONTRACT_VIOLATION),
            any(),
            details.capture());
    assertEquals(DomainErrorCode.RUNNER_PR_REF_DRIFT.value(), details.getValue().get("errorCode"));
  }

  @Test
  void prOutputSuccessWithMatchingPushOutcomeEnrichesArtifactAndDelegates() {
    // Story 3.12 (AC3 / Decision D3) — captureAndPush returns an outcome that matches the reported
    // refs (no drift): the broker enriches the ingested prOutput artifact via a follow-on UPDATE
    // carrying the actual refs, then delegates the pr-output-ready advance.
    org.dradgo.application.workflow.WorkflowOrchestrationService orchestration =
        mock(org.dradgo.application.workflow.WorkflowOrchestrationService.class);
    RepositoryWorkspaceService repoService = mock(RepositoryWorkspaceService.class);
    when(repoService.captureAndPush(REX_ID))
        .thenReturn(
            Optional.of(
                new RepositoryWorkspaceService.RepositoryPushOutcome(
                    "abcdef1234567890abcdef1234567890abcdef12", "feature/x", "PR-1", true)));
    RunnerBroker orchBroker = brokerWithOrchestrationAndRepo(orchestration, repoService);
    when(recordPort.findByPublicId(REX_ID))
        .thenReturn(Optional.of(executionSnapshot(REX_ID, RunnerExecutionStatus.RUNNING)));
    when(contextBundleService.deriveExecutionSubStage(RUN_ID))
        .thenReturn(ExecutionSubStage.PR_OUTPUT);
    stubArtifactRecordSuccess();

    orchBroker.onResult(REX_ID, prOutputResultPayload().getBytes(StandardCharsets.UTF_8));

    verify(executionService).recordCompleted(REX_ID);
    verify(orchestration)
        .onPrOutputStageSucceeded(eq(RUN_ID), eq(REX_ID), org.mockito.ArgumentMatchers.anyString());
    // Two recordOperation calls: the CREATE ingest + the enrichment UPDATE carrying the actual
    // refs.
    ArgumentCaptor<RecordArtifactOperationCommand> commands =
        ArgumentCaptor.forClass(RecordArtifactOperationCommand.class);
    verify(artifactOperationService, times(2)).recordOperation(commands.capture());
    RecordArtifactOperationCommand update =
        commands.getAllValues().stream()
            .filter(
                c -> c.operationType() == org.dradgo.domain.registry.ArtifactOperationType.UPDATE)
            .findFirst()
            .orElseThrow(() -> new AssertionError("expected an enrichment UPDATE operation"));
    String enriched = new String(update.payloadContent(), StandardCharsets.UTF_8);
    assertTrue(
        enriched.contains("feature/x"), () -> "enriched payload missing branch: " + enriched);
    assertTrue(
        enriched.contains("PR-1"), () -> "enriched payload missing prReference: " + enriched);
  }

  // ===== Story 3b-3 — ingest-stage availability marking generalized to plan / pr-output =====

  @Test
  void implementationPlanIngestMarksArtifactAvailable() {
    // Story 3b-3 (AC1): the freshly-ingested implementationPlan artifact is promoted to `available`
    // on ingest (checksum over the payload bytes + storageRef from the op result), so the
    // execution-stage acceptImplementation gate (isApprovalEligible = AVAILABLE) can fire.
    org.dradgo.application.workflow.WorkflowOrchestrationService orchestration =
        mock(org.dradgo.application.workflow.WorkflowOrchestrationService.class);
    RunnerBroker orchBroker = brokerWithOrchestration(orchestration);
    when(recordPort.findByPublicId(REX_ID))
        .thenReturn(Optional.of(executionSnapshot(REX_ID, RunnerExecutionStatus.RUNNING)));
    when(contextBundleService.deriveExecutionSubStage(RUN_ID))
        .thenReturn(ExecutionSubStage.IMPLEMENTATION_PLAN);
    stubArtifactRecordSuccessAvailable();

    orchBroker.onResult(REX_ID, implementationPlanResultPayload().getBytes(StandardCharsets.UTF_8));

    verify(executionService).recordCompleted(REX_ID);
    ArgumentCaptor<RecordArtifactOperationCommand> command =
        ArgumentCaptor.forClass(RecordArtifactOperationCommand.class);
    verify(artifactOperationService).recordOperation(command.capture());
    ArgumentCaptor<org.dradgo.application.artifact.ArtifactChecksum> checksum =
        ArgumentCaptor.forClass(org.dradgo.application.artifact.ArtifactChecksum.class);
    verify(artifactOperationService)
        .markAvailable(
            eq("art_test01234567"), checksum.capture(), eq("store/implementationPlan.json"), any());
    assertEquals("SHA-256", checksum.getValue().algorithm());
    // Story 3b-3 review patch — pin the checksum to the digest of the SAME bytes handed to
    // recordOperation (not just the algorithm), so a regression stamping a constant/wrong-payload
    // digest is caught.
    assertEquals(
        org.dradgo.application.artifact.ArtifactChecksum.digestHex(
                "SHA-256", command.getValue().payloadContent())
            .orElseThrow(),
        checksum.getValue().value());
  }

  @Test
  void prOutputIngestMarksArtifactAvailableWhenNoPushOutcome() {
    // Story 3b-3 (AC1): the no-push prOutput path (the mock runner — captureAndPush empty, no
    // enrich) promotes the v1 ingested artifact to `available` directly in the ingest loop.
    org.dradgo.application.workflow.WorkflowOrchestrationService orchestration =
        mock(org.dradgo.application.workflow.WorkflowOrchestrationService.class);
    RunnerBroker orchBroker = brokerWithOrchestration(orchestration);
    when(recordPort.findByPublicId(REX_ID))
        .thenReturn(Optional.of(executionSnapshot(REX_ID, RunnerExecutionStatus.RUNNING)));
    when(contextBundleService.deriveExecutionSubStage(RUN_ID))
        .thenReturn(ExecutionSubStage.PR_OUTPUT);
    stubArtifactRecordSuccessAvailable();

    orchBroker.onResult(REX_ID, prOutputResultPayload().getBytes(StandardCharsets.UTF_8));

    verify(executionService).recordCompleted(REX_ID);
    verify(artifactOperationService)
        .markAvailable(eq("art_test01234567"), any(), eq("store/prOutput.json"), any());
  }

  @Test
  void prOutputEnrichedHeadIsTheArtifactMarkedAvailable() {
    // Story 3b-3 (AC2 — THE TRAP): a real push enriches the ingested prOutput via a follow-on
    // UPDATE that createNextVersions a NEW PENDING v2 — the highest-version artifact the frontend
    // resolveImplementationArtifact selects. The broker must mark THAT enriched head available, not
    // only the v1 created in the ingest loop (a v1-only mark leaves the reviewer's resolved target
    // PENDING → acceptImplementation fails ARTIFACT_PAYLOAD_UNAVAILABLE). Reported refs match the
    // push outcome (no drift) so the enrich path is reached.
    org.dradgo.application.workflow.WorkflowOrchestrationService orchestration =
        mock(org.dradgo.application.workflow.WorkflowOrchestrationService.class);
    RepositoryWorkspaceService repoService = mock(RepositoryWorkspaceService.class);
    when(repoService.captureAndPush(REX_ID))
        .thenReturn(
            Optional.of(
                new RepositoryWorkspaceService.RepositoryPushOutcome(
                    "abcdef1234567890abcdef1234567890abcdef12", "feature/x", "PR-1", true)));
    RunnerBroker orchBroker = brokerWithOrchestrationAndRepo(orchestration, repoService);
    when(recordPort.findByPublicId(REX_ID))
        .thenReturn(Optional.of(executionSnapshot(REX_ID, RunnerExecutionStatus.RUNNING)));
    when(contextBundleService.deriveExecutionSubStage(RUN_ID))
        .thenReturn(ExecutionSubStage.PR_OUTPUT);
    stubArtifactRecordSuccessEnrichedHead();

    orchBroker.onResult(REX_ID, prOutputResultPayload().getBytes(StandardCharsets.UTF_8));

    // The enriched v2 head — the resolver's target — is marked available.
    ArgumentCaptor<RecordArtifactOperationCommand> commands =
        ArgumentCaptor.forClass(RecordArtifactOperationCommand.class);
    verify(artifactOperationService, times(2)).recordOperation(commands.capture());
    ArgumentCaptor<org.dradgo.application.artifact.ArtifactChecksum> v2Checksum =
        ArgumentCaptor.forClass(org.dradgo.application.artifact.ArtifactChecksum.class);
    verify(artifactOperationService)
        .markAvailable(
            eq("art_prv2_000002"), v2Checksum.capture(), eq("store/prOutput-v2.json"), any());
    // Story 3b-3 review patch — the v2 mark must digest the ENRICHED bytes (the UPDATE command's
    // payload), not v1's: pin it so a bug marking v2 with v1's checksum is caught.
    RecordArtifactOperationCommand enrichCommand =
        commands.getAllValues().stream()
            .filter(
                c -> c.operationType() == org.dradgo.domain.registry.ArtifactOperationType.UPDATE)
            .findFirst()
            .orElseThrow();
    assertEquals(
        org.dradgo.application.artifact.ArtifactChecksum.digestHex(
                "SHA-256", enrichCommand.payloadContent())
            .orElseThrow(),
        v2Checksum.getValue().value());
    // The v1 created in the ingest loop is also promoted (harmless; the resolver picks the
    // highest).
    verify(artifactOperationService)
        .markAvailable(eq("art_prv1_000001"), any(), eq("store/prOutput-v1.json"), any());
  }

  // ===== Story 3b-5 — resolve + cap + embed the prOutput diff at ingest (both write sites) =====

  private static final String PR_OUTPUT_DIFF_REFERENCE = "diffs/" + RUN_ID + "/pr-1.diff";

  @Test
  void prOutputIngestEmbedsResolvedDiffInPayloadWhenScratchPresent() {
    // Story 3b-5 (AC1) — the no-push (mock-runner) path: the diff resolved from scratch is embedded
    // in the in-loop v1 CREATE payload (enrich is skipped, so this is the only write — and the only
    // diff coverage ITs get).
    org.dradgo.application.workflow.WorkflowOrchestrationService orchestration =
        mock(org.dradgo.application.workflow.WorkflowOrchestrationService.class);
    RunnerBroker orchBroker = brokerWithOrchestration(orchestration);
    when(recordPort.findByPublicId(REX_ID))
        .thenReturn(Optional.of(executionSnapshot(REX_ID, RunnerExecutionStatus.RUNNING)));
    when(contextBundleService.deriveExecutionSubStage(RUN_ID))
        .thenReturn(ExecutionSubStage.PR_OUTPUT);
    when(scratchStore.tryReadArtifactContent(eq(REX_ID), eq(PR_OUTPUT_DIFF_REFERENCE)))
        .thenReturn(Optional.of("diff --git a/x b/x\n+hello\n".getBytes(StandardCharsets.UTF_8)));
    stubArtifactRecordSuccess();

    orchBroker.onResult(REX_ID, prOutputResultPayload().getBytes(StandardCharsets.UTF_8));

    ArgumentCaptor<RecordArtifactOperationCommand> command =
        ArgumentCaptor.forClass(RecordArtifactOperationCommand.class);
    verify(artifactOperationService).recordOperation(command.capture());
    String payload = new String(command.getValue().payloadContent(), StandardCharsets.UTF_8);
    assertTrue(payload.contains("\"diff\""), () -> "v1 payload missing diff field: " + payload);
    assertTrue(
        payload.contains("diff --git a/x b/x"),
        () -> "v1 payload missing diff content: " + payload);
  }

  @Test
  void prOutputEnrichedHeadCarriesResolvedDiff() {
    // Story 3b-5 (AC3 — the dual-write trap): a real push enriches the prOutput via a v2 UPDATE that
    // resolveImplementationArtifact selects as the highest version. The diff resolved at ingest must
    // ride that enriched head too, or the reviewer's resolved target loses it.
    org.dradgo.application.workflow.WorkflowOrchestrationService orchestration =
        mock(org.dradgo.application.workflow.WorkflowOrchestrationService.class);
    RepositoryWorkspaceService repoService = mock(RepositoryWorkspaceService.class);
    when(repoService.captureAndPush(REX_ID))
        .thenReturn(
            Optional.of(
                new RepositoryWorkspaceService.RepositoryPushOutcome(
                    "abcdef1234567890abcdef1234567890abcdef12", "feature/x", "PR-1", true)));
    RunnerBroker orchBroker = brokerWithOrchestrationAndRepo(orchestration, repoService);
    when(recordPort.findByPublicId(REX_ID))
        .thenReturn(Optional.of(executionSnapshot(REX_ID, RunnerExecutionStatus.RUNNING)));
    when(contextBundleService.deriveExecutionSubStage(RUN_ID))
        .thenReturn(ExecutionSubStage.PR_OUTPUT);
    when(scratchStore.tryReadArtifactContent(eq(REX_ID), eq(PR_OUTPUT_DIFF_REFERENCE)))
        .thenReturn(Optional.of("diff --git a/x b/x\n+hello\n".getBytes(StandardCharsets.UTF_8)));
    stubArtifactRecordSuccess();

    orchBroker.onResult(REX_ID, prOutputResultPayload().getBytes(StandardCharsets.UTF_8));

    ArgumentCaptor<RecordArtifactOperationCommand> commands =
        ArgumentCaptor.forClass(RecordArtifactOperationCommand.class);
    verify(artifactOperationService, times(2)).recordOperation(commands.capture());
    RecordArtifactOperationCommand update =
        commands.getAllValues().stream()
            .filter(
                c -> c.operationType() == org.dradgo.domain.registry.ArtifactOperationType.UPDATE)
            .findFirst()
            .orElseThrow(() -> new AssertionError("expected an enrichment UPDATE operation"));
    String enriched = new String(update.payloadContent(), StandardCharsets.UTF_8);
    assertTrue(enriched.contains("\"diff\""), () -> "v2 payload missing diff field: " + enriched);
    assertTrue(
        enriched.contains("diff --git a/x b/x"),
        () -> "v2 payload missing diff content: " + enriched);
  }

  @Test
  void prOutputIngestOmitsDiffWhenScratchAbsentWithoutFailingIngest() {
    // Story 3b-5 (AC1) — the OQ-1 reality: today's runners write only the diffReference pointer, not
    // the file, so the scratch read is empty. The diff field is omitted and ingest still succeeds
    // (the read path then degrades to an empty-diff panel).
    org.dradgo.application.workflow.WorkflowOrchestrationService orchestration =
        mock(org.dradgo.application.workflow.WorkflowOrchestrationService.class);
    RunnerBroker orchBroker = brokerWithOrchestration(orchestration);
    when(recordPort.findByPublicId(REX_ID))
        .thenReturn(Optional.of(executionSnapshot(REX_ID, RunnerExecutionStatus.RUNNING)));
    when(contextBundleService.deriveExecutionSubStage(RUN_ID))
        .thenReturn(ExecutionSubStage.PR_OUTPUT);
    // tryReadArtifactContent is NOT stubbed → Mockito returns Optional.empty() (absent scratch).
    stubArtifactRecordSuccess();

    orchBroker.onResult(REX_ID, prOutputResultPayload().getBytes(StandardCharsets.UTF_8));

    verify(executionService).recordCompleted(REX_ID);
    ArgumentCaptor<RecordArtifactOperationCommand> command =
        ArgumentCaptor.forClass(RecordArtifactOperationCommand.class);
    verify(artifactOperationService).recordOperation(command.capture());
    String payload = new String(command.getValue().payloadContent(), StandardCharsets.UTF_8);
    assertTrue(
        !payload.contains("\"diff\""),
        () -> "absent-scratch payload must not carry a diff field: " + payload);
  }

  @Test
  void prOutputOversizeDiffTruncatedAndWarnLogged() {
    // Story 3b-5 (AC2) — a diff exceeding the 5000-line storage cap is truncated with a marker line
    // and a WARN (no silent truncation). The frontend caps again at render.
    org.dradgo.application.workflow.WorkflowOrchestrationService orchestration =
        mock(org.dradgo.application.workflow.WorkflowOrchestrationService.class);
    RunnerBroker orchBroker = brokerWithOrchestration(orchestration);
    when(recordPort.findByPublicId(REX_ID))
        .thenReturn(Optional.of(executionSnapshot(REX_ID, RunnerExecutionStatus.RUNNING)));
    when(contextBundleService.deriveExecutionSubStage(RUN_ID))
        .thenReturn(ExecutionSubStage.PR_OUTPUT);
    String oversize = "x\n".repeat(6000); // 6000 lines > 5000-line cap
    when(scratchStore.tryReadArtifactContent(eq(REX_ID), eq(PR_OUTPUT_DIFF_REFERENCE)))
        .thenReturn(Optional.of(oversize.getBytes(StandardCharsets.UTF_8)));
    stubArtifactRecordSuccess();

    Logger brokerLog = (Logger) LoggerFactory.getLogger(RunnerBroker.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    brokerLog.addAppender(appender);
    try {
      orchBroker.onResult(REX_ID, prOutputResultPayload().getBytes(StandardCharsets.UTF_8));
    } finally {
      brokerLog.detachAppender(appender);
    }

    ArgumentCaptor<RecordArtifactOperationCommand> command =
        ArgumentCaptor.forClass(RecordArtifactOperationCommand.class);
    verify(artifactOperationService).recordOperation(command.capture());
    String payload = new String(command.getValue().payloadContent(), StandardCharsets.UTF_8);
    assertTrue(
        payload.contains("diff truncated by deliveryline storage cap"),
        () -> "oversize payload must carry the truncation marker: " + payload);
    assertTrue(
        appender.list.stream()
            .anyMatch(
                e ->
                    e.getLevel() == Level.WARN
                        && e.getFormattedMessage().contains("prOutput diff truncated")),
        "expected a WARN log for the diff truncation");
    // No diff CONTENT may appear in any log line — sizes/counts only.
    assertTrue(
        appender.list.stream().noneMatch(e -> e.getFormattedMessage().contains("\nx\nx")),
        "diff content must never be logged");
  }

  private static String implementationPlanResultPayload() {
    // Mirrors runner-result.v1.implementation-plan.valid.json (steps + contextReferences, NOT
    // contentReference — the broker's artifactPayload serializes the ref JSON in that case).
    return """
        {
          "schemaVersion": 1,
          "workflowRunId": "%s",
          "runnerExecutionId": "%s",
          "artifactReferences": [
            {"artifactId": "art_test01234567", "artifactType": "implementationPlan",
             "steps": ["Review approved spec", "Implement changes"],
             "contextReferences": ["spec/v1.json"]}
          ],
          "normalizedOutput": {"summary": "ok", "outcome": "success"},
          "checksum": {"algorithm": "SHA-256", "hexDigest": "0000000000000000000000000000000000000000000000000000000000000002"},
          "classification": "shareable-redacted",
          "failureCategory": null
        }
        """
        .formatted(RUN_ID, REX_ID);
  }

  private static String prOutputResultPayload() {
    // Mirrors runner-result.v1.pr-output.valid.json (branch/commitSha/prReference/diffReference).
    return prOutputResultPayload("feature/x", "abcdef1234567890abcdef1234567890abcdef12", "PR-1");
  }

  /** Story 3.12 — a pr-output result with caller-chosen reported refs (AC9/AC3 unit coverage). */
  private static String prOutputResultPayload(String branch, String commitSha, String prReference) {
    return """
        {
          "schemaVersion": 1,
          "workflowRunId": "%s",
          "runnerExecutionId": "%s",
          "artifactReferences": [
            {"artifactId": "art_test01234567", "artifactType": "prOutput",
             "branch": "%s", "commitSha": "%s",
             "prReference": "%s", "diffReference": "diffs/%s/pr-1.diff"}
          ],
          "normalizedOutput": {"summary": "ok", "outcome": "success"},
          "checksum": {"algorithm": "SHA-256", "hexDigest": "0000000000000000000000000000000000000000000000000000000000000003"},
          "classification": "shareable-redacted",
          "failureCategory": null
        }
        """
        .formatted(RUN_ID, REX_ID, branch, commitSha, prReference, RUN_ID);
  }

  private static RunnerExecutionSnapshot executionSnapshot(
      String publicId, RunnerExecutionStatus status) {
    OffsetDateTime now = OffsetDateTime.now(CLOCK);
    return new RunnerExecutionSnapshot(
        publicId,
        RUN_ID,
        RunnerStage.EXECUTION,
        status,
        1,
        now,
        now.plusSeconds(600),
        null,
        RunnerExecutionStateMachine.isTerminal(status) ? now : null,
        now,
        null);
  }

  private RunnerBroker brokerWithOrchestration(
      org.dradgo.application.workflow.WorkflowOrchestrationService orchestration) {
    return brokerWithOrchestrationAndRepo(orchestration, null);
  }

  private RunnerBroker brokerWithOrchestrationAndRepo(
      org.dradgo.application.workflow.WorkflowOrchestrationService orchestration,
      RepositoryWorkspaceService repositoryWorkspaceService) {
    return new RunnerBroker(
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
        runnerProperties,
        secretScanService,
        callthroughTemplate(),
        callthroughTemplate(),
        CLOCK,
        repositoryWorkspaceService,
        orchestration);
  }

  // Story 3.15 (Task 7) — full ctor wiring a repo seam + an IntegrationLinkService supplier so the
  // pr-output linkage seam (linkGitHubPrBestEffort) is reachable.
  private RunnerBroker brokerWithRepoAndLink(
      org.dradgo.application.workflow.WorkflowOrchestrationService orchestration,
      RepositoryWorkspaceService repositoryWorkspaceService,
      org.dradgo.application.integration.IntegrationLinkService linkService) {
    return new RunnerBroker(
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
        runnerProperties,
        secretScanService,
        callthroughTemplate(),
        callthroughTemplate(),
        CLOCK,
        repositoryWorkspaceService,
        () -> orchestration,
        null,
        () -> linkService);
  }

  private void stubArtifactRecordSuccess() {
    when(artifactOperationService.recordOperation(any()))
        .thenAnswer(
            invocation -> {
              RecordArtifactOperationCommand command = invocation.getArgument(0);
              ArtifactRecordSnapshot artifact =
                  ArtifactRecordSnapshot.withoutFailureMetadata(
                      "art_test01234567",
                      command.workflowRunId(),
                      command.artifactType(),
                      1,
                      null,
                      org.dradgo.domain.registry.DataClassification.SHAREABLE_REDACTED,
                      null,
                      null,
                      null,
                      org.dradgo.domain.registry.ArtifactStatus.PENDING,
                      null);
              ArtifactOperationSnapshot op =
                  new ArtifactOperationSnapshot(
                      "op_test01234567",
                      command.workflowRunId(),
                      "art_test01234567",
                      command.operationType().value(),
                      org.dradgo.domain.registry.ArtifactOperationStatus.PENDING,
                      command.idempotencyKey(),
                      null,
                      null,
                      OffsetDateTime.now(CLOCK));
              return new RecordArtifactOperationResult(artifact, op);
            });
  }

  /**
   * Story 3b-3 — like {@link #stubArtifactRecordSuccess()} but carries a non-null {@code
   * storageRef} ({@code store/<artifactType>.json}) so the broker's {@code markArtifactAvailable}
   * actually promotes the ingested artifact (the no-null-storageRef branch). The artifact public id
   * is constant ({@code art_test01234567}).
   */
  private void stubArtifactRecordSuccessAvailable() {
    when(artifactOperationService.recordOperation(any()))
        .thenAnswer(
            invocation -> {
              RecordArtifactOperationCommand command = invocation.getArgument(0);
              return recordResultWithStorageRef(
                  command, "art_test01234567", "store/" + command.artifactType().value() + ".json");
            });
  }

  /**
   * Story 3b-3 (AC2) — distinct ids/refs for the CREATE (ingest v1) vs the enrichment UPDATE
   * (createNextVersion v2) so the enriched-head test can prove {@code markAvailable} targets the v2
   * head, not the v1 created in the ingest loop. Both carry a non-null storageRef so both are
   * promoted.
   */
  private void stubArtifactRecordSuccessEnrichedHead() {
    when(artifactOperationService.recordOperation(any()))
        .thenAnswer(
            invocation -> {
              RecordArtifactOperationCommand command = invocation.getArgument(0);
              boolean isUpdate =
                  command.operationType()
                      == org.dradgo.domain.registry.ArtifactOperationType.UPDATE;
              String artifactPublicId = isUpdate ? "art_prv2_000002" : "art_prv1_000001";
              String storageRef = isUpdate ? "store/prOutput-v2.json" : "store/prOutput-v1.json";
              return recordResultWithStorageRef(command, artifactPublicId, storageRef);
            });
  }

  private static RecordArtifactOperationResult recordResultWithStorageRef(
      RecordArtifactOperationCommand command, String artifactPublicId, String storageRef) {
    ArtifactRecordSnapshot artifact =
        ArtifactRecordSnapshot.withoutFailureMetadata(
            artifactPublicId,
            command.workflowRunId(),
            command.artifactType(),
            1,
            null,
            org.dradgo.domain.registry.DataClassification.SHAREABLE_REDACTED,
            null,
            null,
            null,
            org.dradgo.domain.registry.ArtifactStatus.PENDING,
            null);
    ArtifactOperationSnapshot op =
        new ArtifactOperationSnapshot(
            "op_test01234567",
            command.workflowRunId(),
            artifactPublicId,
            command.operationType().value(),
            org.dradgo.domain.registry.ArtifactOperationStatus.PENDING,
            command.idempotencyKey(),
            null,
            null,
            OffsetDateTime.now(CLOCK));
    return new RecordArtifactOperationResult(artifact, op, storageRef);
  }

  private static String specResultPayload(String artifactType) {
    return """
        {
          "schemaVersion": 1,
          "workflowRunId": "%s",
          "runnerExecutionId": "%s",
          "artifactReferences": [
            {"artifactId": "art_test01234567", "artifactType": "%s", "contentReference": "spec/v1.json"}
          ],
          "normalizedOutput": {"summary": "ok", "outcome": "success"},
          "checksum": {"algorithm": "SHA-256", "hexDigest": "0000000000000000000000000000000000000000000000000000000000000001"},
          "classification": "shareable-redacted",
          "failureCategory": null
        }
        """
        .formatted(RUN_ID, REX_ID, artifactType);
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

  private static RunnerExecutionSnapshot snapshotWithRawOutput(
      String publicId, RunnerExecutionStatus status) {
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
        null,
        null,
        "/runner-logs/" + publicId,
        org.dradgo.domain.registry.DataClassification.LOCAL_ONLY,
        42L,
        2);
  }

  /**
   * Snapshot whose {@code timeoutAt} is strictly before {@link #CLOCK} now — eligible for timeout
   * flip.
   */
  private static RunnerExecutionSnapshot staleSnapshot(
      String publicId, RunnerExecutionStatus status) {
    OffsetDateTime now = OffsetDateTime.now(CLOCK);
    return new RunnerExecutionSnapshot(
        publicId,
        RUN_ID,
        RunnerStage.INVESTIGATION,
        status,
        1,
        now.minusSeconds(120),
        now.minusSeconds(1),
        null,
        null,
        now.minusSeconds(300),
        null);
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
}
