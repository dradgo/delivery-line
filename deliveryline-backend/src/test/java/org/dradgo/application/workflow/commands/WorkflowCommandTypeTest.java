package org.dradgo.application.workflow.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.dradgo.domain.registry.ActorType;
import org.junit.jupiter.api.Test;

// Pins the literal commandType() values that are persisted to workflow_events.details.commandType
// so future record renames break loudly here rather than silently corrupting historical events.
class WorkflowCommandTypeTest {

	@Test
	void commandTypeLiteralsArePinned() {
		assertEquals(
			"SubmitWorkflowCommand",
			new SubmitWorkflowCommand("alex", ActorType.HUMAN, "k", null, "LIN-1").commandType());
		assertEquals(
			"ApproveSpecCommand",
			new ApproveSpecCommand("run_x", "art_x", 1, 1, "alex", ActorType.HUMAN, "k", null).commandType());
		assertEquals(
			"RejectSpecCommand",
			new RejectSpecCommand("run_x", "art_x", 1, 1, "alex", ActorType.HUMAN, "k", null, "r").commandType());
		assertEquals(
			"RetryWorkflowCommand",
			new RetryWorkflowCommand("run_x", "alex", ActorType.HUMAN, "k", null, null).commandType());
		assertEquals(
			"TakeoverWorkflowCommand",
			new TakeoverWorkflowCommand("run_x", "alex", ActorType.HUMAN, "k", null, null).commandType());
	}
}
