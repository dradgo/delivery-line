package org.dradgo.adapters.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.dradgo.application.idempotency.IdempotencyKeyValidator;
import org.dradgo.application.recovery.ClassifyFailureResult;
import org.dradgo.application.recovery.PauseRecoveryResult;
import org.dradgo.application.recovery.RecoveryService;
import org.dradgo.application.recovery.RerunFromStepRecoveryResult;
import org.dradgo.application.recovery.ResumeRecoveryResult;
import org.dradgo.application.security.DataClassificationService;
import org.dradgo.application.security.LocalActorIdentityResolver;
import org.dradgo.application.security.RedactionPolicyService;
import org.dradgo.application.workflow.WorkflowInspectionService;
import org.dradgo.application.workflow.WorkflowInspectionService.OperatorRunRow;
import org.dradgo.application.workflow.WorkflowInspectionService.OperatorRunSummary;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.FailureCategory;
import org.dradgo.domain.registry.WorkflowState;
import org.dradgo.infrastructure.observability.RedactionLayoutHolder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * Story 4.1 (AC1/AC4/AC7/AC10) — {@code deliveryline operator status} CLI rendering + completion
 * log. Mocks {@link WorkflowInspectionService} (the single read seam — AC9) and uses the real
 * {@link WorkflowCommandOutputs} renderer, mirroring {@code WorkerCommandsTest}. Covers text vs
 * JSON, ANSI gating (TTY only), UPPERCASE-label survival when ANSI is stripped, sort order, {@code
 * INVALID_COMMAND_PAYLOAD} propagation, and the AC7 completion-log line.
 */
@ExtendWith(OutputCaptureExtension.class)
class OperatorCommandsTest {

  // Wire an identity RedactionPolicyService into RedactionLayoutHolder so the %redactedMsg
  // converter
  // (installed on the shared LoggerContext once any Spring context boots via logback-spring.xml)
  // passes the AC7 completion-log messages through verbatim. Without this bridge the holder's
  // `service` field is null on this plain JUnit test and the converter emits the fail-closed
  // sentinel
  // "[redaction-pending]", making the CapturedOutput.contains(...) assertions order-dependent
  // (green
  // only when another live context happens to have the holder wired). Same capture-and-restore
  // precedent as SpaFallbackControllerTest / WorkflowCommandsStatusHistoryTest (story 1.19 review).
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

  private static final String ESC = Character.toString((char) 27);
  private static final OffsetDateTime T1 =
      OffsetDateTime.of(2026, 7, 5, 10, 0, 0, 0, ZoneOffset.UTC);
  private static final OffsetDateTime T2 =
      OffsetDateTime.of(2026, 7, 5, 9, 0, 0, 0, ZoneOffset.UTC);

  private final WorkflowInspectionService inspection = mock(WorkflowInspectionService.class);
  private final WorkflowCommandOutputs outputs = new WorkflowCommandOutputs(new ObjectMapper());

  private OperatorCommands commands(boolean interactive) {
    CliInteractivityDetector detector = mock(CliInteractivityDetector.class);
    when(detector.isInteractive()).thenReturn(interactive);
    // Story 4.10 — the status/diagnose tests here ignore the recovery/idempotency/actor deps;
    // supply
    // benign instances so the widened constructor is satisfiable.
    return new OperatorCommands(
        inspection,
        outputs,
        detector,
        mock(RecoveryService.class),
        new IdempotencyKeyValidator(),
        new LocalActorIdentityResolver("local-operator"),
        () -> "corr-fixed",
        () -> "idem-fixed");
  }

  private static OperatorRunSummary fixture() {
    Map<WorkflowState, Integer> byState = new EnumMap<>(WorkflowState.class);
    byState.put(WorkflowState.FAILED, 2);
    byState.put(WorkflowState.TAKEN_OVER, 1);
    Map<FailureCategory, Integer> byFailure = new EnumMap<>(FailureCategory.class);
    byFailure.put(FailureCategory.ORPHAN, 1);
    return new OperatorRunSummary(
        3,
        byState,
        byFailure,
        T2,
        List.of(
            new OperatorRunRow(
                "run_orphan001",
                WorkflowState.FAILED,
                "orphan",
                T1,
                "system",
                "LIN-101",
                "octo/repo#7",
                true,
                T2,
                "ORPHANED",
                null),
            new OperatorRunRow(
                "run_taken0002",
                WorkflowState.TAKEN_OVER,
                null,
                T2,
                "alex",
                null,
                null,
                false,
                T2,
                "TAKENOVER",
                null)),
        null);
  }

  @Test
  void textRendersHistogramsLabelsAndRowsInOrder() {
    when(inspection.getOperatorRunSummary(any())).thenReturn(fixture());

    String out =
        commands(false).status("failed,orphaned,takenover", null, "text", 100, null, false);

    assertThat(out).contains("total: 3");
    assertThat(out).contains("Failed: 2");
    assertThat(out).contains("orphan: 1");
    // Non-color signifier: UPPERCASE bracketed labels, present with color stripped (non-TTY).
    assertThat(out).contains("[ORPHANED] run_orphan001");
    assertThat(out).contains("[TAKENOVER] run_taken0002");
    assertThat(out).doesNotContain(ESC);
    // Sort order preserved: orphan row rendered before takenover row.
    assertThat(out.indexOf("run_orphan001")).isLessThan(out.indexOf("run_taken0002"));
  }

  @Test
  void overriddenRunInActiveStateRendersOverriddenNotStalled() {
    // Regression (review 4.1): an overridden-matched run in an active state (Executing) must render
    // [OVERRIDDEN] — the renderer uses the server-derived signifier, not a state re-derivation that
    // mislabeled it [STALLED]. Also proves [OVERRIDDEN] is emitted at all (AC4 / Reconciliation 9).
    Map<WorkflowState, Integer> byState = new EnumMap<>(WorkflowState.class);
    byState.put(WorkflowState.EXECUTING, 1);
    OperatorRunSummary view =
        new OperatorRunSummary(
            1,
            byState,
            new EnumMap<>(FailureCategory.class),
            T2,
            List.of(
                new OperatorRunRow(
                    "run_override01",
                    WorkflowState.EXECUTING,
                    null,
                    T1,
                    "alex",
                    null,
                    null,
                    false,
                    T2,
                    "OVERRIDDEN",
                    null)),
            null);
    when(inspection.getOperatorRunSummary(any())).thenReturn(view);

    String out = commands(false).status("overridden", null, "text", 100, null, false);

    assertThat(out).contains("[OVERRIDDEN] run_override01");
    assertThat(out).doesNotContain("[STALLED]");
    assertThat(out).doesNotContain(ESC);
  }

