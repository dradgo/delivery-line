package org.dradgo.application.runner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.dradgo.application.artifact.ActorContext;
import org.dradgo.application.idempotency.IdempotencyService;
import org.dradgo.application.idempotency.IdempotencyService.ReservationDecision;
import org.dradgo.application.idempotency.IdempotencyService.ReservationOutcome;
import org.dradgo.application.runner.spi.RunnerExecutionRecordPort;
import org.dradgo.application.runner.spi.RunnerExecutionSnapshot;
import org.dradgo.application.runner.spi.RunnerScratchStore;
import org.dradgo.application.workflow.ManualArtifactSubmissionService;
import org.dradgo.application.workflow.ManualArtifactSubmissionService.ManualArtifactSubmissionCommand;
import org.dradgo.application.workflow.WorkflowStateChangeResult;
import org.dradgo.application.workflow.spi.WorkflowRunReadPort;
import org.dradgo.application.workflow.spi.WorkflowRunSnapshot;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.RunnerExecutionStatus;
import org.dradgo.domain.registry.RunnerStage;
import org.dradgo.domain.registry.WorkflowState;
import org.dradgo.runnercontracts.RunnerContractValidator;
import org.dradgo.runnercontracts.ValidationContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;

/**
 * Story 3d-4 — unit coverage for the applicability + validation + idempotency gate of {@link
 * ManualArtifactSubmissionService}. Uses a REAL {@link RunnerContractValidator} (true validation
 * parity) and mocks the ports + the broker delegate.
 */
class ManualArtifactSubmissionServiceTest {

  private static final String RUN_ID = "run_abcd1234";
  private static final String REX_ID = "rex_valid_plan";
  private static final String IDEMPOTENCY_KEY = "idem-manual-0001";
  private static final String ACTOR = "operator-jane";

  private static final byte[] VALID_PLAN_PAYLOAD =
      ("""
      {
        "schemaVersion": 1,
        "workflowRunId": "run_abcd1234",
        "runnerExecutionId": "rex_valid_plan",
        "artifactReferences": [
          {
            "artifactId": "art_plan0001",
            "artifactType": "implementationPlan",
            "steps": ["Add schema resources", "Implement validator"],
            "contextReferences": ["ctx/ref/1"]
          }
        ],
        "normalizedOutput": { "summary": "Generated implementation plan", "outcome": "success" },
        "checksum": {
          "algorithm": "SHA-256",
          "hexDigest": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        },
        "classification": "shareable-redacted",
        "failureCategory": null
      }
      """)
          .getBytes(StandardCharsets.UTF_8);

  private WorkflowRunReadPort workflowRunReadPort;
  private RunnerExecutionRecordPort recordPort;
  private RunnerScratchStore scratchStore;
  private RunnerBroker runnerBroker;
  private IdempotencyService idempotencyService;
  private ManualArtifactSubmissionService service;
  private ListAppender<ILoggingEvent> appender;
  private Logger serviceLogger;

  @BeforeEach
  void setUp() {
    workflowRunReadPort = Mockito.mock(WorkflowRunReadPort.class);
    recordPort = Mockito.mock(RunnerExecutionRecordPort.class);
    scratchStore = Mockito.mock(RunnerScratchStore.class);
    runnerBroker = Mockito.mock(RunnerBroker.class);
    idempotencyService = Mockito.mock(IdempotencyService.class);
    service =
        new ManualArtifactSubmissionService(
            workflowRunReadPort,
            recordPort,
            scratchStore,
            new RunnerContractValidator(),
            runnerBroker,
            idempotencyService);
    serviceLogger = (Logger) LoggerFactory.getLogger(ManualArtifactSubmissionService.class);
    appender = new ListAppender<>();
    appender.start();
    serviceLogger.addAppender(appender);
    serviceLogger.setLevel(Level.INFO);
  }

  @org.junit.jupiter.api.AfterEach
  void tearDown() {
    serviceLogger.detachAppender(appender);
  }

  private boolean logged(Level level, String fragment) {
    return appender.list.stream()
        .anyMatch(
            event -> event.getLevel() == level && event.getFormattedMessage().contains(fragment));
  }

  private RunnerExecutionSnapshot parkedRow(RunnerStage stage) {
    RunnerExecutionSnapshot row = Mockito.mock(RunnerExecutionSnapshot.class);
    when(row.publicId()).thenReturn(REX_ID);
    when(row.stage()).thenReturn(stage);
    return row;
  }

