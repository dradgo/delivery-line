package org.dradgo.application.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import java.util.Optional;
import org.dradgo.application.workflow.spi.RunDependencyPort;
import org.dradgo.application.workflow.spi.WorkflowRunReadPort;
import org.dradgo.application.workflow.spi.WorkflowRunSnapshot;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.WorkflowState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Story 3f-3 — declaration rules, cycle guard, parking, and gated dispatch for {@link
 * RunDependencyService}.
 */
class RunDependencyServiceTest {

  private static final String DEPENDENT = "run_dependent_aaaa";
  private static final String PREREQ = "run_prereq_bbbbbb";

  private final RunDependencyPort port = mock(RunDependencyPort.class);
  private final WorkflowRunReadPort runs = mock(WorkflowRunReadPort.class);
  private final WorkflowTransitionService transitions = mock(WorkflowTransitionService.class);
  private final WorkflowOrchestrationService orchestration =
      mock(WorkflowOrchestrationService.class);

  private final RunDependencyService service =
      new RunDependencyService(port, runs, transitions, orchestration);

  private ListAppender<ILoggingEvent> appender;
  private Logger logger;

  @BeforeEach
  void attachAppender() {
    appender = new ListAppender<>();
    appender.start();
    logger = (Logger) LoggerFactory.getLogger(RunDependencyService.class);
    logger.addAppender(appender);
  }

  @AfterEach
  void detachAppender() {
    logger.detachAppender(appender);
    appender.stop();
  }

  private static WorkflowRunSnapshot snapshot(String id, WorkflowState state) {
    return new WorkflowRunSnapshot(id, state, null, 1L, 0, false);
  }

  private DeclareRunDependenciesCommand command(List<String> dependsOn) {
    return new DeclareRunDependenciesCommand(
        DEPENDENT, dependsOn, "alex", ActorType.HUMAN, "idem-dep-aaaaaaaaaaaaaaaa", "corr-1");
  }

  @Test
  void declaringUnmetDependencyParksDependentInWaitingForDependencies() {
    when(runs.findByPublicId(DEPENDENT))
        .thenReturn(Optional.of(snapshot(DEPENDENT, WorkflowState.INBOX)));
    when(runs.findByPublicId(PREREQ))
        .thenReturn(Optional.of(snapshot(PREREQ, WorkflowState.INVESTIGATING)));
    when(port.wouldCreateCycle(DEPENDENT, PREREQ)).thenReturn(false);
    when(port.findBlockedOn(DEPENDENT))
        .thenReturn(List.of(new BlockedDependencyView(PREREQ, WorkflowState.INVESTIGATING)));
    when(port.graphView(DEPENDENT)).thenReturn(RunDependencyGraphView.empty());

    service.declareDependencies(command(List.of(PREREQ)));

    verify(port).addDependencies(DEPENDENT, List.of(PREREQ));
    verify(transitions)
        .transition(
            eq(DEPENDENT),
            eq(WorkflowState.WAITING_FOR_DEPENDENCIES),
            any(),
            eq("waiting_for_dependencies"),
            eq("wait-deps:" + DEPENDENT));
    assertThat(appender.list)
        .anyMatch(e -> e.getFormattedMessage().contains("run parked in WaitingForDependencies"));
  }

  @Test
  void cycleDeclarationIsRejectedAndLoggedAtWarn() {
    when(runs.findByPublicId(DEPENDENT))
        .thenReturn(Optional.of(snapshot(DEPENDENT, WorkflowState.INBOX)));
    when(runs.findByPublicId(PREREQ))
        .thenReturn(Optional.of(snapshot(PREREQ, WorkflowState.INVESTIGATING)));
    when(port.wouldCreateCycle(DEPENDENT, PREREQ)).thenReturn(true);

    assertThatThrownBy(() -> service.declareDependencies(command(List.of(PREREQ))))
        .isInstanceOf(DomainException.class)
        .extracting(t -> ((DomainException) t).errorCode())
        .isEqualTo(DomainErrorCode.RUN_DEPENDENCY_CYCLE);

    verify(port, never()).addDependencies(any(), any());
    assertThat(appender.list)
        .anyMatch(
            e ->
                e.getLevel() == Level.WARN && e.getFormattedMessage().contains("rejected (cycle)"));
  }

  @Test
  void selfDependencyIsRejectedBeforeAnyWrite() {
    when(runs.findByPublicId(DEPENDENT))
        .thenReturn(Optional.of(snapshot(DEPENDENT, WorkflowState.INBOX)));

    assertThatThrownBy(() -> service.declareDependencies(command(List.of(DEPENDENT))))
        .isInstanceOf(DomainException.class)
        .extracting(t -> ((DomainException) t).errorCode())
        .isEqualTo(DomainErrorCode.INVALID_COMMAND_PAYLOAD);
    verify(port, never()).addDependencies(any(), any());
  }

  @Test
  void emptyDependencyListIsRejected() {
    assertThatThrownBy(() -> service.declareDependencies(command(List.of())))
        .isInstanceOf(DomainException.class)
        .extracting(t -> ((DomainException) t).errorCode())
        .isEqualTo(DomainErrorCode.INVALID_COMMAND_PAYLOAD);
  }

  @Test
  void prerequisiteAlreadyExecutingIsRejected() {
    when(runs.findByPublicId(DEPENDENT))
        .thenReturn(Optional.of(snapshot(DEPENDENT, WorkflowState.INBOX)));
    when(runs.findByPublicId(PREREQ))
        .thenReturn(Optional.of(snapshot(PREREQ, WorkflowState.EXECUTING)));

    assertThatThrownBy(() -> service.declareDependencies(command(List.of(PREREQ))))
        .isInstanceOf(DomainException.class)
        .extracting(t -> ((DomainException) t).errorCode())
        .isEqualTo(DomainErrorCode.INVALID_COMMAND_PAYLOAD);
    verify(port, never()).addDependencies(any(), any());
  }