  @Test
  void ansiColorsRowsOnTtyButLabelsStillReadable() {
    when(inspection.getOperatorRunSummary(any())).thenReturn(fixture());

    String out = commands(true).status("failed,takenover", null, "text", 100, null, false);

    // Red for the failed/orphaned row, dim for the takenover row; the bracketed label survives.
    assertThat(out).contains(ESC + "[31m[ORPHANED]" + ESC + "[0m");
    assertThat(out).contains(ESC + "[2m[TAKENOVER]" + ESC + "[0m");
  }

  @Test
  void jsonEmitsStableSchemaWithoutAnsiEvenOnTty() {
    when(inspection.getOperatorRunSummary(any())).thenReturn(fixture());

    String out = commands(true).status("failed", null, "json", 100, null, false);

    assertThat(out).doesNotContain(ESC);
    JsonNode body = readJson(out);
    assertThat(body.path("schemaVersion").asInt()).isEqualTo(1);
    assertThat(body.path("total").asInt()).isEqualTo(3);
    assertThat(body.path("byState").path("Failed").asInt()).isEqualTo(2);
    assertThat(body.path("byFailureCategory").path("orphan").asInt()).isEqualTo(1);
    assertThat(body.path("runs").get(0).path("runId").asText()).isEqualTo("run_orphan001");
    assertThat(body.path("runs").get(0).path("linkedPrRef").asText()).isEqualTo("octo/repo#7");
    assertThat(body.path("runs").get(1).path("linkedTicketRef").isNull()).isTrue();
  }

  @Test
  void invalidFormatRaisesInvalidCommandPayload() {
    // Format is validated in the adapter before the service is consulted.
    assertThatThrownBy(() -> commands(false).status("failed", null, "xml", 100, null, false))
        .isInstanceOf(DomainException.class)
        .extracting(e -> ((DomainException) e).errorCode())
        .isEqualTo(DomainErrorCode.INVALID_COMMAND_PAYLOAD);
  }

  @Test
  void invalidStateTokenFromServicePropagates() {
    when(inspection.getOperatorRunSummary(any()))
        .thenThrow(
            new DomainException(DomainErrorCode.INVALID_COMMAND_PAYLOAD, "bad state", Map.of()));

    assertThatThrownBy(() -> commands(false).status("bogus", null, "text", 100, null, false))
        .isInstanceOf(DomainException.class)
        .extracting(e -> ((DomainException) e).errorCode())
        .isEqualTo(DomainErrorCode.INVALID_COMMAND_PAYLOAD);
  }

  @Test
  void emitsCompletionLogLineWithFilterAndResultCount(CapturedOutput output) {
    when(inspection.getOperatorRunSummary(any())).thenReturn(fixture());

    commands(false).status("failed,orphaned", "24h", "text", 50, "corr-abc", false);

    assertThat(output.getOut() + output.getErr())
        .contains("operator command completed")
        .contains("correlationId=corr-abc")
        .contains("commandName=operator status")
        .contains("resultCount=3")
        .contains("outcome=success");
  }

  @Test
  void completionLogRecordsFailureOutcomeOnServiceError(CapturedOutput output) {
    when(inspection.getOperatorRunSummary(any()))
        .thenThrow(
            new DomainException(DomainErrorCode.INVALID_COMMAND_PAYLOAD, "bad since", Map.of()));

    assertThatThrownBy(() -> commands(false).status("failed", "nope", "text", 100, null, false))
        .isInstanceOf(DomainException.class);

    assertThat(output.getOut() + output.getErr())
        .contains("operator command completed")
        .contains("outcome=failure:INVALID_COMMAND_PAYLOAD");
  }

  @Test
  void verboseAppendsResolvedCorrelationId() {
    when(inspection.getOperatorRunSummary(any())).thenReturn(fixture());

    String out = commands(false).status("failed", null, "text", 100, null, true);

    assertThat(out).contains("correlationId=corr-fixed");
  }

  // ---------------------------------------------------------------------------
  // Story 4.4 (AC3/AC10) — `deliveryline operator diagnose {runId}` rendering + completion log.
  // ---------------------------------------------------------------------------

  private static WorkflowInspectionService.FailureDiagnostics diagnosticsFixture() {
    return new WorkflowInspectionService.FailureDiagnostics(
        WorkflowState.FAILED,
        "execution",
        "Executing",
        "runner_timeout",
        "boom",
        T1,
        T1,
        "corr_diag_1",
        "Executing",
        null,
        "retry",
        "operator-jane",
        new WorkflowInspectionService.RunnerLogReferenceView(
            "rex_diag001", "/runner-logs/rex_diag001", 128L, "shareable-redacted", 2),
        new WorkflowInspectionService.IntegrationSyncStatusView("linear", "LIN-101", "synced", T1),
        null,
        List.of(
            new WorkflowInspectionService.RecommendedActionView(
                "retry", "safe", "Retry from the failed stage.", "workspace intact")));
  }

  @Test
  void diagnoseTextRendersNfr7AndSafetyLabels() {
    when(inspection.getFailureDiagnostics("run_diag12345678")).thenReturn(diagnosticsFixture());

    String out = commands(false).diagnose("run_diag12345678", "text", null, false);

    assertThat(out).contains("what happened:").contains("what changed:").contains("who acted:");
    assertThat(out).contains("what failed:").contains("what is next:");
    assertThat(out).contains("[SAFE] retry");
    assertThat(out).doesNotContain(ESC);
  }

  @Test
  void diagnoseAnsiColorsSafetyOnTty() {
    when(inspection.getFailureDiagnostics("run_diag12345678")).thenReturn(diagnosticsFixture());

    String out = commands(true).diagnose("run_diag12345678", "text", null, false);

    assertThat(out).contains(ESC + "[32m[SAFE]" + ESC + "[0m");
  }

