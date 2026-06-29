package org.dradgo.application.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import org.dradgo.application.project.ProjectRuntimeConfigResolver;
import org.dradgo.application.runner.queue.QueuedRunnerExecution;
import org.dradgo.application.runner.spi.RunnerExecutionRecordPort;
import org.dradgo.application.runner.spi.RunnerExecutionSnapshot;
import org.dradgo.application.security.RedactionPolicyService;
import org.dradgo.application.security.RedactionResult;
import org.dradgo.application.workflow.SplitProposalCommandSet.DeclineSplitCommand;
import org.dradgo.application.workflow.SplitProposalCommandSet.ReproposeSplitCommand;
import org.dradgo.application.workflow.SplitProposalCommandSet.RequestSplitCommand;
import org.dradgo.application.workflow.spi.SplitProposalReadPort;
import org.dradgo.application.workflow.spi.SplitProposalWritePort;
import org.dradgo.application.workflow.spi.WorkflowEventRecord;
import org.dradgo.application.workflow.spi.WorkflowEventWritePort;
import org.dradgo.application.workflow.spi.WorkflowRunReadPort;
import org.dradgo.application.workflow.spi.WorkflowRunRejectionLoopPort;
import org.dradgo.application.workflow.spi.WorkflowRunSnapshot;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.RunnerExecutionStatus;
import org.dradgo.domain.registry.RunnerStage;
import org.dradgo.domain.registry.WorkflowEventType;
import org.dradgo.domain.registry.WorkflowState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/** Story 3f-4 — unit coverage for the advisory split-proposal service. */
class SplitProposalServiceTest {

  private static final String RUN = "run_split_svc_aaaa";
  private static final String REX = "rex_split_svc_bbbb";

  private final WorkflowRunReadPort runReadPort = Mockito.mock(WorkflowRunReadPort.class);
  private final ProjectRuntimeConfigResolver configResolver =
      Mockito.mock(ProjectRuntimeConfigResolver.class);
  private final WorkflowOrchestrationService orchestration =
      Mockito.mock(WorkflowOrchestrationService.class);
  private final RunnerExecutionRecordPort recordPort =
      Mockito.mock(RunnerExecutionRecordPort.class);
  private final SplitProposalReadPort readPort = Mockito.mock(SplitProposalReadPort.class);
  private final SplitProposalWritePort writePort = Mockito.mock(SplitProposalWritePort.class);
  private final WorkflowRunRejectionLoopPort loopPort =
      Mockito.mock(WorkflowRunRejectionLoopPort.class);
  private final WorkflowEventWritePort eventWritePort = Mockito.mock(WorkflowEventWritePort.class);
  private final RedactionPolicyService redaction = Mockito.mock(RedactionPolicyService.class);

  private SplitProposalService service(int escalationThreshold) {
    return service(escalationThreshold, ComplexTicketFlowProperties.DEFAULT_MAX_SPLIT_DEPTH);
  }

  private SplitProposalService service(int escalationThreshold, int maxSplitDepth) {
    return new SplitProposalService(
        runReadPort,
        configResolver,
        orchestration,
        recordPort,
        readPort,
        writePort,
        loopPort,
        eventWritePort,
        new SplitProposalEscalationThresholdProvider(escalationThreshold),
        redaction,
        new ComplexTicketFlowProperties(maxSplitDepth));
  }

  @BeforeEach
  void setup() {
    when(runReadPort.findByPublicId(RUN))
        .thenReturn(Optional.of(runAt(WorkflowState.WAITING_FOR_SPEC_APPROVAL)));
    when(readPort.currentSplitProposalLoopCount(RUN)).thenReturn(0);
    when(orchestration.enqueueSplitProposalDispatch(eq(RUN), any(), any()))
        .thenReturn(
            new QueuedRunnerExecution(REX, RUN, RunnerStage.REVIEW, 100, "corr", 0L, "evt_x"));
    RedactionResult clean = Mockito.mock(RedactionResult.class);
    when(clean.sanitizedText()).thenReturn("redacted-feedback");
    when(redaction.redact(any(String.class), any())).thenReturn(clean);
  }

  private static WorkflowRunSnapshot runAt(WorkflowState state) {
    return new WorkflowRunSnapshot(RUN, state, null, 1L, 0, false);
  }

