package org.dradgo.application.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import org.dradgo.application.workflow.WorkflowTransitionService;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.FailureCategory;
import org.dradgo.domain.registry.IdempotencyRecordStatus;
import org.dradgo.domain.registry.RunnerExecutionStatus;
import org.dradgo.domain.registry.RunnerStage;
import org.dradgo.domain.registry.WorkflowEventType;
import org.dradgo.domain.registry.WorkflowState;
import org.dradgo.runnercontracts.RunnerContractValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
    when(contextBundleService.create(
            eq(RUN_ID), eq(RunnerStage.INVESTIGATION), any(), eq(1), any(), any(), eq(ACTOR)))
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
    verify(runnerAdapter).dispatch(any());
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
    when(contextBundleService.create(any(), any(), any(), anyInt(), any(), any(), any()))
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
    when(recordPort.findByPublicId(REX_ID)).thenReturn(Optional.of(active));
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
            120L);
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
            dockerConfig);
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
    when(contextBundleService.create(any(), any(), any(), anyInt(), any(), any(), any()))
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