  @Test
  void diagnoseJsonEmitsStableSchemaWithoutAnsi() {
    when(inspection.getFailureDiagnostics("run_diag12345678")).thenReturn(diagnosticsFixture());

    String out = commands(true).diagnose("run_diag12345678", "json", null, false);

    assertThat(out).doesNotContain(ESC);
    JsonNode body = readJson(out);
    assertThat(body.path("schemaVersion").asInt()).isEqualTo(1);
    assertThat(body.path("currentState").asText()).isEqualTo("Failed");
    assertThat(body.path("recommendedRecoveryActions").get(0).path("safetyLevel").asText())
        .isEqualTo("safe");
    assertThat(body.path("integrationSyncStatus").path("linear").path("syncStatus").asText())
        .isEqualTo("synced");
    assertThat(body.path("integrationSyncStatus").path("github").isNull()).isTrue();
    assertThat(body.path("runnerLogReference").path("runnerExecutionId").asText())
        .isEqualTo("rex_diag001");
  }

  @Test
  void diagnoseJsonWithVerboseStaysParseable() {
    when(inspection.getFailureDiagnostics("run_diag12345678")).thenReturn(diagnosticsFixture());

    String out = commands(false).diagnose("run_diag12345678", "json", null, true);

    // The verbose correlation-id footer must NOT be appended to JSON output — gluing a trailing
    // "correlationId=..." line onto the document would break the stable operator-diagnose.v1
    // schema.
    assertThat(out).doesNotContain("correlationId=");
    JsonNode body = readJson(out);
    assertThat(body.path("schemaVersion").asInt()).isEqualTo(1);
  }

  @Test
  void diagnoseInvalidFormatRaisesInvalidCommandPayload() {
    assertThatThrownBy(() -> commands(false).diagnose("run_diag12345678", "xml", null, false))
        .isInstanceOf(DomainException.class)
        .extracting(e -> ((DomainException) e).errorCode())
        .isEqualTo(DomainErrorCode.INVALID_COMMAND_PAYLOAD);
  }

  @Test
  void diagnoseEmitsCompletionLogLine(CapturedOutput output) {
    when(inspection.getFailureDiagnostics("run_diag12345678")).thenReturn(diagnosticsFixture());

    commands(false).diagnose("run_diag12345678", "text", "corr-abc", false);

    assertThat(output.getOut() + output.getErr())
        .contains("operator command completed")
        .contains("commandName=operator diagnose")
        .contains("workflowRunId=run_diag12345678")
        .contains("outcome=success");
  }

  // ---------------------------------------------------------------------------
  // Story 4.10 (AC6) — `deliveryline operator resume {runId}` rendering + completion log.
  // ---------------------------------------------------------------------------

  private OperatorCommands resumeCommands(RecoveryService rs, boolean interactive) {
    CliInteractivityDetector detector = mock(CliInteractivityDetector.class);
    when(detector.isInteractive()).thenReturn(interactive);
    return new OperatorCommands(
        inspection,
        outputs,
        detector,
        rs,
        new IdempotencyKeyValidator(),
        new LocalActorIdentityResolver("local-operator"),
        () -> "corr-fixed",
        () -> "idem-generated-0000001");
  }

  @Test
  void resumeTextRendersRecoveryActionStateAndRunnerExecution() {
    RecoveryService rs = mock(RecoveryService.class);
    when(rs.resume(any(), any(), any(), any()))
        .thenReturn(
            new ResumeRecoveryResult(
                "rcv_res001",
                "evt_res001",
                "rex_res001",
                WorkflowState.EXECUTING,
                "corr-1",
                false));

    String out =
        resumeCommands(rs, true)
            .resume("run_res001", "note", "idem-resume-key-0000001", null, "corr-1", "text", false);

    assertThat(out).contains("rcv_res001 resume submitted (state: Executing)");
    assertThat(out).contains("[runner-execution: rex_res001]");
    assertThat(out).doesNotContain("[replayed]");
    // Actor resolved to local-operator (omitted --actor-identity), passed as an ActorContext.
    verify(rs).resume(eq("run_res001"), eq("idem-resume-key-0000001"), any(), eq("note"));
  }

  @Test
  void resumeReplayTextShowsReplayedAndOmitsRunnerExecution() {
    RecoveryService rs = mock(RecoveryService.class);
    when(rs.resume(any(), any(), any(), any()))
        .thenReturn(
            new ResumeRecoveryResult(
                "rcv_res002", "evt_res002", null, WorkflowState.EXECUTING, "corr-1", true));

    String out =
        resumeCommands(rs, true)
            .resume("run_res002", null, "idem-resume-key-0000001", null, "corr-1", "text", false);

    assertThat(out).contains("rcv_res002 resume submitted (state: Executing)");
    assertThat(out).contains("[replayed]");
    assertThat(out).doesNotContain("[runner-execution:");
  }

  @Test
  void resumeVerboseAppendsCorrelationIdAndGeneratedKeyWhenKeyOmitted() {
    RecoveryService rs = mock(RecoveryService.class);
    when(rs.resume(any(), any(), any(), any()))
        .thenReturn(
            new ResumeRecoveryResult(
                "rcv_res003",
                "evt_res003",
                "rex_res003",
                WorkflowState.EXECUTING,
                "corr-1",
                false));

    // Interactive TTY + omitted --idempotency-key ⇒ the key is auto-generated; verbose surfaces it.
    String out =
        resumeCommands(rs, true).resume("run_res003", "note", null, null, "corr-1", "text", true);

    assertThat(out).contains("[correlation-id: corr-1]");
    assertThat(out).contains("[generated-idempotency-key: idem-generated-0000001]");
    verify(rs).resume(eq("run_res003"), eq("idem-generated-0000001"), any(), eq("note"));
  }

  @Test
  void resumeJsonEmitsStableSchemaWithoutVerboseFooter() {
    RecoveryService rs = mock(RecoveryService.class);
    when(rs.resume(any(), any(), any(), any()))
        .thenReturn(
            new ResumeRecoveryResult(
                "rcv_res004",
                "evt_res004",
                "rex_res004",
                WorkflowState.EXECUTING,
                "corr-1",
                false));

    // --verbose must NOT glue a "correlation-id" footer onto JSON (would break operator-resume.v1).
    String out =
        resumeCommands(rs, true)
            .resume("run_res004", "note", "idem-resume-key-0000001", null, "corr-1", "json", true);

    assertThat(out).doesNotContain("[correlation-id:");
    JsonNode body = readJson(out);
    assertThat(body.path("schemaVersion").asInt()).isEqualTo(1);
    assertThat(body.path("workflowRunId").asText()).isEqualTo("run_res004");
    assertThat(body.path("currentState").asText()).isEqualTo("Executing");
    assertThat(body.path("recoveryActionId").asText()).isEqualTo("rcv_res004");
    assertThat(body.path("runnerExecutionId").asText()).isEqualTo("rex_res004");
    assertThat(body.path("replayed").asBoolean()).isFalse();
  }

