package org.dradgo.adapters.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.dradgo.application.workflow.WorkflowInspectionService.LatestArtifactView;
import org.dradgo.application.workflow.WorkflowInspectionService.LinkedTicketView;
import org.dradgo.application.workflow.WorkflowInspectionService.WorkflowEventView;
import org.dradgo.application.workflow.WorkflowInspectionService.WorkflowHistoryView;
import org.dradgo.application.workflow.WorkflowInspectionService.WorkflowStatusView;
import org.dradgo.domain.registry.WorkflowState;
import org.junit.jupiter.api.Test;

/**
 * Byte-exact snapshot tests for the {@code status} / {@code history} text renderers. The
 * application service's view records carry deterministic fields and the renderer is pure — any
 * surface drift requires a deliberate snapshot bump.
 */
class WorkflowCommandOutputsTextTest {

  private final WorkflowCommandOutputs outputs =
      new WorkflowCommandOutputs(new ObjectMapper().findAndRegisterModules());

  @Test
  void statusTextRenderingMatchesSnapshot() {
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
            "await_outcome");

    String rendered = outputs.renderStatusText(view);
    String expected =
        String.join(
            "\n",
            "current state: Executing",
            "current actor: alex/human",
            "last event type: workflow.stateChanged",
            "last event timestamp: 2026-05-13T10:00:00Z",
            "latest artifact spec v2",
            "linked ticket: linear:LIN-101",
            "next safe action: await_outcome");
    assertEquals(expected, rendered);
  }

  @Test
  void statusTextRendersFailureDiagnosticsBlockOnFailedRun() {
    WorkflowStatusView view =
        new WorkflowStatusView(
            "run_failed12345",
            WorkflowState.FAILED,
            "alex",
            "human",
            "workflow.stateChanged",
            OffsetDateTime.parse("2026-05-13T10:00:00Z"),
            List.of(),
            null,
            "execution",
            "Executing",
            OffsetDateTime.parse("2026-05-13T10:00:00Z"),
            "runner_timeout",
            OffsetDateTime.parse("2026-05-13T09:59:30Z"),
            "retry");

    String rendered = outputs.renderStatusText(view);
    String expected =
        String.join(
            "\n",
            "current state: Failed",
            "current actor: alex/human",
            "last event type: workflow.stateChanged",
            "last event timestamp: 2026-05-13T10:00:00Z",
            "failed stage: execution",
            "last successful stage: Executing",
            "failure timestamp: 2026-05-13T10:00:00Z",
            "failure category: runner_timeout",
            "last activity timestamp: 2026-05-13T09:59:30Z",
            "next safe action: retry");
    assertEquals(expected, rendered);
  }

  @Test
  void statusTextOmitsArtifactsAndLinkSectionsWhenAbsent() {
    WorkflowStatusView view =
        new WorkflowStatusView(
            "run_minstatus12345",
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
            "await_outcome");

    String rendered = outputs.renderStatusText(view);
    String expected =
        String.join(
            "\n",
            "current state: Inbox",
            "current actor: (none)",
            "last event type: (none)",
            "last event timestamp: (none)",
            "next safe action: await_outcome");
    assertEquals(expected, rendered);
  }

  @Test
  void historyTextRenderingMatchesSnapshot() {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("linearTicketReference", "LIN-101");
    details.put("correlationId", "corr-1");
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
                    details),
                new WorkflowEventView(
                    "evt_e2def1234",
                    "approval.rejected",
                    "WaitingForSpecApproval",
                    "Investigating",
                    "alex",
                    "human",
                    "needs detail",
                    "runner_timeout",
                    true,
                    OffsetDateTime.parse("2026-05-13T10:01:00Z"),
                    Map.of())));

    String rendered = outputs.renderHistoryText(view);
    String expected =
        String.join(
            "\n",
            "2026-05-13T10:00:00Z workflow.stateChanged alex/human (none)->Inbox reason=\"workflow submitted\" details={linearTicketReference=LIN-101, correlationId=corr-1}",
            "2026-05-13T10:01:00Z approval.rejected alex/human WaitingForSpecApproval->Investigating reason=\"needs detail\" failureCategory=runner_timeout [intervention]");
    assertEquals(expected, rendered);
  }

  @Test
  void historyTextRenderingForFailedAndRetriedTimelineExposesFailureCategoryAndRecoveryRetried() {
    WorkflowHistoryView view =
        new WorkflowHistoryView(
            "run_failretried",
            List.of(
                new WorkflowEventView(
                    "evt_failed-12345",
                    "workflow.stateChanged",
                    "Executing",
                    "Failed",
                    "system",
                    "system",
                    "runner failure",
                    "runner_timeout",
                    false,
                    OffsetDateTime.parse("2026-05-13T10:00:00Z"),
                    Map.of()),
                new WorkflowEventView(
                    "evt_runfail-1234",
                    "runner.failed",
                    null,
                    null,
                    "system",
                    "system",
                    null,
                    "runner_timeout",
                    false,
                    OffsetDateTime.parse("2026-05-13T10:00:01Z"),
                    Map.of()),
                new WorkflowEventView(
                    "evt_retried-1234",
                    "recovery.retried",
                    "Failed",
                    "Executing",
                    "alex",
                    "human",
                    "retry from failed execution",
                    null,
                    true,
                    OffsetDateTime.parse("2026-05-13T10:05:00Z"),
                    Map.of())));

    String rendered = outputs.renderHistoryText(view);
    String[] lines = rendered.split("\n");
    assertEquals(3, lines.length);
    // AC6 regression: the failure transition carries `failureCategory=runner_timeout` and the
    // terminal line of a retried history is the `recovery.retried` event with the
    // `[intervention]` marker.
    assertEquals(
        "2026-05-13T10:00:00Z workflow.stateChanged system/system Executing->Failed reason=\"runner failure\" failureCategory=runner_timeout",
        lines[0]);
    assertEquals(
        "2026-05-13T10:00:01Z runner.failed system/system (none)->(none) failureCategory=runner_timeout",
        lines[1]);
    assertEquals(
        "2026-05-13T10:05:00Z recovery.retried alex/human Failed->Executing reason=\"retry from failed execution\" [intervention]",
        lines[2]);
  }

  @Test
  void historyTextEscapesControlCharactersInsideDetailsValuesSoOneEventStaysOnOneLine() {
    // Operator-supplied --correlation-id that smuggled in CR/LF/TAB. Even though the CLI
    // strips control chars in MDC, the persisted workflow_events.details may have carried
    // a raw value through an earlier path. The renderer is the last line of defense (review
    // F7) and must escape so the per-line history parser does not see synthetic events.
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("correlationId", "abc\nrogue-line\twith-tab");
    WorkflowHistoryView view =
        new WorkflowHistoryView(
            "run_inject01234",
            List.of(
                new WorkflowEventView(
                    "evt_inject-1234",
                    "workflow.stateChanged",
                    null,
                    "Inbox",
                    "alex",
                    "human",
                    "workflow submitted",
                    null,
                    false,
                    OffsetDateTime.parse("2026-05-13T10:00:00Z"),
                    details)));

    String rendered = outputs.renderHistoryText(view);
    // Exactly one line — the rendered output must not be split by the raw \n in the value.
    assertEquals(1, rendered.split("\n").length);
    assertEquals(
        "2026-05-13T10:00:00Z workflow.stateChanged alex/human (none)->Inbox reason=\"workflow submitted\" details={correlationId=abc\\nrogue-line\\twith-tab}",
        rendered);
  }

  @Test
  void historyTextRendersNoneWhenNonStateEventsHaveNoResultingState() {
    WorkflowHistoryView view =
        new WorkflowHistoryView(
            "run_histnull12345",
            List.of(
                new WorkflowEventView(
                    "evt_artifact1234",
                    "artifact.failed",
                    null,
                    null,
                    "alex",
                    "human",
                    "artifact upload failed",
                    "runner_timeout",
                    false,
                    OffsetDateTime.parse("2026-05-13T10:02:00Z"),
                    Map.of())));

    String rendered = outputs.renderHistoryText(view);
    assertEquals(
        "2026-05-13T10:02:00Z artifact.failed alex/human (none)->(none) reason=\"artifact upload failed\" failureCategory=runner_timeout",
        rendered);
  }
}
