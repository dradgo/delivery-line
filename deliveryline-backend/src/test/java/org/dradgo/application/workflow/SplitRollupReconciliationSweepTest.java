package org.dradgo.application.workflow;

import static org.assertj.core.api.Assertions.assertThat;
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
import org.dradgo.application.workflow.SplitRollupReconciliationSweep.SweepResult;
import org.dradgo.application.workflow.spi.WorkflowRunReadPort;
import org.dradgo.application.workflow.spi.WorkflowRunSnapshot;
import org.dradgo.domain.registry.WorkflowState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Story 3f-8 (AC1/AC4/AC5) — unit coverage of {@link SplitRollupReconciliationSweep}. */
class SplitRollupReconciliationSweepTest {

  private static final String PARENT_A = "run_split_parentA";
  private static final String PARENT_B = "run_split_parentB";
  private static final String CHILD_A1 = "run_child_a1____";
  private static final String CHILD_A2 = "run_child_a2____";

  private final WorkflowRunReadPort readPort = mock(WorkflowRunReadPort.class);
  private final RunSplitCompletionRollupService rollupService =
      mock(RunSplitCompletionRollupService.class);

  private ListAppender<ILoggingEvent> appender;
  private Logger logger;

  @BeforeEach
  void attachAppender() {
    appender = new ListAppender<>();
    appender.start();
    logger = (Logger) org.slf4j.LoggerFactory.getLogger(SplitRollupReconciliationSweep.class);
    logger.addAppender(appender);
  }

  @AfterEach
  void detachAppender() {
    logger.detachAppender(appender);
    appender.stop();
  }

  private static WorkflowRunSnapshot run(String id, WorkflowState state, String parentId) {
    return new WorkflowRunSnapshot(id, state, null, 1L, 0, false, null, parentId);
  }

  private SplitRollupReconciliationSweep newSweep(RollupSweepProperties properties) {
    return new SplitRollupReconciliationSweep(readPort, rollupService, properties);
  }

  @Test
  void recoversEachStrandedParentAndLogsSweepMarkerAndSummary() {
    RollupSweepProperties props = new RollupSweepProperties(true, 60_000L, 100);
    when(readPort.findStrandedSplitParents(100))
        .thenReturn(
            List.of(
                run(PARENT_A, WorkflowState.SPLIT, null),
                run(PARENT_B, WorkflowState.SPLIT, null)));
    when(readPort.findByParentRunId(PARENT_A))
        .thenReturn(
            List.of(
                run(CHILD_A1, WorkflowState.COMPLETED, PARENT_A),
                run(CHILD_A2, WorkflowState.COMPLETED, PARENT_A)));
    when(readPort.findByParentRunId(PARENT_B))
        .thenReturn(List.of(run("run_child_b1____", WorkflowState.COMPLETED, PARENT_B)));
    // After the rollup the re-read shows the parent flipped to Completed (the rollup is mocked, so
    // we
    // stub the post-rollup state directly to exercise the recovered-count logic).
    when(readPort.findByPublicId(PARENT_A))
        .thenReturn(Optional.of(run(PARENT_A, WorkflowState.COMPLETED, null)));
    when(readPort.findByPublicId(PARENT_B))
        .thenReturn(Optional.of(run(PARENT_B, WorkflowState.COMPLETED, null)));

    SweepResult result = newSweep(props).sweep();

    verify(rollupService).rollupParent(PARENT_A, "sweep:" + PARENT_A);
    verify(rollupService).rollupParent(PARENT_B, "sweep:" + PARENT_B);
    assertThat(result.found()).isEqualTo(2);
    assertThat(result.recovered()).isEqualTo(2);
    assertThat(result.batchLimitHit()).isFalse();
    // AC5 — sweep-vs-hook marker per recovery + per-tick summary.
    assertThat(appender.list)
        .anyMatch(
            e -> e.getFormattedMessage().contains("split-rollup SWEEP recovering stranded parent"));
    assertThat(appender.list)
        .anyMatch(
            e ->
                e.getLevel() == Level.INFO
                    && e.getFormattedMessage()
                        .contains("split-rollup SWEEP tick complete found=2 recovered=2"));
  }

  @Test
  void noOpWhenNoStrandedParents() {
    RollupSweepProperties props = RollupSweepProperties.defaults();
    when(readPort.findStrandedSplitParents(props.batchLimit())).thenReturn(List.of());

    SweepResult result = newSweep(props).sweep();

    verify(rollupService, never())
        .rollupParent(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    assertThat(result.found()).isZero();
    assertThat(result.recovered()).isZero();
    assertThat(result.batchLimitHit()).isFalse();
  }

  @Test
  void warnsWhenBatchLimitHit() {
    RollupSweepProperties props = new RollupSweepProperties(true, 60_000L, 2);
    when(readPort.findStrandedSplitParents(2))
        .thenReturn(
            List.of(
                run(PARENT_A, WorkflowState.SPLIT, null),
                run(PARENT_B, WorkflowState.SPLIT, null)));
    when(readPort.findByParentRunId(org.mockito.ArgumentMatchers.anyString()))
        .thenReturn(List.of());
    // Neither parent flips this tick (post-rollup re-read still Split) — recovered stays 0.
    when(readPort.findByPublicId(PARENT_A))
        .thenReturn(Optional.of(run(PARENT_A, WorkflowState.SPLIT, null)));
    when(readPort.findByPublicId(PARENT_B))
        .thenReturn(Optional.of(run(PARENT_B, WorkflowState.SPLIT, null)));

    SweepResult result = newSweep(props).sweep();

    assertThat(result.found()).isEqualTo(2);
    assertThat(result.recovered()).isZero();
    assertThat(result.batchLimitHit()).isTrue();
    assertThat(appender.list)
        .anyMatch(
            e ->
                e.getLevel() == Level.WARN
                    && e.getFormattedMessage().contains("split-rollup SWEEP hit batch limit"));
    // A parent that did not flip is flagged (no silent loss), not double-counted as recovered.
    assertThat(appender.list)
        .anyMatch(
            e ->
                e.getLevel() == Level.WARN
                    && e.getFormattedMessage().contains("still Split after rollup attempt"));
  }
}