  @Test
  void resumeInvalidFormatRaisesInvalidCommandPayloadBeforeMutating() {
    RecoveryService rs = mock(RecoveryService.class);

    assertThatThrownBy(
            () ->
                resumeCommands(rs, true)
                    .resume(
                        "run_res005", null, "idem-resume-key-0000001", null, null, "xml", false))
        .isInstanceOf(DomainException.class)
        .extracting(e -> ((DomainException) e).errorCode())
        .isEqualTo(DomainErrorCode.INVALID_COMMAND_PAYLOAD);
    // The mutation must not have fired when --format is rejected.
    org.mockito.Mockito.verifyNoInteractions(rs);
  }

  @Test
  void resumeNonInteractiveWithoutKeyRaisesMissingIdempotencyKey() {
    RecoveryService rs = mock(RecoveryService.class);

    assertThatThrownBy(
            () ->
                resumeCommands(rs, false)
                    .resume("run_res006", null, null, null, "corr-1", "text", false))
        .isInstanceOf(DomainException.class)
        .extracting(e -> ((DomainException) e).errorCode())
        .isEqualTo(DomainErrorCode.MISSING_IDEMPOTENCY_KEY);
    org.mockito.Mockito.verifyNoInteractions(rs);
  }

  @Test
  void resumeEmitsCompletionLogLine(CapturedOutput output) {
    RecoveryService rs = mock(RecoveryService.class);
    when(rs.resume(any(), any(), any(), any()))
        .thenReturn(
            new ResumeRecoveryResult(
                "rcv_res007",
                "evt_res007",
                "rex_res007",
                WorkflowState.EXECUTING,
                "corr-abc",
                false));

    resumeCommands(rs, true)
        .resume("run_res007", "note", "idem-resume-key-0000001", null, "corr-abc", "text", false);

    assertThat(output.getOut() + output.getErr())
        .contains("operator command completed")
        .contains("commandName=operator resume")
        .contains("workflowRunId=run_res007")
        .contains("outcome=success");
    // The free-form reason prose is never logged verbatim (length only).
    assertThat(output.getOut() + output.getErr()).doesNotContain("note");
  }

  // ---------------------------------------------------------------------------
  // Story 4.12 (AC6) — `deliveryline operator rerun-from-step {runId}` rendering + completion log.
  // ---------------------------------------------------------------------------

  private OperatorCommands rerunCommands(RecoveryService rs, boolean interactive) {
    CliInteractivityDetector detector = mock(CliInteractivityDetector.class);
    when(detector.isInteractive()).thenReturn(interactive);
    return new OperatorCommands(
        inspection,
        outputs,
        detector,
        rs,
        new IdempotencyKeyValidator(),
        new LocalActorIdentityResolver("local-operator"),
        () -> "corr-fixed",
        () -> "idem-generated-0000001");
  }

  private static RerunFromStepRecoveryResult rerunResult(
      String recoveryId, String runnerExecId, WorkflowState state, boolean replayed) {
    return new RerunFromStepRecoveryResult(
        recoveryId,
        "evt_" + recoveryId,
        List.of("art_superseded_1"),
        List.of("apr_invalidated_1"),
        runnerExecId,
        state,
        "corr-1",
        replayed);
  }

  @Test
  void rerunTextRendersRecoveryActionStateAndRunnerExecution() {
    RecoveryService rs = mock(RecoveryService.class);
    when(rs.rerunFromStep(any(), any(), any(), any(), any()))
        .thenReturn(rerunResult("rcv_rr001", "rex_rr001", WorkflowState.INVESTIGATING, false));

    String out =
        rerunCommands(rs, true)
            .rerunFromStep(
                "run_rr001",
                "investigating",
                "note",
                "idem-rerun-key-00000001",
                null,
                "corr-1",
                "text",
                false);

    assertThat(out).contains("rcv_rr001 rerun-from-step submitted (state: Investigating)");
    assertThat(out).contains("[runner-execution: rex_rr001]");
    assertThat(out).doesNotContain("[replayed]");
    // Positional arg order: targetStep SECOND, before idempotencyKey (Reconciliation 4).
    verify(rs)
        .rerunFromStep(
            eq("run_rr001"), eq("investigating"), eq("idem-rerun-key-00000001"), any(), eq("note"));
  }

  @Test
  void rerunReplayTextShowsReplayedAndOmitsRunnerExecution() {
    RecoveryService rs = mock(RecoveryService.class);
    when(rs.rerunFromStep(any(), any(), any(), any(), any()))
        .thenReturn(rerunResult("rcv_rr002", null, WorkflowState.EXECUTING, true));

    String out =
        rerunCommands(rs, true)
            .rerunFromStep(
                "run_rr002",
                "executing",
                null,
                "idem-rerun-key-00000001",
                null,
                "corr-1",
                "text",
                false);

    assertThat(out).contains("rcv_rr002 rerun-from-step submitted (state: Executing)");
    assertThat(out).contains("[replayed]");
    assertThat(out).doesNotContain("[runner-execution:");
  }

  @Test
  void rerunVerboseAppendsCorrelationIdAndGeneratedKeyWhenKeyOmitted() {
    RecoveryService rs = mock(RecoveryService.class);
    when(rs.rerunFromStep(any(), any(), any(), any(), any()))
        .thenReturn(rerunResult("rcv_rr003", "rex_rr003", WorkflowState.INVESTIGATING, false));

    // Interactive TTY + omitted --idempotency-key ⇒ the key is auto-generated; verbose surfaces it.
    String out =
        rerunCommands(rs, true)
            .rerunFromStep(
                "run_rr003", "investigating", "note", null, null, "corr-1", "text", true);

    assertThat(out).contains("[correlation-id: corr-1]");
    assertThat(out).contains("[generated-idempotency-key: idem-generated-0000001]");
    verify(rs)
        .rerunFromStep(
            eq("run_rr003"), eq("investigating"), eq("idem-generated-0000001"), any(), eq("note"));
  }

