package org.dradgo.application.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.dradgo.application.project.ProjectRuntimeConfigResolver;
import org.dradgo.application.runner.RunnerBroker;
import org.dradgo.application.workflow.commands.ApproveDeliveryCommand;
import org.dradgo.application.workflow.spi.WorkflowEventReadPort;
import org.dradgo.application.workflow.spi.WorkflowEventRecord;
import org.dradgo.application.workflow.spi.WorkflowEventWritePort;
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.PushMode;
import org.dradgo.domain.registry.WorkflowEventType;
import org.dradgo.domain.registry.WorkflowState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Story 3h-4 (Task 8, AC4) Р В Р’В Р В РІР‚В Р В Р’В Р Р†Р вЂљРЎв„ўР В Р вЂ Р В РІР‚С™Р РЋРЎС™ unit
 * coverage for the approve_delivery executor: approve
 * Р В Р’В Р В РІР‚В Р В Р вЂ Р В РІР‚С™Р вЂ™Р’В Р В Р вЂ Р В РІР‚С™Р Р†РІР‚С›РЎС› transition +
 * deferred push resume; manual
 * Р В Р’В Р В РІР‚В Р В Р вЂ Р В РІР‚С™Р вЂ™Р’В Р В Р вЂ Р В РІР‚С™Р Р†РІР‚С›РЎС› transition +
 * delivery.recordedManually event + deferred reviewer-enqueue-only (no git); both return
 * WaitingForReview.
 */
class DeliveryApprovalServiceTest {

  private static final String RUN_ID = "run_delivapprove00000000000000000000";
  private static final String ACTOR = "op-1";
  private static final String IDEM = "idem-deliv-1";
  private static final String CORR = "corr-deliv";

  private WorkflowTransitionService transitionService;
  private ProjectRuntimeConfigResolver runtimeConfigResolver;
  private AfterCommitSideEffectRunner afterCommit;
  private WorkflowEventReadPort workflowEventReadPort;
  private WorkflowEventWritePort workflowEventWritePort;
  private RunnerBroker broker;
  private DeliveryApprovalService service;
  private ListAppender<ILoggingEvent> logAppender;

  @BeforeEach
  void setUp() {
    transitionService = mock(WorkflowTransitionService.class);
    runtimeConfigResolver = mock(ProjectRuntimeConfigResolver.class);
    afterCommit = mock(AfterCommitSideEffectRunner.class);
    workflowEventReadPort = mock(WorkflowEventReadPort.class);
    workflowEventWritePort = mock(WorkflowEventWritePort.class);
    broker = mock(RunnerBroker.class);

    // Fire both afterCommit layers synchronously so the deferred seam reaches the broker inline.
    org.mockito.Mockito.doAnswer(
            inv -> {
              ((Runnable) inv.getArgument(2)).run();
              return null;
            })
        .when(afterCommit)
        .runAfterCommit(any(), any(), any());
    org.mockito.Mockito.doAnswer(
            inv -> {
              ((Runnable) inv.getArgument(2)).run();
              return null;
            })
        .when(afterCommit)
        .runInNewTransaction(any(), any(), any());

    service =
        new DeliveryApprovalService(
            transitionService,
            runtimeConfigResolver,
            afterCommit,
            workflowEventReadPort,
            workflowEventWritePort,
            () -> broker,
            Clock.fixed(Instant.parse("2026-07-09T00:00:00Z"), ZoneOffset.UTC));

    logAppender = new ListAppender<>();
    logAppender.start();
    Logger serviceLogger =
        (Logger) org.slf4j.LoggerFactory.getLogger(DeliveryApprovalService.class);
    serviceLogger.setLevel(Level.INFO);
    serviceLogger.addAppender(logAppender);
  }

  private String logMessages() {
    return logAppender.list.stream()
        .map(ILoggingEvent::getFormattedMessage)
        .collect(Collectors.joining("\n"));
  }

  @Test
  void approveModeTransitionsThenResumesDeliveryPostCommit() {
    when(runtimeConfigResolver.resolvePushMode(RUN_ID)).thenReturn(PushMode.APPROVE);

    WorkflowState result =
        service.approveDelivery(
            new ApproveDeliveryCommand(RUN_ID, ACTOR, ActorType.HUMAN, IDEM, CORR, null));

    assertThat(result).isEqualTo(WorkflowState.WAITING_FOR_REVIEW);
    verify(transitionService)
        .transition(
            eq(RUN_ID),
            eq(WorkflowState.WAITING_FOR_REVIEW),
            any(),
            eq("delivery_approved"),
            eq("delivery-approved:" + RUN_ID),
            anyMap());
    verify(broker).resumeDeliveryTailFromGateOrThrow(RUN_ID, CORR);
    // Approve mode records NO manual event and NEVER routes through the manual seam.
    verify(broker, never()).recordManualDeliveryAndEnqueueReviewer(any(), any());
    verify(workflowEventWritePort, never()).append(any());
    assertThat(logMessages()).contains("approveDelivery accepted (approve)");
  }