  private void stubParkedRunInState(RunnerStage stage, WorkflowState postState) {
    RunnerExecutionSnapshot row = parkedRow(stage);
    when(workflowRunReadPort.findByPublicId(RUN_ID))
        .thenReturn(Optional.of(new WorkflowRunSnapshot(RUN_ID, postState, null, 1L, 0, false)));
    when(recordPort.findByWorkflowRunPublicIdAndStatusIn(
            RUN_ID, List.of(RunnerExecutionStatus.AWAITING_MANUAL)))
        .thenReturn(List.of(row));
    when(runnerBroker.buildResultValidationContext(RUN_ID, REX_ID))
        .thenReturn(
            ValidationContext.builder()
                .maxPayloadBytes(256 * 1024)
                .addKnownRunnerExecutionId(REX_ID)
                .build());
  }

  private ManualArtifactSubmissionCommand command(byte[] payload) {
    return new ManualArtifactSubmissionCommand(
        RUN_ID, payload, Map.of(), IDEMPOTENCY_KEY, ACTOR, ActorType.HUMAN, "corr-1");
  }

  @Test
  void happyPathValidatesThenDelegatesIngestAndCompletesIdempotency() {
    stubParkedRunInState(RunnerStage.EXECUTION, WorkflowState.WAITING_FOR_REVIEW);
    when(idempotencyService.checkAndReserve(eq(IDEMPOTENCY_KEY), any(), eq(ACTOR), any()))
        .thenReturn(new ReservationOutcome(ReservationDecision.RESERVED, null));

    WorkflowStateChangeResult result = service.submit(command(VALID_PLAN_PAYLOAD));

    assertThat(result.currentState()).isEqualTo(WorkflowState.WAITING_FOR_REVIEW);
    verify(runnerBroker)
        .ingestManualResult(eq(REX_ID), eq(VALID_PLAN_PAYLOAD), any(ActorContext.class));
    verify(idempotencyService).complete(eq(IDEMPOTENCY_KEY), eq("WaitingForReview"), any());
    // Logging contract — entry + accepted INFO, never the payload bytes.
    assertThat(logged(Level.INFO, "manual artifact submission received")).isTrue();
    assertThat(logged(Level.INFO, "manual artifact accepted")).isTrue();
  }

  @Test
  void notParkedRunIsManualExecutionNotApplicableAndNeverIngests() {
    when(workflowRunReadPort.findByPublicId(RUN_ID))
        .thenReturn(
            Optional.of(
                new WorkflowRunSnapshot(
                    RUN_ID, WorkflowState.WAITING_FOR_REVIEW, null, 1L, 0, false)));
    when(recordPort.findByWorkflowRunPublicIdAndStatusIn(
            RUN_ID, List.of(RunnerExecutionStatus.AWAITING_MANUAL)))
        .thenReturn(List.of());

    when(idempotencyService.checkAndReserve(eq(IDEMPOTENCY_KEY), any(), eq(ACTOR), any()))
        .thenReturn(new ReservationOutcome(ReservationDecision.RESERVED, null));

    assertThatThrownBy(() -> service.submit(command(VALID_PLAN_PAYLOAD)))
        .isInstanceOf(DomainException.class)
        .extracting(error -> ((DomainException) error).errorCode())
        .isEqualTo(DomainErrorCode.MANUAL_EXECUTION_NOT_APPLICABLE);
    // The reservation is taken first, but the throw rolls the tx (incl. the reservation) back; no
    // ingest, no completion.
    verify(runnerBroker, never()).ingestManualResult(any(), any(), any());
    verify(idempotencyService, never()).complete(any(), any(), any());
  }

  @Test
  void unknownRunIsRunNotFound() {
    when(workflowRunReadPort.findByPublicId(RUN_ID)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.submit(command(VALID_PLAN_PAYLOAD)))
        .isInstanceOf(DomainException.class)
        .extracting(error -> ((DomainException) error).errorCode())
        .isEqualTo(DomainErrorCode.RUN_NOT_FOUND);
    verify(runnerBroker, never()).ingestManualResult(any(), any(), any());
  }

  @Test
  void invalidPayloadIsRejectedWithoutIngestingOrCompleting() {
    stubParkedRunInState(RunnerStage.EXECUTION, WorkflowState.WAITING_FOR_REVIEW);
    when(idempotencyService.checkAndReserve(eq(IDEMPOTENCY_KEY), any(), eq(ACTOR), any()))
        .thenReturn(new ReservationOutcome(ReservationDecision.RESERVED, null));

    byte[] garbage = "{ not a runner result }".getBytes(StandardCharsets.UTF_8);
    assertThatThrownBy(() -> service.submit(command(garbage)))
        .isInstanceOf(DomainException.class)
        .extracting(error -> ((DomainException) error).errorCode())
        .isEqualTo(DomainErrorCode.RUNNER_OUTPUT_VALIDATION_FAILED);
    // AC5 — a failed submission never ingests or completes; the throw rolls the reservation back.
    verify(runnerBroker, never()).ingestManualResult(any(), any(), any());
    verify(idempotencyService, never()).complete(any(), any(), any());
    // Logging contract — the rejection is logged at WARN with the typed code (run unchanged).
    assertThat(logged(Level.WARN, DomainErrorCode.RUNNER_OUTPUT_VALIDATION_FAILED.value()))
        .isTrue();
  }