  @Test
  void dependentNotPreExecutionIsRejected() {
    when(runs.findByPublicId(DEPENDENT))
        .thenReturn(Optional.of(snapshot(DEPENDENT, WorkflowState.EXECUTING)));

    assertThatThrownBy(() -> service.declareDependencies(command(List.of(PREREQ))))
        .isInstanceOf(DomainException.class)
        .extracting(t -> ((DomainException) t).errorCode())
        .isEqualTo(DomainErrorCode.INVALID_COMMAND_PAYLOAD);
  }

  @Test
  void missingDependentRunSurfacesRunNotFound() {
    when(runs.findByPublicId(DEPENDENT)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.declareDependencies(command(List.of(PREREQ))))
        .isInstanceOf(DomainException.class)
        .extracting(t -> ((DomainException) t).errorCode())
        .isEqualTo(DomainErrorCode.RUN_NOT_FOUND);
  }

  @Test
  void gatedDispatchProceedsWhenNoUnmetPrerequisites() {
    when(runs.findByPublicId(DEPENDENT))
        .thenReturn(Optional.of(snapshot(DEPENDENT, WorkflowState.INBOX)));
    when(port.findBlockedOn(DEPENDENT)).thenReturn(List.of());

    RunDependencyService.GatedDispatchOutcome outcome =
        service.dispatchWhenUnblocked(DEPENDENT, "corr-2");

    assertThat(outcome).isEqualTo(RunDependencyService.GatedDispatchOutcome.DISPATCHED);
    verify(orchestration).dispatchSpecGeneration(DEPENDENT, "corr-2");
    verify(transitions, never())
        .transition(any(), eq(WorkflowState.WAITING_FOR_DEPENDENCIES), any(), any(), any());
  }

  @Test
  void gatedDispatchParksWhenPrerequisitesUnmet() {
    when(runs.findByPublicId(DEPENDENT))
        .thenReturn(Optional.of(snapshot(DEPENDENT, WorkflowState.INBOX)));
    when(port.findBlockedOn(DEPENDENT))
        .thenReturn(List.of(new BlockedDependencyView(PREREQ, WorkflowState.INVESTIGATING)));

    RunDependencyService.GatedDispatchOutcome outcome =
        service.dispatchWhenUnblocked(DEPENDENT, "corr-3");

    assertThat(outcome).isEqualTo(RunDependencyService.GatedDispatchOutcome.PARKED);
    verify(orchestration, never()).dispatchSpecGeneration(any(), any());
    verify(transitions)
        .transition(
            eq(DEPENDENT),
            eq(WorkflowState.WAITING_FOR_DEPENDENCIES),
            any(),
            eq("waiting_for_dependencies"),
            eq("wait-deps:" + DEPENDENT));
  }

  @Test
  void gatedDispatchSkipsWhenRunPastPreExecution() {
    // Review 3f-3 P1: a run already dispatched/finished must never be re-dispatched into spec
    // generation (which would attempt an illegal <state> -> Investigating transition).
    when(runs.findByPublicId(DEPENDENT))
        .thenReturn(Optional.of(snapshot(DEPENDENT, WorkflowState.EXECUTING)));

    RunDependencyService.GatedDispatchOutcome outcome =
        service.dispatchWhenUnblocked(DEPENDENT, "corr-4");

    assertThat(outcome).isEqualTo(RunDependencyService.GatedDispatchOutcome.SKIPPED);
    verify(orchestration, never()).dispatchSpecGeneration(any(), any());
    verify(transitions, never()).transition(any(), any(), any(), any(), any());
  }

  @Test
  void duplicateDependencyIdsAreDedupedBeforePersist() {
    // Review 3f-3 P9: duplicate-edge idempotency at the service boundary — the same prerequisite
    // listed twice in one declaration is collapsed to a single edge before it reaches the port.
    when(runs.findByPublicId(DEPENDENT))
        .thenReturn(Optional.of(snapshot(DEPENDENT, WorkflowState.INBOX)));
    when(runs.findByPublicId(PREREQ))
        .thenReturn(Optional.of(snapshot(PREREQ, WorkflowState.INVESTIGATING)));
    when(port.wouldCreateCycle(DEPENDENT, PREREQ)).thenReturn(false);
    when(port.findBlockedOn(DEPENDENT)).thenReturn(List.of());
    when(port.graphView(DEPENDENT)).thenReturn(RunDependencyGraphView.empty());

    service.declareDependencies(command(List.of(PREREQ, PREREQ)));

    verify(port).addDependencies(DEPENDENT, List.of(PREREQ));
  }

  @Test
  void archivedDependentIsRejectedBeforeAnyWrite() {
    // Review 3f-3 P9: an archived dependent cannot gain dependencies; rejected before any edge
    // write.
    when(runs.findByPublicId(DEPENDENT))
        .thenReturn(
            Optional.of(
                new WorkflowRunSnapshot(
                    DEPENDENT,
                    WorkflowState.INBOX,
                    java.time.OffsetDateTime.parse("2026-06-28T00:00:00Z"),
                    1L,
                    0,
                    false)));

    assertThatThrownBy(() -> service.declareDependencies(command(List.of(PREREQ))))
        .isInstanceOf(DomainException.class)
        .extracting(t -> ((DomainException) t).errorCode())
        .isEqualTo(DomainErrorCode.INVALID_COMMAND_PAYLOAD);
    verify(port, never()).addDependencies(any(), any());
  }
}
