package org.dradgo.application.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.dradgo.application.runner.RunnerBroker;
import org.dradgo.application.workflow.commands.ApproveLintCommand;
import org.dradgo.application.workflow.commands.RequestLintFixCommand;
import org.dradgo.application.workflow.spi.WorkflowRunRejectionLoopPort;
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.WorkflowState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Story 3h-2 (Task 9, AC5) — unit coverage for the lint-gate operator-action executors: approve →
 * resume the delivery tail (post-commit); request-fix → count bump + escalation-at-cap + transition
 * to Executing + re-dispatch.
 */
class LintApprovalServiceTest {

  private static final String RUN_ID = "run_lintapprove00000000000000000000";
  private static final String ACTOR = "op-1";
  private static final String IDEM = "idem-lint-1";
  private static final String CORR = "corr-lint";

  private WorkflowTransitionService transitionService;
  private WorkflowRunRejectionLoopPort rejectionLoopPort;
  private LintFixEscalationThresholdProvider thresholdProvider;
  private AfterCommitSideEffectRunner afterCommit;
  private RunnerBroker broker;
  private WorkflowOrchestrationService orchestration;
  private LintApprovalService service;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    transitionService = mock(WorkflowTransitionService.class);
    rejectionLoopPort = mock(WorkflowRunRejectionLoopPort.class);
    thresholdProvider = mock(LintFixEscalationThresholdProvider.class);
    afterCommit = mock(AfterCommitSideEffectRunner.class);
    broker = mock(RunnerBroker.class);
    orchestration = mock(WorkflowOrchestrationService.class);

    ObjectProvider<RunnerBroker> brokerProvider = mock(ObjectProvider.class);
    when(brokerProvider.getIfAvailable()).thenReturn(broker);
    ObjectProvider<WorkflowOrchestrationService> orchestrationProvider = mock(ObjectProvider.class);
    when(orchestrationProvider.getIfAvailable()).thenReturn(orchestration);

    // Fire the afterCommit side-effects synchronously (no real tx in a unit test). approveLint
    // composes runAfterCommit over runInNewTransaction (the resume needs its own REQUIRES_NEW tx),
    // so BOTH layers must run their runnable inline for the resume to reach the broker.
    doAnswer(
            inv -> {
              ((Runnable) inv.getArgument(2)).run();
              return null;
            })
        .when(afterCommit)
        .runAfterCommit(any(), any(), any());
    doAnswer(
            inv -> {
              ((Runnable) inv.getArgument(2)).run();
              return null;
            })
        .when(afterCommit)
        .runInNewTransaction(any(), any(), any());

    service =
        new LintApprovalService(
            transitionService,
            rejectionLoopPort,
            thresholdProvider,
            afterCommit,
            brokerProvider,
            orchestrationProvider);
  }

  @Test
  void approveLintTransitionsToWaitingForReviewInTxThenResumesDeliveryPostCommit() {
    WorkflowState result =
        service.approveLint(
            new ApproveLintCommand(RUN_ID, ACTOR, ActorType.HUMAN, IDEM, CORR, null));

    assertThat(result).isEqualTo(WorkflowState.WAITING_FOR_REVIEW);
    // P2 (code-review 2026-07-06): the WaitingForLintApproval -> WaitingForReview transition
    // happens
    // SYNCHRONOUSLY in the command tx (a wrong-state approve throws ILLEGAL_TRANSITION here → 409),
    // then the git-push-bearing delivery resume runs post-commit.
    verify(transitionService)
        .transition(
            eq(RUN_ID),
            eq(WorkflowState.WAITING_FOR_REVIEW),
            any(),
            eq("lint_approved"),
            eq("lint-approved:" + RUN_ID),
            anyMap());
    verify(broker).resumeDeliveryTailFromGate(RUN_ID, CORR);
  }

  @Test
  void requestLintFixBelowCapBumpsCountTransitionsToExecutingAndRedispatchesWithoutEscalating() {
    when(rejectionLoopPort.incrementAndReadLintFixLoopCount(RUN_ID)).thenReturn(1);
    when(thresholdProvider.get()).thenReturn(3);

    WorkflowState result =
        service.requestLintFix(
            new RequestLintFixCommand(RUN_ID, ACTOR, ActorType.HUMAN, IDEM, CORR, "please fix"));

    assertThat(result).isEqualTo(WorkflowState.EXECUTING);
    verify(rejectionLoopPort).incrementAndReadLintFixLoopCount(RUN_ID);
    verify(rejectionLoopPort, never()).markEscalationOnce(any());
    verify(transitionService)
        .transition(
            eq(RUN_ID),
            eq(WorkflowState.EXECUTING),
            any(),
            any(),
            eq("lint-fix:" + RUN_ID + ":1"),
            anyMap());
    verify(orchestration).retryImplementation(RUN_ID, CORR);
  }

  @Test
  void requestLintFixAtCapFlipsTheEscalationMarkerOnceButNeverFails() {
    when(rejectionLoopPort.incrementAndReadLintFixLoopCount(RUN_ID)).thenReturn(3);
    when(thresholdProvider.get()).thenReturn(3);
    when(rejectionLoopPort.markEscalationOnce(RUN_ID)).thenReturn(1);

    WorkflowState result =
        service.requestLintFix(
            new RequestLintFixCommand(RUN_ID, ACTOR, ActorType.HUMAN, IDEM, CORR, null));

    // Cap reached → escalation marker flipped (visibility only); still transitions to Executing and
    // re-dispatches. There is NO FAILED transition (Decision 3).
    assertThat(result).isEqualTo(WorkflowState.EXECUTING);
    verify(rejectionLoopPort).markEscalationOnce(RUN_ID);
    verify(transitionService)
        .transition(eq(RUN_ID), eq(WorkflowState.EXECUTING), any(), any(), any(), anyMap());
    verify(transitionService, never())
        .transition(any(), eq(WorkflowState.FAILED), any(), any(), any(), anyMap());
    verify(orchestration).retryImplementation(RUN_ID, CORR);
  }
}
