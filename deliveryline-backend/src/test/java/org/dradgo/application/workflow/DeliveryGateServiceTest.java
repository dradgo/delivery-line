package org.dradgo.application.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.nio.file.Path;
import java.util.Optional;
import java.util.stream.Collectors;
import org.dradgo.application.project.ProjectRuntimeConfigResolver;
import org.dradgo.application.runner.RunnerExecutionService;
import org.dradgo.application.runner.spi.RunnerWorkspaceStore;
import org.dradgo.domain.registry.PushMode;
import org.dradgo.domain.registry.WorkflowState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Story 3h-4 (Task 8, AC2/AC4) — unit coverage for the delivery gate's push-vs-park decision: auto
 * → pass-through (false, no side effects); no workspace → pass-through; manual/approve → finalize
 * the producing rex + transition to WaitingForDelivery + true.
 */
class DeliveryGateServiceTest {

  private static final String RUN_ID = "run_delivgate0000000000000000000000";
  private static final String REX_ID = "rex_delivgate000000000000000000000000";
  private static final String CORR = "corr-deliv";

  private ProjectRuntimeConfigResolver runtimeConfigResolver;
  private RunnerWorkspaceStore workspaceStore;
  private RunnerExecutionService executionService;
  private WorkflowTransitionService transitionService;
  private DeliveryGateService service;
  private Runnable deliverInline;
  private ListAppender<ILoggingEvent> logAppender;

  @BeforeEach
  void setUp() {
    runtimeConfigResolver = mock(ProjectRuntimeConfigResolver.class);
    workspaceStore = mock(RunnerWorkspaceStore.class);
    executionService = mock(RunnerExecutionService.class);
    transitionService = mock(WorkflowTransitionService.class);
    deliverInline = mock(Runnable.class);
    service =
        new DeliveryGateService(
            runtimeConfigResolver, workspaceStore, executionService, transitionService);

    logAppender = new ListAppender<>();
    logAppender.start();
    Logger serviceLogger = (Logger) org.slf4j.LoggerFactory.getLogger(DeliveryGateService.class);
    serviceLogger.setLevel(Level.INFO);
    serviceLogger.addAppender(logAppender);
  }

  private String logMessages() {
    return logAppender.list.stream()
        .map(ILoggingEvent::getFormattedMessage)
        .collect(Collectors.joining("\n"));
  }

  @Test
  void autoModeIsPassThroughAndNeverParks() {
    when(runtimeConfigResolver.resolvePushMode(RUN_ID)).thenReturn(PushMode.AUTO);

    boolean gated = service.tryGateBehindDelivery(RUN_ID, REX_ID, CORR, deliverInline);

    assertThat(gated).isFalse();
    // Pass-through: no rex finalize, no transition, and the gate NEVER runs deliverInline itself.
    verifyNoInteractions(executionService, transitionService, deliverInline);
    assertThat(logMessages()).contains("delivery gate pushMode=auto").contains("inline");
  }

  @Test
  void noWorkspaceIsPassThrough() {
    when(runtimeConfigResolver.resolvePushMode(RUN_ID)).thenReturn(PushMode.APPROVE);
    when(workspaceStore.resolveRepositoryDir(REX_ID)).thenReturn(Optional.empty());

    boolean gated = service.tryGateBehindDelivery(RUN_ID, REX_ID, CORR, deliverInline);

    assertThat(gated).isFalse();
    verifyNoInteractions(executionService, transitionService, deliverInline);
  }

  @Test
  void approveModeFinalizesRexAndParksAtWaitingForDelivery() {
    when(runtimeConfigResolver.resolvePushMode(RUN_ID)).thenReturn(PushMode.APPROVE);
    when(workspaceStore.resolveRepositoryDir(REX_ID)).thenReturn(Optional.of(Path.of("/tmp/repo")));

    boolean gated = service.tryGateBehindDelivery(RUN_ID, REX_ID, CORR, deliverInline);

    assertThat(gated).isTrue();
    verify(executionService).recordCompleted(REX_ID);
    verify(transitionService)
        .transition(
            eq(RUN_ID),
            eq(WorkflowState.WAITING_FOR_DELIVERY),
            any(),
            any(),
            eq("delivery-gate:" + RUN_ID),
            anyMap());
    verify(deliverInline, never()).run();
    assertThat(logMessages())
        .contains("delivery gate pushMode=approve")
        .contains("parked at WaitingForDelivery");
  }

  @Test
  void manualModeParksAtWaitingForDelivery() {
    when(runtimeConfigResolver.resolvePushMode(RUN_ID)).thenReturn(PushMode.MANUAL);
    when(workspaceStore.resolveRepositoryDir(REX_ID)).thenReturn(Optional.of(Path.of("/tmp/repo")));

    boolean gated = service.tryGateBehindDelivery(RUN_ID, REX_ID, CORR, deliverInline);

    assertThat(gated).isTrue();
    verify(executionService).recordCompleted(REX_ID);
    verify(transitionService)
        .transition(
            eq(RUN_ID),
            eq(WorkflowState.WAITING_FOR_DELIVERY),
            any(),
            any(),
            eq("delivery-gate:" + RUN_ID),
            anyMap());
  }
}