  @Test
  void rerunJsonEmitsStableSchemaWithoutVerboseFooter() {
    RecoveryService rs = mock(RecoveryService.class);
    when(rs.rerunFromStep(any(), any(), any(), any(), any()))
        .thenReturn(rerunResult("rcv_rr004", "rex_rr004", WorkflowState.INVESTIGATING, false));

    // --verbose must NOT glue a "correlation-id" footer onto JSON (would break
    // operator-rerun-from-step.v1).
    String out =
        rerunCommands(rs, true)
            .rerunFromStep(
                "run_rr004",
                "investigating",
                "note",
                "idem-rerun-key-00000001",
                null,
                "corr-1",
                "json",
                true);

    assertThat(out).doesNotContain("[correlation-id:");
    JsonNode body = readJson(out);
    assertThat(body.path("schemaVersion").asInt()).isEqualTo(1);
    assertThat(body.path("workflowRunId").asText()).isEqualTo("run_rr004");
    assertThat(body.path("currentState").asText()).isEqualTo("Investigating");
    assertThat(body.path("recoveryActionId").asText()).isEqualTo("rcv_rr004");
    assertThat(body.path("rerunEventId").asText()).isEqualTo("evt_rcv_rr004");
    assertThat(body.path("supersededArtifactIds").isArray()).isTrue();
    assertThat(body.path("supersededArtifactIds").get(0).asText()).isEqualTo("art_superseded_1");
    assertThat(body.path("invalidatedApprovalIds").get(0).asText()).isEqualTo("apr_invalidated_1");
    assertThat(body.path("runnerExecutionId").asText()).isEqualTo("rex_rr004");
    assertThat(body.path("replayed").asBoolean()).isFalse();
  }

  @Test
  void rerunInvalidFormatRaisesInvalidCommandPayloadBeforeMutating() {
    RecoveryService rs = mock(RecoveryService.class);

    assertThatThrownBy(
            () ->
                rerunCommands(rs, true)
                    .rerunFromStep(
                        "run_rr005",
                        "investigating",
                        null,
                        "idem-rerun-key-00000001",
                        null,
                        null,
                        "xml",
                        false))
        .isInstanceOf(DomainException.class)
        .extracting(e -> ((DomainException) e).errorCode())
        .isEqualTo(DomainErrorCode.INVALID_COMMAND_PAYLOAD);
    // The mutation must not have fired when --format is rejected.
    org.mockito.Mockito.verifyNoInteractions(rs);
  }

  @Test
  void rerunNonInteractiveWithoutKeyRaisesMissingIdempotencyKey() {
    RecoveryService rs = mock(RecoveryService.class);

    assertThatThrownBy(
            () ->
                rerunCommands(rs, false)
                    .rerunFromStep(
                        "run_rr006", "investigating", null, null, null, "corr-1", "text", false))
        .isInstanceOf(DomainException.class)
        .extracting(e -> ((DomainException) e).errorCode())
        .isEqualTo(DomainErrorCode.MISSING_IDEMPOTENCY_KEY);
    org.mockito.Mockito.verifyNoInteractions(rs);
  }

  @Test
  void rerunEmitsCompletionLogLineSuccess(CapturedOutput output) {
    RecoveryService rs = mock(RecoveryService.class);
    when(rs.rerunFromStep(any(), any(), any(), any(), any()))
        .thenReturn(rerunResult("rcv_rr007", "rex_rr007", WorkflowState.INVESTIGATING, false));

    rerunCommands(rs, true)
        .rerunFromStep(
            "run_rr007",
            "investigating",
            "note",
            "idem-rerun-key-00000001",
            null,
            "corr-abc",
            "text",
            false);

    assertThat(output.getOut() + output.getErr())
        .contains("operator command completed")
        .contains("commandName=operator rerun-from-step")
        .contains("workflowRunId=run_rr007")
        .contains("outcome=success");
    // The free-form reason prose is never logged verbatim (length only).
    assertThat(output.getOut() + output.getErr()).doesNotContain("note");
  }

  @Test
  void rerunEmitsCompletionLogLineFailureOnServiceError(CapturedOutput output) {
    RecoveryService rs = mock(RecoveryService.class);
    when(rs.rerunFromStep(any(), any(), any(), any(), any()))
        .thenThrow(
            new DomainException(
                DomainErrorCode.INVALID_RERUN_TARGET_STEP, "bad target", Map.of("provided", "x")));

    assertThatThrownBy(
            () ->
                rerunCommands(rs, true)
                    .rerunFromStep(
                        "run_rr008",
                        "bogus",
                        "note",
                        "idem-rerun-key-00000001",
                        null,
                        "corr-abc",
                        "text",
                        false))
        .isInstanceOf(DomainException.class);

    assertThat(output.getOut() + output.getErr())
        .contains("operator command completed")
        .contains("commandName=operator rerun-from-step")
        .contains("outcome=failure:INVALID_RERUN_TARGET_STEP");
  }

  // ---------------------------------------------------------------------------
  // Story 4.13 (AC6) — `deliveryline operator pause {runId}` rendering + completion log.
  // ---------------------------------------------------------------------------

  private OperatorCommands pauseCommands(RecoveryService rs, boolean interactive) {
    CliInteractivityDetector detector = mock(CliInteractivityDetector.class);
    when(detector.isInteractive()).thenReturn(interactive);
    return new OperatorCommands(
        inspection,
        outputs,
        detector,
        rs,
        new IdempotencyKeyValidator(),
        new LocalActorIdentityResolver("local-operator"),
        () -> "corr-fixed",
        () -> "idem-generated-0000001");
  }

  private static PauseRecoveryResult pauseResult(
      String recoveryId, WorkflowState priorState, int inFlight, int queued, boolean replayed) {
    return new PauseRecoveryResult(
        recoveryId,
        "evt_" + recoveryId,
        priorState,
        inFlight,
        queued,
        WorkflowState.PAUSED,
        "corr-1",
        replayed);
  }

  @Test
  void pauseTextRendersRecoveryActionStatePriorAndCancellationCounts() {
    RecoveryService rs = mock(RecoveryService.class);
    when(rs.pause(any(), any(), any(), any()))
        .thenReturn(pauseResult("rcv_pp001", WorkflowState.EXECUTING, 2, 3, false));

    String out =
        pauseCommands(rs, true)
            .pause("run_pp001", "note", "idem-pause-key-00000001", null, "corr-1", "text", false);

    assertThat(out).contains("rcv_pp001 pause submitted (state: Paused)");
    assertThat(out).contains("[prior: Executing]");
    assertThat(out).contains("[cancelled: inFlight=2 queued=3]");
    assertThat(out).doesNotContain("[replayed]");
    // Four positional args, reasonText LAST, no domain field before idempotencyKey (Reconciliation
    // 4). Actor resolved to local-operator (omitted --actor-identity).
    verify(rs).pause(eq("run_pp001"), eq("idem-pause-key-00000001"), any(), eq("note"));
  }