  @Test
  void wrongArtifactTypeForStageIsRejected() {
    // The parked stage is INVESTIGATION (expects spec) but the payload carries an
    // implementationPlan.
    stubParkedRunInState(RunnerStage.INVESTIGATION, WorkflowState.WAITING_FOR_SPEC_APPROVAL);
    when(idempotencyService.checkAndReserve(eq(IDEMPOTENCY_KEY), any(), eq(ACTOR), any()))
        .thenReturn(new ReservationOutcome(ReservationDecision.RESERVED, null));

    assertThatThrownBy(() -> service.submit(command(VALID_PLAN_PAYLOAD)))
        .isInstanceOf(DomainException.class)
        .extracting(error -> ((DomainException) error).errorCode())
        .isEqualTo(DomainErrorCode.RUNNER_ARTIFACT_TYPE_MISMATCH);
    verify(runnerBroker, never()).ingestManualResult(any(), any(), any());
  }

  @Test
  void fingerprintIsStableAcrossWhitespaceAndKeyOrderingSoCrossChannelRetryReplays() {
    // Review finding 2026-06-23: the REST controller re-serializes the parsed result JSON (compact)
    // while the CLI forwards the operator's raw file bytes (pretty-printed / reordered keys). Both
    // must hash to the SAME fingerprint for the SAME logical artifact, else an honest cross-channel
    // retry under one key surfaces a false IDEMPOTENCY_KEY_CONFLICT. Canonicalization guarantees
    // it.
    byte[] cliRawBytes =
        ("""
        {
          "b": 2,
          "a": 1,
          "nested": { "y": [1, 2], "x": "v" }
        }
        """)
            .getBytes(StandardCharsets.UTF_8);
    byte[] restCompactBytes =
        "{\"a\":1,\"nested\":{\"x\":\"v\",\"y\":[1,2]},\"b\":2}".getBytes(StandardCharsets.UTF_8);

    String cliFingerprint =
        ManualArtifactSubmissionService.fingerprint(RUN_ID, cliRawBytes, Map.of());
    String restFingerprint =
        ManualArtifactSubmissionService.fingerprint(RUN_ID, restCompactBytes, Map.of());

    assertThat(cliFingerprint)
        .as("whitespace + key-order differences must not change the manual-artifact fingerprint")
        .isEqualTo(restFingerprint);

    // Negative: a genuinely different artifact value must still produce a different fingerprint.
    byte[] differentValue = "{\"a\":1,\"b\":3}".getBytes(StandardCharsets.UTF_8);
    assertThat(ManualArtifactSubmissionService.fingerprint(RUN_ID, differentValue, Map.of()))
        .as("a different artifact value must change the fingerprint")
        .isNotEqualTo(cliFingerprint);

    // Array element ORDER is significant in JSON and must change the fingerprint.
    byte[] reorderedArray =
        "{\"a\":1,\"nested\":{\"x\":\"v\",\"y\":[2,1]},\"b\":2}".getBytes(StandardCharsets.UTF_8);
    assertThat(ManualArtifactSubmissionService.fingerprint(RUN_ID, reorderedArray, Map.of()))
        .as("array element order is significant and must change the fingerprint")
        .isNotEqualTo(cliFingerprint);
  }

  @Test
  void idempotentReplayReturnsPriorStateWithoutIngesting() {
    stubParkedRunInState(RunnerStage.EXECUTION, WorkflowState.WAITING_FOR_REVIEW);
    when(idempotencyService.checkAndReserve(eq(IDEMPOTENCY_KEY), any(), eq(ACTOR), any()))
        .thenReturn(new ReservationOutcome(ReservationDecision.REPLAY, "WaitingForReview"));

    WorkflowStateChangeResult result = service.submit(command(VALID_PLAN_PAYLOAD));

    assertThat(result.currentState()).isEqualTo(WorkflowState.WAITING_FOR_REVIEW);
    verify(runnerBroker, never()).ingestManualResult(any(), any(), any());
    verify(idempotencyService, never()).complete(any(), any(), any());
  }
}