  @Test
  void requestEnqueuesSplitDispatchWhenReviewerBoundAndNoOpenProposal() {
    when(configResolver.hasReviewerBinding(RUN)).thenReturn(true);
    when(readPort.findOpenForRun(RUN)).thenReturn(Optional.empty());
    // Review 2026-06-29 (Decision 1): request bumps the loop counter so each attempt gets a
    // DISTINCT dispatch key (a re-request after a decline must not reuse a consumed key).
    when(loopPort.incrementAndReadSplitProposalLoopCount(RUN)).thenReturn(1);

    SplitProposalStatusView view =
        service(3).request(new RequestSplitCommand(RUN, "alex", ActorType.HUMAN, "idem", "corr"));

    assertThat(view.state()).isEqualTo(SplitProposalStatusView.STATE_PENDING);
    assertThat(view.loopCount()).isEqualTo(1);
    verify(loopPort).incrementAndReadSplitProposalLoopCount(RUN);
    ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
    verify(orchestration).enqueueSplitProposalDispatch(eq(RUN), key.capture(), any());
    assertThat(key.getValue()).isEqualTo("split-proposal:" + RUN + ":1");
  }

  @Test
  void requestIsANoOpWhileASplitDispatchIsInFlight() {
    when(configResolver.hasReviewerBinding(RUN)).thenReturn(true);
    when(readPort.findOpenForRun(RUN)).thenReturn(Optional.empty());
    RunnerExecutionSnapshot active = Mockito.mock(RunnerExecutionSnapshot.class);
    when(active.stage()).thenReturn(RunnerStage.REVIEW);
    when(active.idempotencyKey()).thenReturn("split-proposal:" + RUN + ":0");
    when(recordPort.findByWorkflowRunPublicIdAndStatusIn(
            eq(RUN), eq(List.of(RunnerExecutionStatus.PENDING, RunnerExecutionStatus.RUNNING))))
        .thenReturn(List.of(active));

    SplitProposalStatusView view =
        service(3).request(new RequestSplitCommand(RUN, "alex", ActorType.HUMAN, "idem", "corr"));

    // No second dispatch, no double loop-count bump (review 2026-06-29, Decision 1 idempotency).
    assertThat(view.state()).isEqualTo(SplitProposalStatusView.STATE_PENDING);
    verify(orchestration, never()).enqueueSplitProposalDispatch(any(), any(), any());
    verify(loopPort, never()).incrementAndReadSplitProposalLoopCount(any());
  }

  @Test
  void requestDegradesToUnavailableWhenReviewerUnbound() {
    when(configResolver.hasReviewerBinding(RUN)).thenReturn(false);
    when(readPort.findOpenForRun(RUN)).thenReturn(Optional.empty());

    SplitProposalStatusView view =
        service(3).request(new RequestSplitCommand(RUN, "alex", ActorType.HUMAN, "idem", "corr"));

    assertThat(view.state()).isEqualTo(SplitProposalStatusView.STATE_UNAVAILABLE);
    verify(orchestration, never()).enqueueSplitProposalDispatch(any(), any(), any());
  }

  @Test
  void requestRejectsRunOutsideAGate() {
    when(runReadPort.findByPublicId(RUN)).thenReturn(Optional.of(runAt(WorkflowState.EXECUTING)));

    assertThatThrownBy(
            () ->
                service(3)
                    .request(new RequestSplitCommand(RUN, "alex", ActorType.HUMAN, "idem", "corr")))
        .isInstanceOf(DomainException.class)
        .extracting(e -> ((DomainException) e).errorCode())
        .isEqualTo(DomainErrorCode.INVALID_COMMAND_PAYLOAD);
    verify(orchestration, never()).enqueueSplitProposalDispatch(any(), any(), any());
  }