  @Test
  void pauseReplayTextShowsReplayedWithZeroCounts() {
    RecoveryService rs = mock(RecoveryService.class);
    when(rs.pause(any(), any(), any(), any()))
        .thenReturn(pauseResult("rcv_pp002", WorkflowState.WAITING_FOR_REVIEW, 0, 0, true));

    String out =
        pauseCommands(rs, true)
            .pause("run_pp002", null, "idem-pause-key-00000001", null, "corr-1", "text", false);

    assertThat(out).contains("rcv_pp002 pause submitted (state: Paused)");
    assertThat(out).contains("[prior: WaitingForReview]");
    assertThat(out).contains("[cancelled: inFlight=0 queued=0]");
    assertThat(out).contains("[replayed]");
  }

  @Test
  void pauseTextOmitsPriorSegmentWhenPriorStateNull() {
    // ⚠️ Reconciliation 6 — priorState may be null on a degenerate replay; renderPauseText
    // null-guards the [prior: …] segment (the cancellation-count segment stays).
    RecoveryService rs = mock(RecoveryService.class);
    when(rs.pause(any(), any(), any(), any()))
        .thenReturn(pauseResult("rcv_pp003", null, 0, 0, true));

    String out =
        pauseCommands(rs, true)
            .pause("run_pp003", "note", "idem-pause-key-00000001", null, "corr-1", "text", false);

    assertThat(out).contains("rcv_pp003 pause submitted (state: Paused)");
    assertThat(out).doesNotContain("[prior:");
    assertThat(out).contains("[cancelled: inFlight=0 queued=0]");
  }

  @Test
  void pauseVerboseAppendsCorrelationIdAndGeneratedKeyWhenKeyOmitted() {
    RecoveryService rs = mock(RecoveryService.class);
    when(rs.pause(any(), any(), any(), any()))
        .thenReturn(pauseResult("rcv_pp004", WorkflowState.EXECUTING, 1, 0, false));

    // Interactive TTY + omitted --idempotency-key ⇒ the key is auto-generated; verbose surfaces it.
    String out =
        pauseCommands(rs, true).pause("run_pp004", "note", null, null, "corr-1", "text", true);

    assertThat(out).contains("[correlation-id: corr-1]");
    assertThat(out).contains("[generated-idempotency-key: idem-generated-0000001]");
    verify(rs).pause(eq("run_pp004"), eq("idem-generated-0000001"), any(), eq("note"));
  }

  @Test
  void pauseJsonEmitsStableSchemaWithoutVerboseFooterAndNullTolerantPrior() {
    RecoveryService rs = mock(RecoveryService.class);
    when(rs.pause(any(), any(), any(), any()))
        .thenReturn(pauseResult("rcv_pp005", null, 0, 0, true));

    // --verbose must NOT glue a "correlation-id" footer onto JSON (would break operator-pause.v1).
    String out =
        pauseCommands(rs, true)
            .pause("run_pp005", "note", "idem-pause-key-00000001", null, "corr-1", "json", true);

    assertThat(out).doesNotContain("[correlation-id:");
    JsonNode body = readJson(out);
    assertThat(body.path("schemaVersion").asInt()).isEqualTo(1);
    assertThat(body.path("workflowRunId").asText()).isEqualTo("run_pp005");
    assertThat(body.path("currentState").asText()).isEqualTo("Paused");
    // priorState null-tolerant → serialized as JSON null.
    assertThat(body.path("priorState").isNull()).isTrue();
    assertThat(body.path("recoveryActionId").asText()).isEqualTo("rcv_pp005");
    assertThat(body.path("cancelledInFlightCount").asInt()).isEqualTo(0);
    assertThat(body.path("cancelledQueuedCount").asInt()).isEqualTo(0);
    assertThat(body.path("replayed").asBoolean()).isTrue();
  }

  @Test
  void pauseInvalidFormatRaisesInvalidCommandPayloadBeforeMutating() {
    RecoveryService rs = mock(RecoveryService.class);

    assertThatThrownBy(
            () ->
                pauseCommands(rs, true)
                    .pause("run_pp006", null, "idem-pause-key-00000001", null, null, "xml", false))
        .isInstanceOf(DomainException.class)
        .extracting(e -> ((DomainException) e).errorCode())
        .isEqualTo(DomainErrorCode.INVALID_COMMAND_PAYLOAD);
    // The mutation must not have fired when --format is rejected.
    org.mockito.Mockito.verifyNoInteractions(rs);
  }

  @Test
  void pauseNonInteractiveWithoutKeyRaisesMissingIdempotencyKey() {
    RecoveryService rs = mock(RecoveryService.class);

    assertThatThrownBy(
            () ->
                pauseCommands(rs, false)
                    .pause("run_pp007", null, null, null, "corr-1", "text", false))
        .isInstanceOf(DomainException.class)
        .extracting(e -> ((DomainException) e).errorCode())
        .isEqualTo(DomainErrorCode.MISSING_IDEMPOTENCY_KEY);
    org.mockito.Mockito.verifyNoInteractions(rs);
  }

  @Test
  void pauseEmitsCompletionLogLineSuccess(CapturedOutput output) {
    RecoveryService rs = mock(RecoveryService.class);
    when(rs.pause(any(), any(), any(), any()))
        .thenReturn(pauseResult("rcv_pp008", WorkflowState.EXECUTING, 1, 0, false));

    // A realistic multi-word reason with distinctive tokens — a partial leak of the prose (not just
    // an exact-match on a 4-char token) would fail the doesNotContain assertions below.
    pauseCommands(rs, true)
        .pause(
            "run_pp008",
            "quarantine the flaky upstream fixture",
            "idem-pause-key-00000001",
            null,
            "corr-abc",
            "text",
            false);

    String logged = output.getOut() + output.getErr();
    assertThat(logged)
        .contains("operator command completed")
        .contains("commandName=operator pause")
        .contains("workflowRunId=run_pp008")
        .contains("outcome=success");
    // The free-form reason prose is never logged verbatim (length only) — assert both the full
    // phrase and distinctive tokens are absent, so a partial leak cannot slip through.
    assertThat(logged)
        .doesNotContain("quarantine the flaky upstream fixture")
        .doesNotContain("quarantine")
        .doesNotContain("flaky");
  }

