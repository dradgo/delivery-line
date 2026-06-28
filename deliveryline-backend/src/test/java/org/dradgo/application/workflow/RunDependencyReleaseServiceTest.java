package org.dradgo.application.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import org.dradgo.application.workflow.spi.RunDependencyPort;
import org.dradgo.domain.registry.WorkflowState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/** Story 3f-3 (AC6/AC7) — release-on-completion behavior of {@link RunDependencyReleaseService}. */
class RunDependencyReleaseServiceTest {

  private static final String COMPLETED_RUN = "run_prereq_done11";
  private static final String DEPENDENT = "run_dependent_xx1";

  private final RunDependencyPort port = mock(RunDependencyPort.class);
  private final WorkflowTransitionService transitions = mock(WorkflowTransitionService.class);
  private final WorkflowOrchestrationService orchestration =
      mock(WorkflowOrchestrationService.class);
  private final org.springframework.transaction.PlatformTransactionManager txManager =
      mock(org.springframework.transaction.PlatformTransactionManager.class);

  private final RunDependencyReleaseService service =
      new RunDependencyReleaseService(port, transitions, orchestration, txManager);

  {
    // The REQUIRES_NEW TransactionTemplate needs a non-null status to commit; the per-dependent
    // body
    // still runs synchronously so the verifications below hold.
    when(txManager.getTransaction(any()))
        .thenReturn(mock(org.springframework.transaction.TransactionStatus.class));
  }

  private ListAppender<ILoggingEvent> appender;
  private Logger logger;

  @BeforeEach
  void attachAppender() {
    appender = new ListAppender<>();
    appender.start();
    logger = (Logger) LoggerFactory.getLogger(RunDependencyReleaseService.class);
    logger.addAppender(appender);
  }

  @AfterEach
  void detachAppender() {
    logger.detachAppender(appender);
    appender.stop();
  }

  @Test
  void waitingDependentWithAllPrerequisitesCompletedIsReleasedAndDispatched() {
    when(port.findDependents(COMPLETED_RUN))
        .thenReturn(
            List.of(new BlockedDependencyView(DEPENDENT, WorkflowState.WAITING_FOR_DEPENDENCIES)));
    when(port.allPrerequisitesCompleted(DEPENDENT)).thenReturn(true);

    service.releaseDependentsOf(COMPLETED_RUN, "corr-rel");

    verify(transitions)
        .transition(
            eq(DEPENDENT),
            eq(WorkflowState.INVESTIGATING),
            any(),
            eq("dependencies_satisfied"),
            eq("release-deps:" + DEPENDENT));
    verify(orchestration).dispatchSpecGeneration(DEPENDENT, "corr-rel");
  }

  @Test
  void dependentNotWaitingIsSkipped() {
    when(port.findDependents(COMPLETED_RUN))
        .thenReturn(List.of(new BlockedDependencyView(DEPENDENT, WorkflowState.INBOX)));

    service.releaseDependentsOf(COMPLETED_RUN, null);

    verify(transitions, never()).transition(any(), any(), any(), any(), any());
    verify(orchestration, never()).dispatchSpecGeneration(any(), any());
  }

  @Test
  void dependentWithUnmetPrerequisitesIsNotReleased() {
    when(port.findDependents(COMPLETED_RUN))
        .thenReturn(
            List.of(new BlockedDependencyView(DEPENDENT, WorkflowState.WAITING_FOR_DEPENDENCIES)));
    when(port.allPrerequisitesCompleted(DEPENDENT)).thenReturn(false);

    service.releaseDependentsOf(COMPLETED_RUN, null);

    verify(transitions, never()).transition(any(), any(), any(), any(), any());
    verify(orchestration, never()).dispatchSpecGeneration(any(), any());
  }

  @Test
  void oneDependentFailureIsSwallowedAndOthersStillProcessed() {
    String other = "run_dependent_xx2";
    when(port.findDependents(COMPLETED_RUN))
        .thenReturn(
            List.of(
                new BlockedDependencyView(DEPENDENT, WorkflowState.WAITING_FOR_DEPENDENCIES),
                new BlockedDependencyView(other, WorkflowState.WAITING_FOR_DEPENDENCIES)));
    when(port.allPrerequisitesCompleted(any())).thenReturn(true);
    doThrow(new RuntimeException("boom"))
        .when(transitions)
        .transition(
            eq(DEPENDENT),
            eq(WorkflowState.INVESTIGATING),
            any(),
            eq("dependencies_satisfied"),
            eq("release-deps:" + DEPENDENT));

    service.releaseDependentsOf(COMPLETED_RUN, null);

    // The second dependent is still released despite the first throwing.
    verify(orchestration).dispatchSpecGeneration(other, null);
    verify(orchestration, never()).dispatchSpecGeneration(eq(DEPENDENT), any());
    assertThat(appender.list)
        .anyMatch(
            e ->
                e.getLevel() == Level.WARN
                    && e.getFormattedMessage().contains("dependency release swallowed an error"));
  }
}
