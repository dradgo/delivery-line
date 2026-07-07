package org.dradgo.adapters.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.dradgo.application.audit.AuditQueryService;
import org.dradgo.application.audit.AuditQueryService.AuditEventRow;
import org.dradgo.application.audit.AuditQueryService.AuditQueryResult;
import org.dradgo.application.security.DataClassificationService;
import org.dradgo.application.security.RedactionPolicyService;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.FailureCategory;
import org.dradgo.domain.registry.WorkflowEventType;
import org.dradgo.domain.registry.WorkflowState;
import org.dradgo.infrastructure.observability.RedactionLayoutHolder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * Story 4.3 (AC4/AC5/AC10) — {@code deliveryline audit query} CLI rendering + completion log. Mocks
 * {@link AuditQueryService} (the single read seam — AC9) and uses the real {@link
 * WorkflowCommandOutputs} renderer, mirroring {@code OperatorCommandsTest}. Covers text vs JSON,
 * ANSI gating (TTY only), the {@code --ticket}/{@code --run} XOR, typed filter rejections, and the
 * AC10 completion-log line.
 */
@ExtendWith(OutputCaptureExtension.class)
class AuditCommandsTest {

  // Identity RedactionPolicyService into RedactionLayoutHolder so the %redactedMsg converter passes
  // the AC10 completion-log messages through verbatim (same precedent as OperatorCommandsTest).
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
      OffsetDateTime.of(2026, 7, 6, 10, 0, 0, 0, ZoneOffset.UTC);

  private final AuditQueryService service = mock(AuditQueryService.class);
  private final WorkflowCommandOutputs outputs = new WorkflowCommandOutputs(new ObjectMapper());

  private AuditCommands commands(boolean interactive) {
    CliInteractivityDetector detector = mock(CliInteractivityDetector.class);
    when(detector.isInteractive()).thenReturn(interactive);
    return new AuditCommands(service, outputs, detector, () -> "corr-fixed");
  }

  private static AuditQueryResult fixture() {
    return new AuditQueryResult(
        List.of(
            new AuditEventRow(
                "evt_fail0001",
                WorkflowEventType.RUNNER_FAILED.value(),
                "run_abc123",
                "system",
                ActorType.SYSTEM,
                T1,
                WorkflowState.EXECUTING,
                WorkflowState.FAILED,
                FailureCategory.ORPHAN,
                "runner orphaned",
                "cor_xyz",
                "art_100"),
            new AuditEventRow(
                "evt_state002",
                WorkflowEventType.WORKFLOW_STATE_CHANGED.value(),
                "run_abc123",
                "alex",
                ActorType.HUMAN,
                T1,
                WorkflowState.INBOX,
                WorkflowState.PLANNED,
                null,
                null,
                null,
                null)),
        2,
        "cursor_next");
  }

  @Test
  void textRendersHeaderAndRowsWithBracketedLabels() {
    when(service.queryByRun(eq("run_abc123"), any())).thenReturn(fixture());

    String out =
        commands(false)
            .query("", "run_abc123", null, null, null, null, 50, null, "text", null, false);

    assertThat(out).contains("total: 2");
    assertThat(out).contains("nextCursor: cursor_next");
    assertThat(out).contains("[RUNNER.FAILED] evt_fail0001 run_abc123");
    assertThat(out).contains("state=Executing->Failed");
    assertThat(out).contains("failureCategory=orphan");
    assertThat(out).contains("[WORKFLOW.STATECHANGED] evt_state002");
    assertThat(out).doesNotContain(ESC);
  }

  @Test
  void jsonEmitsStableSchemaWithoutAnsiEvenOnTty() {
    when(service.queryByRun(any(), any())).thenReturn(fixture());

    String out =
        commands(true)
            .query("", "run_abc123", null, null, null, null, 50, null, "json", null, false);

    assertThat(out).doesNotContain(ESC);
    JsonNode body = readJson(out);
    assertThat(body.path("schemaVersion").asInt()).isEqualTo(1);
    assertThat(body.path("totalCount").asInt()).isEqualTo(2);
    assertThat(body.path("nextCursor").asText()).isEqualTo("cursor_next");
    assertThat(body.path("events").get(0).path("eventId").asText()).isEqualTo("evt_fail0001");
    assertThat(body.path("events").get(0).path("eventType").asText()).isEqualTo("runner.failed");
    assertThat(body.path("events").get(1).path("failureCategory").isNull()).isTrue();
    assertThat(body.path("events").get(1).path("reason").isNull()).isTrue();
  }

