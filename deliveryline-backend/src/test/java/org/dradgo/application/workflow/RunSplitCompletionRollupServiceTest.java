package org.dradgo.application.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
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
import java.util.Map;
import java.util.Optional;
import org.dradgo.application.workflow.spi.RunDependencyPort;
import org.dradgo.application.workflow.spi.WorkflowRunReadPort;
import org.dradgo.application.workflow.spi.WorkflowRunSnapshot;
import org.dradgo.domain.registry.WorkflowState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;

/** Story 3f-7 (AC2/AC4/AC7) — rollup behavior of {@link RunSplitCompletionRollupService}. */
class RunSplitCompletionRollupServiceTest {

  private static final String CHILD = "run_child_done111";
  private static final String SIBLING = "run_child_done222";
  private static final String PARENT = "run_split_parent1";

  private final WorkflowRunReadPort readPort = mock(WorkflowRunReadPort.class);
  private final WorkflowTransitionService transitions = mock(WorkflowTransitionService.class);
  private final RunDependencyPort dependencyPort = mock(RunDependencyPort.class);
  private final org.springframework.transaction.PlatformTransactionManager txManager =
      mock(org.springframework.transaction.PlatformTransactionManager.class);
  // Story 3h-0 — the child-driven rollupParentOf now runs through the shared helper's Layer B; use
  // a
  // REAL helper over the same stub tx manager so the REQUIRES_NEW body still runs synchronously.
  private final AfterCommitSideEffectRunner sideEffectRunner =
      new AfterCommitSideEffectRunner(txManager);

  private final RunSplitCompletionRollupService service =
      new RunSplitCompletionRollupService(
          readPort, transitions, dependencyPort, sideEffectRunner, txManager);

  {
    // The REQUIRES_NEW TransactionTemplate needs a non-null status to commit; the body still runs
    // synchronously so the verifications below hold.
    when(txManager.getTransaction(any()))
        .thenReturn(mock(org.springframework.transaction.TransactionStatus.class));
  }

  private ListAppender<ILoggingEvent> appender;
  private Logger logger;
  // Story 3h-0 — the child-driven swallow-and-WARN moved into AfterCommitSideEffectRunner (Layer
  // B),
  // so it logs on the helper's logger, not the service's. The sweep-driven swallow ("(sweep)")
  // stays
  // on the service logger.
  private ListAppender<ILoggingEvent> helperAppender;
  private Logger helperLogger;

  @BeforeEach
  void attachAppender() {
    appender = new ListAppender<>();
    appender.start();
    logger = (Logger) LoggerFactory.getLogger(RunSplitCompletionRollupService.class);
    logger.addAppender(appender);
    helperAppender = new ListAppender<>();
    helperAppender.start();
    helperLogger = (Logger) LoggerFactory.getLogger(AfterCommitSideEffectRunner.class);
    helperLogger.addAppender(helperAppender);
  }

  @AfterEach
  void detachAppender() {
    logger.detachAppender(appender);
    appender.stop();
    helperLogger.detachAppender(helperAppender);
    helperAppender.stop();
  }

  private static WorkflowRunSnapshot run(String id, WorkflowState state, String parentId) {
    return new WorkflowRunSnapshot(id, state, null, 1L, 0, false, null, parentId);
  }

  @Test
  void rollsParentUpWhenAllDirectChildrenCompleted() {
    when(readPort.findByPublicId(CHILD))
        .thenReturn(Optional.of(run(CHILD, WorkflowState.COMPLETED, PARENT)));
    when(readPort.findByPublicId(PARENT))
        .thenReturn(Optional.of(run(PARENT, WorkflowState.SPLIT, null)));
    when(readPort.findByParentRunId(PARENT))
        .thenReturn(
            List.of(
                run(CHILD, WorkflowState.COMPLETED, PARENT),
                run(SIBLING, WorkflowState.COMPLETED, PARENT)));

    service.rollupParentOf(CHILD, null);

    dependencyPortLockTaken();
    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, Object>> details = ArgumentCaptor.forClass(Map.class);
    verify(transitions)
        .transition(
            eq(PARENT),
            eq(WorkflowState.COMPLETED),
            any(),
            eq("split_rollup"),
            eq("split-rollup:" + PARENT),
            details.capture());
    assertThat(details.getValue()).containsEntry("viaSplitRollup", true);
  }

  @Test
  void doesNotRollUpWhenAnyChildNotCompleted() {
    when(readPort.findByPublicId(CHILD))
        .thenReturn(Optional.of(run(CHILD, WorkflowState.COMPLETED, PARENT)));
    when(readPort.findByPublicId(PARENT))
        .thenReturn(Optional.of(run(PARENT, WorkflowState.SPLIT, null)));
    when(readPort.findByParentRunId(PARENT))
        .thenReturn(
            List.of(
                run(CHILD, WorkflowState.COMPLETED, PARENT),
                run(SIBLING, WorkflowState.FAILED, PARENT)));

    service.rollupParentOf(CHILD, null);

    verify(transitions, never()).transition(any(), any(), any(), any(), any(), anyMap());
    assertThat(appender.list)
        .anyMatch(e -> e.getFormattedMessage().contains("split-rollup parent NOT ready"));
  }

