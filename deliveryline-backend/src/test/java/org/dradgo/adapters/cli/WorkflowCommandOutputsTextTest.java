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

	private final WorkflowCommandOutputs outputs = new WorkflowCommandOutputs(new ObjectMapper().findAndRegisterModules());

	@Test
	void statusTextRenderingMatchesSnapshot() {
		WorkflowStatusView view = new WorkflowStatusView(
			"run_status12345",
			WorkflowState.EXECUTING,
			"alex",
			"human",
			"workflow.stateChanged",
			OffsetDateTime.parse("2026-05-13T10:00:00Z"),
			List.of(new LatestArtifactView("spec", 2, "available")),
			new LinkedTicketView("linear", "LIN-101", "linked"),
			"pending (story 1.18)");

		String rendered = outputs.renderStatusText(view);
		String expected = String.join("\n",
			"current state: Executing",
			"current actor: alex/human",
			"last event type: workflow.stateChanged",
			"last event timestamp: 2026-05-13T10:00:00Z",
			"latest artifact spec v2",
			"linked ticket: linear:LIN-101",
			"next safe action: pending (story 1.18)");
		assertEquals(expected, rendered);
	}

	@Test
	void statusTextOmitsArtifactsAndLinkSectionsWhenAbsent() {
		WorkflowStatusView view = new WorkflowStatusView(
			"run_minstatus12345",
			WorkflowState.INBOX,
			null,
			null,
			null,
			null,
			List.of(),
			null,
			"pending (story 1.18)");

		String rendered = outputs.renderStatusText(view);
		String expected = String.join("\n",
			"current state: Inbox",
			"current actor: (none)",
			"last event type: (none)",
			"last event timestamp: (none)",
			"next safe action: pending (story 1.18)");
		assertEquals(expected, rendered);
	}

	@Test
	void historyTextRenderingMatchesSnapshot() {
		Map<String, Object> details = new LinkedHashMap<>();
		details.put("linearTicketReference", "LIN-101");
		details.put("correlationId", "corr-1");
		WorkflowHistoryView view = new WorkflowHistoryView("run_hist12345", List.of(
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
		String expected = String.join("\n",
			"2026-05-13T10:00:00Z workflow.stateChanged alex/human (none)->Inbox reason=\"workflow submitted\" details={linearTicketReference=LIN-101, correlationId=corr-1}",
			"2026-05-13T10:01:00Z approval.rejected alex/human WaitingForSpecApproval->Investigating reason=\"needs detail\" [intervention]");
		assertEquals(expected, rendered);
	}

	@Test
	void historyTextRendersNoneWhenNonStateEventsHaveNoResultingState() {
		WorkflowHistoryView view = new WorkflowHistoryView("run_histnull12345", List.of(
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
			"2026-05-13T10:02:00Z artifact.failed alex/human (none)->(none) reason=\"artifact upload failed\"",
			rendered);
	}
}
