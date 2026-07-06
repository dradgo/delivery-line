package org.dradgo.adapters.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.dradgo.application.security.DataClassificationService;
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
    return new OperatorCommands(inspection, outputs, detector, () -> "corr-fixed");
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

  private static JsonNode readJson(String json) {
    try {
      return new ObjectMapper().readTree(json);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