  @Test
  void reproposeSupersedesBumpsLoopEnqueuesAndStoresRedactedFeedback() {
    when(configResolver.hasReviewerBinding(RUN)).thenReturn(true);
    when(readPort.findOpenForRun(RUN))
        .thenReturn(Optional.of(Mockito.mock(SplitProposalView.class)));
    when(loopPort.incrementAndReadSplitProposalLoopCount(RUN)).thenReturn(1);

    SplitProposalStatusView view =
        service(3)
            .repropose(
                new ReproposeSplitCommand(
                    RUN, "split the db layer", "alex", ActorType.HUMAN, "idem", "corr"));

    assertThat(view.state()).isEqualTo(SplitProposalStatusView.STATE_PENDING);
    assertThat(view.loopCount()).isEqualTo(1);
    verify(writePort).supersedeOpenForRun(RUN);
    ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
    verify(orchestration).enqueueSplitProposalDispatch(eq(RUN), key.capture(), any());
    assertThat(key.getValue()).isEqualTo("split-proposal:" + RUN + ":1");
    // Feedback is redacted before persistence and keyed by the reviewer execution.
    verify(redaction).redact(eq("split the db layer"), any());
    verify(writePort).insertFeedback(any(), eq(REX), eq("redacted-feedback"));
  }

  @Test
  void reproposeFlipsEscalationOnceAtThreshold() {
    when(configResolver.hasReviewerBinding(RUN)).thenReturn(true);
    when(readPort.findOpenForRun(RUN))
        .thenReturn(Optional.of(Mockito.mock(SplitProposalView.class)));
    when(loopPort.incrementAndReadSplitProposalLoopCount(RUN)).thenReturn(2);
    when(loopPort.isEscalationMarkerSet(RUN)).thenReturn(false);
    when(loopPort.markEscalationOnce(RUN)).thenReturn(1);

    service(2)
        .repropose(
            new ReproposeSplitCommand(RUN, "again", "alex", ActorType.HUMAN, "idem", "corr"));

    verify(loopPort).markEscalationOnce(RUN);
    ArgumentCaptor<WorkflowEventRecord> evt = ArgumentCaptor.forClass(WorkflowEventRecord.class);
    verify(eventWritePort).append(evt.capture());
    assertThat(evt.getValue().eventType()).isEqualTo(WorkflowEventType.ESCALATION_REQUIRED);
  }

  @Test
  void reproposeDoesNotEscalateBelowThreshold() {
    when(configResolver.hasReviewerBinding(RUN)).thenReturn(true);
    when(readPort.findOpenForRun(RUN))
        .thenReturn(Optional.of(Mockito.mock(SplitProposalView.class)));
    when(loopPort.incrementAndReadSplitProposalLoopCount(RUN)).thenReturn(1);

    service(3)
        .repropose(
            new ReproposeSplitCommand(RUN, "again", "alex", ActorType.HUMAN, "idem", "corr"));

    verify(loopPort, never()).markEscalationOnce(any());
    verify(eventWritePort, never()).append(any());
  }

