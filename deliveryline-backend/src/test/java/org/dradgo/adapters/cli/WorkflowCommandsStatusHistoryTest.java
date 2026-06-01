package org.dradgo.adapters.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.dradgo.application.artifact.ActorContext;
import org.dradgo.application.idempotency.IdempotencyKeyValidator;
import org.dradgo.application.recovery.RecoveryService;
import org.dradgo.application.recovery.RetryRecoveryResult;
import org.dradgo.application.security.DataClassificationService;
import org.dradgo.application.security.LocalActorIdentityResolver;
import org.dradgo.application.security.RedactionPolicyService;
import org.dradgo.application.workflow.WorkflowCommandService;
import org.dradgo.application.workflow.WorkflowInspectionService;
import org.dradgo.application.workflow.WorkflowInspectionService.LatestArtifactView;
import org.dradgo.application.workflow.WorkflowInspectionService.LinkedTicketView;
import org.dradgo.application.workflow.WorkflowInspectionService.WorkflowEventView;
import org.dradgo.application.workflow.WorkflowInspectionService.WorkflowHistoryView;
import org.dradgo.application.workflow.WorkflowInspectionService.WorkflowStatusView;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.WorkflowState;
import org.dradgo.infrastructure.observability.RedactionLayoutHolder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class WorkflowCommandsStatusHistoryTest {

  // Wire an identity RedactionPolicyService into RedactionLayoutHolder so the
  // %redactedMsg converter (active via logback-spring.xml's local,test,!demo
  // springProfile block) passes log messages through verbatim. Without this
  // bridge, the holder's `service` field is null on plain JUnit tests and the
  // converter emits the fail-closed sentinel "[redaction-pending]" — which
  // causes CapturedOutput.contains(...) assertions to fail on Linux CI where
  // the springProfile block evaluates true (empty profile set → `!demo` is
  // true). Precedent: LoggingForbiddenPayloadContractTest +
  // LoggingRedactionContractTest +  JsonSchemaStabilityTest use this same
  // capture-and-restore pattern (story 1.19 review, P16).
  private static RedactionPolicyService priorService;

  @BeforeAll
  static void wireRedactionHolder() {
    priorService = RedactionLayoutHolder.currentForTesting();
    RedactionLayoutHolder.setRedactionService(
        new RedactionPolicyService(new DataClassificationService()));
  }

  @AfterAll
  static void unwireRedactionHolder() {
    if (priorService == null) {
      RedactionLayoutHolder.clearForTesting();
    } else {
      RedactionLayoutHolder.setRedactionService(priorService);
    }
  }

  private final WorkflowCommandService submitService = mock(WorkflowCommandService.class);
  private final WorkflowInspectionService inspection = mock(WorkflowInspectionService.class);
  private final RecoveryService recovery = mock(RecoveryService.class);
  private final WorkflowCommandOutputs outputs =
      new WorkflowCommandOutputs(new ObjectMapper().findAndRegisterModules());
  private final WorkflowCommands commands =
      new WorkflowCommands(
          submitService,
          inspection,
          outputs,
          () -> false,
          () -> "01964c38-1c45-7000-8000-000000000000",
          () -> "01964c38-1c45-7000-8000-000000000001",
          new IdempotencyKeyValidator(),
          recovery,
          null,
          new LocalActorIdentityResolver("local-operator"));

  @Test
  void statusInvokesInspectionServiceOnceAndRendersText() {
    WorkflowStatusView view =
        new WorkflowStatusView(
            "run_status12345",
            WorkflowState.EXECUTING,
            "alex",
            "human",
            "workflow.stateChanged",
            OffsetDateTime.parse("2026-05-13T10:00:00Z"),
            List.of(new LatestArtifactView("spec", 2, "available")),
            new LinkedTicketView("linear", "LIN-101", "linked"),
            null,
            null,
            null,
            null,
            null,
            "await_outcome",
            0,
            false);
    when(inspection.getStatus("run_status12345")).thenReturn(view);

    String rendered =
        commands.status("run_status12345", "text", "corr-status-1", false, false, false);
    assertTrue(rendered.startsWith("current state: Executing"));
    verify(inspection).getStatus("run_status12345");
  }

  @Test
  void statusRejectsUnsupportedFormatValues() {
    DomainException error =
        assertThrows(
            DomainException.class,
            () -> commands.status("run_status12345", "yaml", "corr-status-1", false, false, false));
    assertEquals(DomainErrorCode.INVALID_COMMAND_PAYLOAD, error.errorCode());
    assertEquals("yaml", error.details().get("format"));
  }

  @Test
  void statusJsonFormatReturnsJsonObject() {
    when(inspection.getStatus("run_status12345"))
        .thenReturn(
            new WorkflowStatusView(
                "run_status12345",
                WorkflowState.INBOX,
                null,
                null,
                null,
                null,
                List.of(),
                null,
                null,
                null,
                null,
                null,
                null,
                "await_outcome",
                0,
                false));

    String rendered = commands.status("run_status12345", "json", null, false, false, false);
    assertTrue(
        rendered.startsWith("{") && rendered.endsWith("}"),
        () -> "JSON output should be a single object, was: " + rendered);
    assertTrue(rendered.contains("\"schemaVersion\":1"));
    assertTrue(rendered.contains("\"workflowRunId\":\"run_status12345\""));
  }

  @Test
  void historyInvokesInspectionServiceWithParsedSinceAndRendersText() {
    WorkflowHistoryView view =
        new WorkflowHistoryView(
            "run_hist12345",
            List.of(
                new WorkflowEventView(
                    "evt_e1abc1234",
                    "workflow.stateChanged",
                    null,
                    "Inbox",
                    "alex",
                    "human",
                    "workflow submitted",
                    null,
                    false,
                    OffsetDateTime.parse("2026-05-13T10:00:00Z"),
                    Map.of())));
    when(inspection.listHistory(eq("run_hist12345"), any())).thenReturn(view);

    String rendered =
        commands.history("run_hist12345", "text", "2026-05-13T09:00:00Z", null, false);
    assertTrue(rendered.contains("workflow.stateChanged"));
    verify(inspection)
        .listHistory(eq("run_hist12345"), eq(OffsetDateTime.parse("2026-05-13T09:00:00Z")));
  }

  @Test
  void historyRaisesInvalidTimeRangeOnUnparseableSince() {
    DomainException error =
        assertThrows(
            DomainException.class,
            () -> commands.history("run_hist12345", "text", "not-a-date", null, false));
    assertEquals(DomainErrorCode.INVALID_TIME_RANGE, error.errorCode());
    assertEquals("not-a-date", error.details().get("since"));
  }

  @Test
  void statusPropagatesDomainExceptionFromInspectionService() {
    when(inspection.getStatus("run_missing12345"))
        .thenThrow(
            new DomainException(
                DomainErrorCode.RUN_NOT_FOUND,
                "missing",
                new java.util.LinkedHashMap<>(Map.of("runId", "run_missing12345"))));

    DomainException error =
        assertThrows(
            DomainException.class,
            () -> commands.status("run_missing12345", "text", null, false, false, false));
    assertEquals(DomainErrorCode.RUN_NOT_FOUND, error.errorCode());
  }

  @Test
  void statusCompletionLogIncludesCorrelationIdOnSuccess(CapturedOutput output) {
    when(inspection.getStatus("run_status12345"))
        .thenReturn(
            new WorkflowStatusView(
                "run_status12345",
                WorkflowState.INBOX,
                null,
                null,
                null,
                null,
                List.of(),
                null,
                null,
                null,
                null,
                null,
                null,
                "await_outcome",
                0,
                false));

    commands.status("run_status12345", "json", "corr-status-42", false, false, false);

    assertTrue(
        output
            .getOut()
            .contains(
                "workflow command completed correlationId=corr-status-42 commandName=workflow status workflowRunId=run_status12345 outcome=success"));
  }

  @Test
  void statusCompletionLogIncludesCorrelationIdOnFailure(CapturedOutput output) {
    when(inspection.getStatus("run_missing12345"))
        .thenThrow(
            new DomainException(
                DomainErrorCode.RUN_NOT_FOUND,
                "missing",
                new java.util.LinkedHashMap<>(Map.of("runId", "run_missing12345"))));

    assertThrows(
        DomainException.class,
        () -> commands.status("run_missing12345", "text", null, false, false, false));

    assertTrue(
        output
            .getOut()
            .contains(
                "workflow command completed correlationId=01964c38-1c45-7000-8000-000000000001 commandName=workflow status workflowRunId=run_missing12345 outcome=failure:RUN_NOT_FOUND"));
  }

  // =====================================================================
  // retry command (story 1.18 Task 7)
  // =====================================================================

  @Test
  void retryHappyPathReturnsRecoveryActionAndRunnerExecutionAndEmitsSuccessLog(
      CapturedOutput output) {
    when(recovery.retry(eq("run_retry01234"), any(), any(ActorContext.class), any()))
        .thenReturn(
            new RetryRecoveryResult(
                "rcv_recov-aaaaa",
                "evt_retried-bbbbb",
                null,
                "rex_new1-ccccc",
                "corr-retry-1",
                false));

    String rendered =
        commands.retry(
            "run_retry01234",
            "alex",
            ActorType.HUMAN,
            "idem-retry-1234567890",
            "corr-retry-1",
            null,
            false);

    assertEquals(
        "rcv_recov-aaaaa retry submitted (state: Executing) [runner-execution: rex_new1-ccccc]",
        rendered);
    assertTrue(
        output
            .getOut()
            .contains(
                "workflow command completed correlationId=corr-retry-1 commandName=workflow retry workflowRunId=run_retry01234 outcome=success"));
  }

  @Test
  void retryVerboseFlagAppendsCorrelationAndRecoveryEventAndGeneratedKey(CapturedOutput output) {
    when(recovery.retry(eq("run_retry01234"), any(), any(ActorContext.class), any()))
        .thenReturn(
            new RetryRecoveryResult(
                "rcv_recov-aaaaa",
                "evt_retried-bbbbb",
                null,
                "rex_new1-ccccc",
                "corr-retry-2",
                false));

    String rendered =
        commands.retry(
            "run_retry01234",
            "alex",
            ActorType.HUMAN,
            "idem-retry-1234567890",
            "corr-retry-2",
            null,
            true);

    assertTrue(rendered.contains("[runner-execution: rex_new1-ccccc]"));
    assertTrue(rendered.contains("[correlation-id: corr-retry-2]"));
    assertTrue(rendered.contains("[recovery-event: evt_retried-bbbbb]"));
  }

  @Test
  void retryReplayResultEmitsReplayedMarkerAndOmitsRunnerExecutionField() {
    when(recovery.retry(eq("run_retry01234"), any(), any(ActorContext.class), any()))
        .thenReturn(
            new RetryRecoveryResult(
                "rcv_recov-prior", "evt_prior-bbbbb", null, null, "corr-retry-3", true));

    String rendered =
        commands.retry(
            "run_retry01234",
            "alex",
            ActorType.HUMAN,
            "idem-retry-1234567890",
            "corr-retry-3",
            null,
            false);

    assertEquals("rcv_recov-prior retry submitted (state: Executing) [replayed]", rendered);
  }

  @Test
  void retryOnNonFailedRunPropagatesRetryNotApplicableAndExits201ViaMapper(CapturedOutput output) {
    when(recovery.retry(eq("run_retry01234"), any(), any(ActorContext.class), any()))
        .thenThrow(
            new DomainException(
                DomainErrorCode.RETRY_NOT_APPLICABLE,
                "Retry is only applicable to runs in state Failed; current state is Executing",
                Map.of("runId", "run_retry01234", "currentState", "Executing")));

    DomainException error =
        assertThrows(
            DomainException.class,
            () ->
                commands.retry(
                    "run_retry01234",
                    "alex",
                    ActorType.HUMAN,
                    "idem-retry-1234567890",
                    "corr-retry-bad",
                    null,
                    false));
    assertEquals(DomainErrorCode.RETRY_NOT_APPLICABLE, error.errorCode());
    assertTrue(
        output
            .getOut()
            .contains(
                "workflow command completed correlationId=corr-retry-bad commandName=workflow retry workflowRunId=run_retry01234 outcome=failure:RETRY_NOT_APPLICABLE"));
  }

  @Test
  void retryWithoutIdempotencyKeyInNonInteractiveSessionThrowsMissingIdempotencyKey() {
    // Non-interactive session: the test fixture's interactivityDetector returns false.
    DomainException error =
        assertThrows(
            DomainException.class,
            () ->
                commands.retry(
                    "run_retry01234",
                    "alex",
                    ActorType.HUMAN,
                    null,
                    "corr-retry-noidem",
                    null,
                    false));
    assertEquals(DomainErrorCode.MISSING_IDEMPOTENCY_KEY, error.errorCode());
  }

  @Test
  void retryRejectsInvalidIdempotencyKeyBeforeCallingRecoveryService() {
    DomainException error =
        assertThrows(
            DomainException.class,
            () ->
                commands.retry(
                    "run_retry01234",
                    "alex",
                    ActorType.HUMAN,
                    "bad key",
                    "corr-retry-invalid",
                    null,
                    false));
    assertEquals(DomainErrorCode.INVALID_IDEMPOTENCY_KEY, error.errorCode());
    verifyNoInteractions(recovery);
  }
}