  @Test
  void pauseEmitsCompletionLogLineFailureUnknownOnNonDomainError(CapturedOutput output) {
    // The catch (RuntimeException) branch — any non-DomainException failure from the service (e.g.
    // RecoveryService.pause's IllegalStateException("RunnerAdapter is required")) emits
    // outcome=failure:unknown and rethrows unchanged.
    RecoveryService rs = mock(RecoveryService.class);
    when(rs.pause(any(), any(), any(), any()))
        .thenThrow(new IllegalStateException("RunnerAdapter is required for pause"));

    assertThatThrownBy(
            () ->
                pauseCommands(rs, true)
                    .pause(
                        "run_pp010",
                        "note",
                        "idem-pause-key-00000001",
                        null,
                        "corr-abc",
                        "text",
                        false))
        .isInstanceOf(IllegalStateException.class);

    assertThat(output.getOut() + output.getErr())
        .contains("operator command completed")
        .contains("commandName=operator pause")
        .contains("workflowRunId=run_pp010")
        .contains("outcome=failure:unknown");
  }

  @Test
  void pauseEmitsCompletionLogLineFailureOnServiceError(CapturedOutput output) {
    RecoveryService rs = mock(RecoveryService.class);
    when(rs.pause(any(), any(), any(), any()))
        .thenThrow(
            new DomainException(DomainErrorCode.MISSING_REASON_TEXT, "missing reason", Map.of()));

    assertThatThrownBy(
            () ->
                pauseCommands(rs, true)
                    .pause(
                        "run_pp009",
                        null,
                        "idem-pause-key-00000001",
                        null,
                        "corr-abc",
                        "text",
                        false))
        .isInstanceOf(DomainException.class);

    assertThat(output.getOut() + output.getErr())
        .contains("operator command completed")
        .contains("commandName=operator pause")
        .contains("outcome=failure:MISSING_REASON_TEXT");
  }

  // ---- Story 4.14 — deliveryline operator classify-failure -------------------------------------

  private OperatorCommands classifyCommands(RecoveryService rs, boolean interactive) {
    CliInteractivityDetector detector = mock(CliInteractivityDetector.class);
    when(detector.isInteractive()).thenReturn(interactive);
    return new OperatorCommands(
        inspection,
        outputs,
        detector,
        rs,
        new IdempotencyKeyValidator(),
        new LocalActorIdentityResolver("local-operator"),
        () -> "corr-fixed",
        () -> "idem-generated-0000001");
  }

  private static ClassifyFailureResult classifyResult(
      String recoveryId, String taxonomyValue, String priorTaxonomyValue, boolean replayed) {
    return new ClassifyFailureResult(
        recoveryId, "evt_" + recoveryId, taxonomyValue, priorTaxonomyValue, "corr-1", replayed);
  }

  @Test
  void classifyTextRendersRecoveryActionAndTaxonomy() {
    RecoveryService rs = mock(RecoveryService.class);
    when(rs.classifyFailure(any(), any(), any(), any(), any()))
        .thenReturn(classifyResult("rcv_cf001", "agent_execution_failure", null, false));

    String out =
        classifyCommands(rs, true)
            .classifyFailure(
                "run_cf001",
                "agent_execution_failure",
                "note",
                "idem-classify-key-00000001",
                null,
                "corr-1",
                "text",
                false);

    assertThat(out).contains("rcv_cf001 classify submitted (taxonomy: agent_execution_failure)");
    // No state segment — classify is pure metadata (Reconciliation 6).
    assertThat(out).doesNotContain("state:");
    assertThat(out).doesNotContain("[prior:");
    assertThat(out).doesNotContain("[replayed]");
    // Five positional args, taxonomy SECOND (before idempotencyKey), reasonText LAST
    // (Reconciliation 7). Actor resolved to local-operator (omitted --actor-identity).
    verify(rs)
        .classifyFailure(
            eq("run_cf001"),
            eq("agent_execution_failure"),
            eq("idem-classify-key-00000001"),
            any(),
            eq("note"));
  }

  @Test
  void classifyReplayTextShowsPriorTaxonomyAndReplayed() {
    RecoveryService rs = mock(RecoveryService.class);
    when(rs.classifyFailure(any(), any(), any(), any(), any()))
        .thenReturn(
            classifyResult("rcv_cf002", "agent_execution_failure", "specification_gap", true));

    String out =
        classifyCommands(rs, true)
            .classifyFailure(
                "run_cf002",
                "agent_execution_failure",
                null,
                "idem-classify-key-00000001",
                null,
                "corr-1",
                "text",
                false);

    assertThat(out).contains("rcv_cf002 classify submitted (taxonomy: agent_execution_failure)");
    assertThat(out).contains("[prior: specification_gap]");
    assertThat(out).contains("[replayed]");
  }

  @Test
  void classifyTextOmitsPriorSegmentWhenPriorTaxonomyNull() {
    // priorTaxonomyValue is null on a first classify; renderClassifyFailureText null-guards the
    // [prior: …] segment.
    RecoveryService rs = mock(RecoveryService.class);
    when(rs.classifyFailure(any(), any(), any(), any(), any()))
        .thenReturn(classifyResult("rcv_cf003", "context_gap", null, false));

    String out =
        classifyCommands(rs, true)
            .classifyFailure(
                "run_cf003",
                "context_gap",
                "note",
                "idem-classify-key-00000001",
                null,
                "corr-1",
                "text",
                false);

    assertThat(out).contains("rcv_cf003 classify submitted (taxonomy: context_gap)");
    assertThat(out).doesNotContain("[prior:");
  }

  @Test
  void classifyVerboseAppendsCorrelationIdAndGeneratedKeyWhenKeyOmitted() {
    RecoveryService rs = mock(RecoveryService.class);
    when(rs.classifyFailure(any(), any(), any(), any(), any()))
        .thenReturn(classifyResult("rcv_cf004", "review_rejection", null, false));

    // Interactive TTY + omitted --idempotency-key ⇒ the key is auto-generated; verbose surfaces it.
    String out =
        classifyCommands(rs, true)
            .classifyFailure(
                "run_cf004", "review_rejection", "note", null, null, "corr-1", "text", true);

    assertThat(out).contains("[correlation-id: corr-1]");
    assertThat(out).contains("[generated-idempotency-key: idem-generated-0000001]");
    verify(rs)
        .classifyFailure(
            eq("run_cf004"),
            eq("review_rejection"),
            eq("idem-generated-0000001"),
            any(),
            eq("note"));
  }