  @Test
  void ansiColorsFailureRowOnTty() {
    when(service.queryByRun(any(), any())).thenReturn(fixture());

    String out =
        commands(true)
            .query("", "run_abc123", null, null, null, null, 50, null, "text", null, false);

    // The failure event (non-null failureCategory) is colored red; the bracketed label survives.
    assertThat(out).contains(ESC + "[31m[RUNNER.FAILED]" + ESC + "[0m");
  }

  @Test
  void ticketRoutesToQueryByTicket() {
    when(service.queryByTicket(eq("LIN-123"), any())).thenReturn(fixture());

    String out =
        commands(false).query("LIN-123", "", null, null, null, null, 50, null, "text", null, false);

    assertThat(out).contains("total: 2");
  }

  @Test
  void bothTicketAndRunRejectedWithInvalidCommandPayload() {
    assertThatThrownBy(
            () ->
                commands(false)
                    .query(
                        "LIN-123",
                        "run_abc123",
                        null,
                        null,
                        null,
                        null,
                        50,
                        null,
                        "text",
                        null,
                        false))
        .isInstanceOf(DomainException.class)
        .extracting(e -> ((DomainException) e).errorCode())
        .isEqualTo(DomainErrorCode.INVALID_COMMAND_PAYLOAD);
  }

  @Test
  void neitherTicketNorRunRejectedWithInvalidCommandPayload() {
    assertThatThrownBy(
            () ->
                commands(false)
                    .query("", "", null, null, null, null, 50, null, "text", null, false))
        .isInstanceOf(DomainException.class)
        .extracting(e -> ((DomainException) e).errorCode())
        .isEqualTo(DomainErrorCode.INVALID_COMMAND_PAYLOAD);
  }

  @Test
  void invalidFormatRaisesInvalidCommandPayload() {
    assertThatThrownBy(
            () ->
                commands(false)
                    .query("", "run_abc123", null, null, null, null, 50, null, "xml", null, false))
        .isInstanceOf(DomainException.class)
        .extracting(e -> ((DomainException) e).errorCode())
        .isEqualTo(DomainErrorCode.INVALID_COMMAND_PAYLOAD);
  }

  @Test
  void malformedSinceTimestampRaisesInvalidAuditFilter() {
    assertThatThrownBy(
            () ->
                commands(false)
                    .query(
                        "",
                        "run_abc123",
                        null,
                        null,
                        "not-a-timestamp",
                        null,
                        50,
                        null,
                        "text",
                        null,
                        false))
        .isInstanceOf(DomainException.class)
        .extracting(e -> ((DomainException) e).errorCode())
        .isEqualTo(DomainErrorCode.INVALID_AUDIT_FILTER);
  }

  @Test
  void serviceInvalidEventTypePropagates() {
    when(service.queryByRun(any(), any()))
        .thenThrow(
            new DomainException(
                DomainErrorCode.INVALID_AUDIT_FILTER,
                "Unknown --event-type value: bogus",
                Map.of()));

    assertThatThrownBy(
            () ->
                commands(false)
                    .query(
                        "", "run_abc123", "bogus", null, null, null, 50, null, "text", null, false))
        .isInstanceOf(DomainException.class)
        .extracting(e -> ((DomainException) e).errorCode())
        .isEqualTo(DomainErrorCode.INVALID_AUDIT_FILTER);
  }

  @Test
  void emitsCompletionLogLineWithScopeAndResultCount(CapturedOutput output) {
    when(service.queryByRun(any(), any())).thenReturn(fixture());

    commands(false)
        .query(
            "",
            "run_abc123",
            "workflow.stateChanged",
            null,
            null,
            null,
            50,
            null,
            "text",
            "corr-abc",
            false);

    assertThat(output.getOut() + output.getErr())
        .contains("audit command completed")
        .contains("correlationId=corr-abc")
        .contains("commandName=audit query")
        .contains("scope=run")
        .contains("resultCount=2")
        .contains("outcome=success");
  }

  @Test
  void completionLogRecordsFailureOutcomeOnServiceError(CapturedOutput output) {
    when(service.queryByRun(any(), any()))
        .thenThrow(new DomainException(DomainErrorCode.RUN_NOT_FOUND, "no such run", Map.of()));

    assertThatThrownBy(
            () ->
                commands(false)
                    .query(
                        "", "run_missing1", null, null, null, null, 50, null, "text", null, false))
        .isInstanceOf(DomainException.class);

    assertThat(output.getOut() + output.getErr())
        .contains("audit command completed")
        .contains("outcome=failure:RUN_NOT_FOUND");
  }

  @Test
  void verboseAppendsResolvedCorrelationId() {
    when(service.queryByRun(any(), any())).thenReturn(fixture());

    String out =
        commands(false)
            .query("", "run_abc123", null, null, null, null, 50, null, "text", null, true);

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