  @Test
  void noOpWhenCompletedRunHasNoParent() {
    when(readPort.findByPublicId(CHILD))
        .thenReturn(Optional.of(run(CHILD, WorkflowState.COMPLETED, null)));

    service.rollupParentOf(CHILD, null);

    verify(transitions, never()).transition(any(), any(), any(), any(), any(), anyMap());
  }

  @Test
  void noOpWhenParentNotInSplit() {
    when(readPort.findByPublicId(CHILD))
        .thenReturn(Optional.of(run(CHILD, WorkflowState.COMPLETED, PARENT)));
    when(readPort.findByPublicId(PARENT))
        .thenReturn(Optional.of(run(PARENT, WorkflowState.COMPLETED, null)));

    service.rollupParentOf(CHILD, null);

    verify(transitions, never()).transition(any(), any(), any(), any(), any(), anyMap());
  }

  @Test
  void idempotentSecondInvocationAfterParentAlreadyCompletedIsNoOp() {
    // First child triggered the rollup; on a replay the parent is already Completed.
    when(readPort.findByPublicId(CHILD))
        .thenReturn(Optional.of(run(CHILD, WorkflowState.COMPLETED, PARENT)));
    when(readPort.findByPublicId(PARENT))
        .thenReturn(Optional.of(run(PARENT, WorkflowState.COMPLETED, null)));

    service.rollupParentOf(CHILD, null);

    verify(transitions, never()).transition(any(), any(), any(), any(), any(), anyMap());
  }

  @Test
  void swallowsAndLogsRuntimeExceptionFromTransition() {
    when(readPort.findByPublicId(CHILD))
        .thenReturn(Optional.of(run(CHILD, WorkflowState.COMPLETED, PARENT)));
    when(readPort.findByPublicId(PARENT))
        .thenReturn(Optional.of(run(PARENT, WorkflowState.SPLIT, null)));
    when(readPort.findByParentRunId(PARENT))
        .thenReturn(List.of(run(CHILD, WorkflowState.COMPLETED, PARENT)));
    doThrow(new RuntimeException("boom"))
        .when(transitions)
        .transition(
            eq(PARENT),
            eq(WorkflowState.COMPLETED),
            any(),
            eq("split_rollup"),
            eq("split-rollup:" + PARENT),
            anyMap());

    // Must NOT propagate (the completing run's callback stays intact).
    service.rollupParentOf(CHILD, null);

    // Story 3h-0 — the child-driven swallow now lives in the helper (Layer B), so it logs on the
    // helper's logger with the shared "(completion intact)" wording.
    assertThat(helperAppender.list)
        .anyMatch(
            e ->
                e.getLevel() == Level.WARN
                    && e.getFormattedMessage()
                        .contains("split-rollup swallowed an error (completion intact)"));
  }

  // ---- Story 3f-8: parent-targeted entry (the reconciliation sweep's reuse of the same gate) ----

  @Test
  void rollupParentRollsUpThroughTheSameGate() {
    when(readPort.findByPublicId(PARENT))
        .thenReturn(Optional.of(run(PARENT, WorkflowState.SPLIT, null)));
    when(readPort.findByParentRunId(PARENT))
        .thenReturn(
            List.of(
                run(CHILD, WorkflowState.COMPLETED, PARENT),
                run(SIBLING, WorkflowState.COMPLETED, PARENT)));

    service.rollupParent(PARENT, "corr-sweep");

    dependencyPortLockTaken();
    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, Object>> details = ArgumentCaptor.forClass(Map.class);
    verify(transitions)
        .transition(
            eq(PARENT),
            eq(WorkflowState.COMPLETED),
            any(),
            eq("split_rollup"),
            eq("split-rollup:" + PARENT),
            details.capture());
    assertThat(details.getValue()).containsEntry("viaSplitRollup", true);
  }

  @Test
  void rollupParentIsNoOpWhenParentNotInSplit() {
    when(readPort.findByPublicId(PARENT))
        .thenReturn(Optional.of(run(PARENT, WorkflowState.COMPLETED, null)));

    service.rollupParent(PARENT, "corr-sweep");

    verify(transitions, never()).transition(any(), any(), any(), any(), any(), anyMap());
  }

  @Test
  void rollupParentSwallowsAndLogsRuntimeException() {
    when(readPort.findByPublicId(PARENT))
        .thenReturn(Optional.of(run(PARENT, WorkflowState.SPLIT, null)));
    when(readPort.findByParentRunId(PARENT))
        .thenReturn(List.of(run(CHILD, WorkflowState.COMPLETED, PARENT)));
    doThrow(new RuntimeException("boom"))
        .when(transitions)
        .transition(
            eq(PARENT),
            eq(WorkflowState.COMPLETED),
            any(),
            eq("split_rollup"),
            eq("split-rollup:" + PARENT),
            anyMap());

    // Must NOT propagate — the sweep loop continues to the next stranded parent.
    service.rollupParent(PARENT, "corr-sweep");

    assertThat(appender.list)
        .anyMatch(
            e ->
                e.getLevel() == Level.WARN
                    && e.getFormattedMessage().contains("split-rollup swallowed an error (sweep)"));
  }

  private void dependencyPortLockTaken() {
    verify(dependencyPort).lockDependencyGraph();
  }
}