  @Test
  void classifyJsonEmitsStableSchemaWithoutVerboseFooterAndNullTolerantPrior() {
    RecoveryService rs = mock(RecoveryService.class);
    when(rs.classifyFailure(any(), any(), any(), any(), any()))
        .thenReturn(classifyResult("rcv_cf005", "agent_execution_failure", null, true));

    // --verbose must NOT glue a "correlation-id" footer onto JSON (would break
    // operator-classify-failure.v1).
    String out =
        classifyCommands(rs, true)
            .classifyFailure(
                "run_cf005",
                "agent_execution_failure",
                "note",
                "idem-classify-key-00000001",
                null,
                "corr-1",
                "json",
                true);

    assertThat(out).doesNotContain("[correlation-id:");
    JsonNode body = readJson(out);
    assertThat(body.path("schemaVersion").asInt()).isEqualTo(1);
    assertThat(body.path("workflowRunId").asText()).isEqualTo("run_cf005");
    assertThat(body.path("taxonomyValue").asText()).isEqualTo("agent_execution_failure");
    // priorTaxonomyValue null-tolerant → serialized as JSON null.
    assertThat(body.path("priorTaxonomyValue").isNull()).isTrue();
    assertThat(body.path("recoveryActionId").asText()).isEqualTo("rcv_cf005");
    assertThat(body.path("replayed").asBoolean()).isTrue();
    // Reconciliation 6 — classify carries NO state field, even in JSON.
    assertThat(body.has("currentState")).isFalse();
  }

  @Test
  void classifyInvalidFormatRaisesInvalidCommandPayloadBeforeMutating() {
    RecoveryService rs = mock(RecoveryService.class);

    assertThatThrownBy(
            () ->
                classifyCommands(rs, true)
                    .classifyFailure(
                        "run_cf006",
                        "agent_execution_failure",
                        null,
                        "idem-classify-key-00000001",
                        null,
                        null,
                        "xml",
                        false))
        .isInstanceOf(DomainException.class)
        .extracting(e -> ((DomainException) e).errorCode())
        .isEqualTo(DomainErrorCode.INVALID_COMMAND_PAYLOAD);
    org.mockito.Mockito.verifyNoInteractions(rs);
  }

  @Test
  void classifyNonInteractiveWithoutKeyRaisesMissingIdempotencyKey() {
    RecoveryService rs = mock(RecoveryService.class);

    assertThatThrownBy(
            () ->
                classifyCommands(rs, false)
                    .classifyFailure(
                        "run_cf007",
                        "agent_execution_failure",
                        null,
                        null,
                        null,
                        "corr-1",
                        "text",
                        false))
        .isInstanceOf(DomainException.class)
        .extracting(e -> ((DomainException) e).errorCode())
        .isEqualTo(DomainErrorCode.MISSING_IDEMPOTENCY_KEY);
    org.mockito.Mockito.verifyNoInteractions(rs);
  }

  @Test
  void classifyEmitsCompletionLogLineSuccessAndNeverLogsReasonProse(CapturedOutput output) {
    RecoveryService rs = mock(RecoveryService.class);
    when(rs.classifyFailure(any(), any(), any(), any(), any()))
        .thenReturn(classifyResult("rcv_cf008", "agent_execution_failure", null, false));

    classifyCommands(rs, true)
        .classifyFailure(
            "run_cf008",
            "agent_execution_failure",
            "quarantine the flaky upstream fixture",
            "idem-classify-key-00000001",
            null,
            "corr-abc",
            "text",
            false);

    String logged = output.getOut() + output.getErr();
    assertThat(logged)
        .contains("operator command completed")
        .contains("commandName=operator classify-failure")
        .contains("workflowRunId=run_cf008")
        .contains("outcome=success");
    // The applied taxonomy value IS audited (bounded governed registry value).
    assertThat(logged)
        .contains("operator classify-failure applied")
        .contains("taxonomyValue=agent_execution_failure");
    // The free-form reason prose is never logged verbatim (length only).
    assertThat(logged)
        .doesNotContain("quarantine the flaky upstream fixture")
        .doesNotContain("quarantine")
        .doesNotContain("flaky");
  }

  @Test
  void classifyEmitsCompletionLogLineFailureUnknownOnNonDomainError(CapturedOutput output) {
    RecoveryService rs = mock(RecoveryService.class);
    when(rs.classifyFailure(any(), any(), any(), any(), any()))
        .thenThrow(new IllegalStateException("WorkflowRunFailureClassificationPort is required"));

    assertThatThrownBy(
            () ->
                classifyCommands(rs, true)
                    .classifyFailure(
                        "run_cf010",
                        "agent_execution_failure",
                        "note",
                        "idem-classify-key-00000001",
                        null,
                        "corr-abc",
                        "text",
                        false))
        .isInstanceOf(IllegalStateException.class);

    assertThat(output.getOut() + output.getErr())
        .contains("operator command completed")
        .contains("commandName=operator classify-failure")
        .contains("workflowRunId=run_cf010")
        .contains("outcome=failure:unknown");
  }

  @Test
  void classifyEmitsCompletionLogLineFailureOnServiceError(CapturedOutput output) {
    RecoveryService rs = mock(RecoveryService.class);
    when(rs.classifyFailure(any(), any(), any(), any(), any()))
        .thenThrow(
            new DomainException(
                DomainErrorCode.MISSING_TAXONOMY_VALUE, "missing taxonomy", Map.of()));

    assertThatThrownBy(
            () ->
                classifyCommands(rs, true)
                    .classifyFailure(
                        "run_cf009",
                        null,
                        null,
                        "idem-classify-key-00000001",
                        null,
                        "corr-abc",
                        "text",
                        false))
        .isInstanceOf(DomainException.class);

    assertThat(output.getOut() + output.getErr())
        .contains("operator command completed")
        .contains("commandName=operator classify-failure")
        .contains("outcome=failure:MISSING_TAXONOMY_VALUE");
  }

  private static JsonNode readJson(String json) {
    try {
      return new ObjectMapper().readTree(json);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
