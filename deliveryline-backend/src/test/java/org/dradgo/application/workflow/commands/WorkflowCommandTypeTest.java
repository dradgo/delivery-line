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
        new ApproveSpecCommand(
                "run_x",
                "art_x",
                1,
                1,
                "alex",
                ActorType.HUMAN,
                "k",
                null,
                "product_reviewer",
                null)
            .commandType());
    assertEquals(
        "RejectSpecCommand",
        new RejectSpecCommand(
                "run_x",
                "art_x",
                1,
                1,
                "alex",
                ActorType.HUMAN,
                "k",
                null,
                "product_reviewer",
                org.dradgo.domain.registry.RejectionTaxonomy.MISSING_SCOPE,
                "r")
            .commandType());
    assertEquals(
        "RejectImplementationCommand",
        new RejectImplementationCommand(
                "run_x",
                "art_x",
                1,
                1,
                "alex",
                ActorType.HUMAN,
                "k",
                null,
                "developer",
                org.dradgo.domain.registry.RejectionTaxonomy.INCORRECT_APPROACH,
                "r")
            .commandType());
    assertEquals(
        "SubmitClarificationCommand",
        new SubmitClarificationCommand(
                "run_x", "clr_x", "art_x", 1, "answer", "alex", ActorType.HUMAN, "k", null)
            .commandType());
    assertEquals(
        "RetryWorkflowCommand",
        new RetryWorkflowCommand("run_x", "alex", ActorType.HUMAN, "k", null, null).commandType());
    assertEquals(
        "TakeoverWorkflowCommand",
        new TakeoverWorkflowCommand("run_x", "alex", ActorType.HUMAN, "k", null, null)
            .commandType());
    assertEquals(
        "ResumeWorkflowCommand",
        new ResumeWorkflowCommand(
                "run_x",
                "alex",
                ActorType.HUMAN,
                "k",
                null,
                null,
                org.dradgo.domain.registry.WorkflowState.EXECUTING)
            .commandType());
    assertEquals(
        "ReconcileWorkflowCommand",
        new ReconcileWorkflowCommand(
                "run_x",
                "alex",
                ActorType.HUMAN,
                "k",
                null,
                "icf_x",
                org.dradgo.domain.registry.ReconciliationDecision.ACCEPT_INTERNAL_STATE,
                "reason")
            .commandType());
    assertEquals(
        "RerunFromStepWorkflowCommand",
        new RerunFromStepWorkflowCommand(
                "run_x",
                "alex",
                ActorType.HUMAN,
                "k",
                null,
                "reason",
                org.dradgo.domain.registry.WorkflowState.INVESTIGATING)
            .commandType());
    assertEquals(
        "PauseWorkflowCommand",
        new PauseWorkflowCommand("run_x", "alex", ActorType.HUMAN, "k", null, "reason")
            .commandType());
  }
}