  @Test
  void reproposeRejectsWhenNoOpenProposalExists() {
    // Review 2026-06-29: re-propose iterates an EXISTING open proposal; with none open it must
    // reject (not silently bump the loop counter toward the escalation threshold).
    when(readPort.findOpenForRun(RUN)).thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                service(3)
                    .repropose(
                        new ReproposeSplitCommand(
                            RUN, "again", "alex", ActorType.HUMAN, "idem", "corr")))
        .isInstanceOf(DomainException.class)
        .extracting(e -> ((DomainException) e).errorCode())
        .isEqualTo(DomainErrorCode.INVALID_COMMAND_PAYLOAD);
    verify(loopPort, never()).incrementAndReadSplitProposalLoopCount(any());
    verify(orchestration, never()).enqueueSplitProposalDispatch(any(), any(), any());
  }

  @Test
  void declineDismissesOpenProposal() {
    SplitProposalStatusView view =
        service(3).decline(new DeclineSplitCommand(RUN, "alex", ActorType.HUMAN, "idem", "corr"));

    assertThat(view.state()).isEqualTo(SplitProposalStatusView.STATE_NONE);
    verify(writePort).dismissOpenForRun(RUN);
  }

  @Test
  void declineRejectedWhileSplitDispatchInFlight() {
    // P3 (review 2026-06-29): decline during the pending window (no open proposal yet, a split
    // dispatch in flight) must be rejected so the harvester cannot resurrect the rejected proposal.
    when(readPort.findOpenForRun(RUN)).thenReturn(Optional.empty());
    RunnerExecutionSnapshot active = Mockito.mock(RunnerExecutionSnapshot.class);
    when(active.stage()).thenReturn(RunnerStage.REVIEW);
    when(active.idempotencyKey()).thenReturn("split-proposal:" + RUN + ":0");
    when(recordPort.findByWorkflowRunPublicIdAndStatusIn(
            eq(RUN), eq(List.of(RunnerExecutionStatus.PENDING, RunnerExecutionStatus.RUNNING))))
        .thenReturn(List.of(active));

    assertThatThrownBy(
            () ->
                service(3)
                    .decline(new DeclineSplitCommand(RUN, "alex", ActorType.HUMAN, "idem", "corr")))
        .isInstanceOf(DomainException.class)
        .extracting(e -> ((DomainException) e).errorCode())
        .isEqualTo(DomainErrorCode.INVALID_COMMAND_PAYLOAD);
    verify(writePort, never()).dismissOpenForRun(any());
  }

  @Test
  void proposalViewReportsPendingWhenActiveSplitDispatchExists() {
    when(readPort.findOpenForRun(RUN)).thenReturn(Optional.empty());
    RunnerExecutionSnapshot active = Mockito.mock(RunnerExecutionSnapshot.class);
    when(active.stage()).thenReturn(RunnerStage.REVIEW);
    when(active.idempotencyKey()).thenReturn("split-proposal:" + RUN + ":0");
    when(recordPort.findByWorkflowRunPublicIdAndStatusIn(
            eq(RUN), eq(List.of(RunnerExecutionStatus.PENDING, RunnerExecutionStatus.RUNNING))))
        .thenReturn(List.of(active));

    SplitProposalStatusView view = service(3).proposalView(RUN);

    assertThat(view.state()).isEqualTo(SplitProposalStatusView.STATE_PENDING);
  }

  // --- Story 3f-7 (AC5) — recursive-split depth cap ---------------------------------------------

  private static final String PARENT = "run_split_parent_cc";
  private static final String ROOT = "run_split_root_dddd";

  /** Chain ROOT(depth 0) <- PARENT(depth 1) <- RUN(depth 2). RUN's split depth is 2. */
  private void wireDepth2Lineage() {
    when(runReadPort.findByPublicId(RUN))
        .thenReturn(
            Optional.of(
                new WorkflowRunSnapshot(
                    RUN,
                    WorkflowState.WAITING_FOR_SPEC_APPROVAL,
                    null,
                    1L,
                    0,
                    false,
                    null,
                    PARENT)));
    when(runReadPort.findByPublicId(PARENT))
        .thenReturn(
            Optional.of(
                new WorkflowRunSnapshot(
                    PARENT, WorkflowState.SPLIT, null, 1L, 0, false, null, ROOT)));
    when(runReadPort.findByPublicId(ROOT))
        .thenReturn(
            Optional.of(
                new WorkflowRunSnapshot(
                    ROOT, WorkflowState.SPLIT, null, 1L, 0, false, null, null)));
  }

  @Test
  void requestRejectsWhenSplitDepthAtCapWithoutOverride() {
    when(configResolver.hasReviewerBinding(RUN)).thenReturn(true);
    when(readPort.findOpenForRun(RUN)).thenReturn(Optional.empty());
    wireDepth2Lineage();

    assertThatThrownBy(
            () ->
                service(3, 2)
                    .request(new RequestSplitCommand(RUN, "alex", ActorType.HUMAN, "idem", "corr")))
        .isInstanceOf(DomainException.class)
        .extracting(e -> ((DomainException) e).errorCode())
        .isEqualTo(DomainErrorCode.SPLIT_DEPTH_LIMIT_EXCEEDED);
    // The guard fires BEFORE any enqueue/LLM dispatch (AC1/AC5).
    verify(orchestration, never()).enqueueSplitProposalDispatch(any(), any(), any());
    verify(loopPort, never()).incrementAndReadSplitProposalLoopCount(any());
    // No override event appended on a plain rejection.
    verify(eventWritePort, never()).append(any());
  }

  @Test
  void requestProceedsBelowCap() {
    when(configResolver.hasReviewerBinding(RUN)).thenReturn(true);
    when(readPort.findOpenForRun(RUN)).thenReturn(Optional.empty());
    when(loopPort.incrementAndReadSplitProposalLoopCount(RUN)).thenReturn(1);
    wireDepth2Lineage();

    // max=5: depth 2 < 5, so the request proceeds unchanged (no depth event, normal enqueue).
    SplitProposalStatusView view =
        service(3, 5)
            .request(new RequestSplitCommand(RUN, "alex", ActorType.HUMAN, "idem", "corr"));

    assertThat(view.state()).isEqualTo(SplitProposalStatusView.STATE_PENDING);
    verify(orchestration).enqueueSplitProposalDispatch(eq(RUN), any(), any());
    verify(eventWritePort, never()).append(any());
  }

  @Test
  void requestAtCapWithOverrideProceedsAndRecordsGovernedHistoryEvent() {
    when(configResolver.hasReviewerBinding(RUN)).thenReturn(true);
    when(readPort.findOpenForRun(RUN)).thenReturn(Optional.empty());
    when(loopPort.incrementAndReadSplitProposalLoopCount(RUN)).thenReturn(1);
    wireDepth2Lineage();

    SplitProposalStatusView view =
        service(3, 2)
            .request(new RequestSplitCommand(RUN, "alex", ActorType.HUMAN, "idem", "corr", true));

    assertThat(view.state()).isEqualTo(SplitProposalStatusView.STATE_PENDING);
    // The deep split proceeds (enqueues) AND the override is recorded in the governed history.
    verify(orchestration).enqueueSplitProposalDispatch(eq(RUN), any(), any());
    ArgumentCaptor<WorkflowEventRecord> evt = ArgumentCaptor.forClass(WorkflowEventRecord.class);
    verify(eventWritePort).append(evt.capture());
    WorkflowEventRecord recorded = evt.getValue();
    assertThat(recorded.eventType()).isEqualTo(WorkflowEventType.SPLIT_DEPTH_OVERRIDE);
    assertThat(recorded.interventionMarker()).isTrue();
    assertThat(recorded.priorState()).isNull();
    assertThat(recorded.resultingState()).isNull();
    assertThat(recorded.details())
        .containsEntry("deepSplitOverride", true)
        .containsEntry("splitDepth", 2)
        .containsEntry("maxSplitDepth", 2);
  }

  @Test
  void requestAtCapWithOverrideDoesNotRecordOverrideWhenProposalAlreadyOpen() {
    // Review 2026-06-29 (Decision 1): the depth-cap rejection stays at the top, but the override
    // event must be recorded ONLY on the proceed path. A replayed deep-split request that finds an
    // already-open proposal is a no-op and must NOT append a duplicate SPLIT_DEPTH_OVERRIDE event.
    when(configResolver.hasReviewerBinding(RUN)).thenReturn(true);
    when(readPort.findOpenForRun(RUN))
        .thenReturn(Optional.of(Mockito.mock(SplitProposalView.class)));
    wireDepth2Lineage();

    SplitProposalStatusView view =
        service(3, 2)
            .request(new RequestSplitCommand(RUN, "alex", ActorType.HUMAN, "idem", "corr", true));

    assertThat(view.state()).isEqualTo(SplitProposalStatusView.STATE_AVAILABLE);
    verify(orchestration, never()).enqueueSplitProposalDispatch(any(), any(), any());
    verify(loopPort, never()).incrementAndReadSplitProposalLoopCount(any());
    // No phantom override appended for a split that never proceeded.
    verify(eventWritePort, never()).append(any());
  }

  @Test
  void requestAtCapWithOverrideDoesNotRecordOverrideWhenReviewerUnbound() {
    // Review 2026-06-29 (Decision 1): a deep-split request that degrades to unavailable (reviewer
    // unbound) performs no split, so it must NOT append a phantom SPLIT_DEPTH_OVERRIDE event.
    when(configResolver.hasReviewerBinding(RUN)).thenReturn(false);
    when(readPort.findOpenForRun(RUN)).thenReturn(Optional.empty());
    wireDepth2Lineage();

    SplitProposalStatusView view =
        service(3, 2)
            .request(new RequestSplitCommand(RUN, "alex", ActorType.HUMAN, "idem", "corr", true));

    assertThat(view.state()).isEqualTo(SplitProposalStatusView.STATE_UNAVAILABLE);
    verify(orchestration, never()).enqueueSplitProposalDispatch(any(), any(), any());
    verify(eventWritePort, never()).append(any());
  }
}