  @Test
  void approveModePushFailurePropagatesBeforeAfterCommitDeferral() {
    when(runtimeConfigResolver.resolvePushMode(RUN_ID)).thenReturn(PushMode.APPROVE);
    org.mockito.Mockito.doThrow(new RuntimeException("push rejected"))
        .when(broker)
        .resumeDeliveryTailFromGateOrThrow(RUN_ID, CORR);

    assertThatThrownBy(
            () ->
                service.approveDelivery(
                    new ApproveDeliveryCommand(RUN_ID, ACTOR, ActorType.HUMAN, IDEM, CORR, null)))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("push rejected");

    verify(broker).resumeDeliveryTailFromGateOrThrow(RUN_ID, CORR);
    verify(afterCommit, never()).runAfterCommit(any(), any(), any());
  }

  @Test
  void approvalUsesPushModeCapturedWhenGateWasCreated() {
    when(runtimeConfigResolver.resolvePushMode(RUN_ID)).thenReturn(PushMode.MANUAL);
    when(workflowEventReadPort.findLatestTransitionToState(
            RUN_ID, WorkflowState.WAITING_FOR_DELIVERY))
        .thenReturn(
            Optional.of(
                new WorkflowEventRecord(
                    "evt_delivery_gate",
                    RUN_ID,
                    WorkflowEventType.WORKFLOW_STATE_CHANGED,
                    WorkflowState.EXECUTING,
                    WorkflowState.WAITING_FOR_DELIVERY,
                    "system",
                    ActorType.SYSTEM,
                    "delivery gate",
                    null,
                    false,
                    Instant.parse("2026-07-09T00:00:00Z").atOffset(ZoneOffset.UTC),
                    Map.of("pushMode", "approve"))));

    WorkflowState result =
        service.approveDelivery(
            new ApproveDeliveryCommand(RUN_ID, ACTOR, ActorType.HUMAN, IDEM, CORR, null));

    assertThat(result).isEqualTo(WorkflowState.WAITING_FOR_REVIEW);
    verify(broker).resumeDeliveryTailFromGateOrThrow(RUN_ID, CORR);
    verify(broker, never()).recordManualDeliveryAndEnqueueReviewer(any(), any());
    verify(workflowEventWritePort, never()).append(any());
  }

  @Test
  void manualModeTransitionsAppendsEventAndEnqueuesReviewerWithoutGit() {
    when(runtimeConfigResolver.resolvePushMode(RUN_ID)).thenReturn(PushMode.MANUAL);

    WorkflowState result =
        service.approveDelivery(
            new ApproveDeliveryCommand(RUN_ID, ACTOR, ActorType.HUMAN, IDEM, CORR, null));

    assertThat(result).isEqualTo(WorkflowState.WAITING_FOR_REVIEW);
    verify(transitionService)
        .transition(
            eq(RUN_ID),
            eq(WorkflowState.WAITING_FOR_REVIEW),
            any(),
            eq("delivery_approved"),
            eq("delivery-approved:" + RUN_ID),
            anyMap());
    // The out-of-band delivery is recorded as a delivery.recordedManually event...
    verify(workflowEventWritePort)
        .append(
            argThat(
                event ->
                    event.eventType() == WorkflowEventType.DELIVERY_RECORDED_MANUALLY
                        && RUN_ID.equals(event.workflowRunPublicId())));
    // ...and the reviewer is enqueued via the no-git manual seam Р В Р’В Р В РІР‚В Р В Р’В Р Р†Р
    //  вЂљРЎв„ўР В Р вЂ Р В РІР‚С™Р РЋРЎС™ never the push resume.
    verify(broker).recordManualDeliveryAndEnqueueReviewer(RUN_ID, CORR);
    verify(broker, never()).resumeDeliveryTailFromGate(any(), any());
    assertThat(logMessages())
        .contains("approveDelivery accepted (manual)")
        .contains("recorded out-of-band delivery");
  }
}
