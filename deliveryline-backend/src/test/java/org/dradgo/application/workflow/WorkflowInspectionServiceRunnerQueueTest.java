package org.dradgo.application.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.dradgo.application.approval.spi.ApprovalReadPort;
import org.dradgo.application.artifact.spi.ArtifactPayloadStore;
import org.dradgo.application.artifact.spi.ArtifactRecordPort;
import org.dradgo.application.clarification.spi.ClarificationReadPort;
import org.dradgo.application.integration.IntegrationLinkService;
import org.dradgo.application.recovery.RecoveryService;
import org.dradgo.application.runner.RunnerProperties;
import org.dradgo.application.runner.RunnerWorkerPoolProperties;
import org.dradgo.application.runner.spi.RunnerExecutionRecordPort;
import org.dradgo.application.runner.spi.RunnerExecutionSnapshot;
import org.dradgo.application.runner.spi.RunnerQueueCounts;
import org.dradgo.application.runner.spi.RunnerScratchStore;
import org.dradgo.application.security.RedactionPolicyService;
import org.dradgo.application.workflow.WorkflowInspectionService.RunnerQueueStatus;
import org.dradgo.application.workflow.spi.WorkflowEventReadPort;
import org.dradgo.application.workflow.spi.WorkflowRunReadPort;
import org.dradgo.domain.registry.RunnerExecutionStatus;
import org.dradgo.domain.registry.RunnerStage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Story 3.19 (AC1/AC3) — mock-based unit coverage of {@link
 * WorkflowInspectionService#getRunnerQueueStatus(String)}: the read-port aggregate maps to the
 * typed view (poolSize from config, {@code idleWorkers = max(0, poolSize − activeWorkers)}, workers
 * reconstructed from leased rows), and the non-zero-stale {@code WARN} log line is emitted (the
 * Logging Requirements test contract). The real-SQL behaviour is proven separately by {@code
 * WorkflowInspectionRunnerQueueIT}.
 */
class WorkflowInspectionServiceRunnerQueueTest {

  private static final OffsetDateTime DISPATCHED =
      OffsetDateTime.of(2026, 6, 15, 12, 0, 0, 0, ZoneOffset.UTC);

  private final RunnerExecutionRecordPort recordPort = mock(RunnerExecutionRecordPort.class);
  private WorkflowInspectionService service;
  private ListAppender<ILoggingEvent> appender;
  private Logger serviceLogger;

  @BeforeEach
  void setUp() {
    service =
        new WorkflowInspectionService(
            mock(WorkflowRunReadPort.class),
            mock(WorkflowEventReadPort.class),
            mock(ArtifactRecordPort.class),
            mock(ArtifactPayloadStore.class),
            mock(ApprovalReadPort.class),
            mock(IntegrationLinkService.class),
            mock(RedactionPolicyService.class),
            mock(RecoveryService.class),
            recordPort,
            mock(RunnerScratchStore.class),
            mock(ClarificationReadPort.class),
            mock(org.dradgo.application.recovery.spi.RecoveryActionRecordPort.class),
            RunnerProperties.defaults(),
            RunnerWorkerPoolProperties.defaults());
    serviceLogger = (Logger) LoggerFactory.getLogger(WorkflowInspectionService.class);
    appender = new ListAppender<>();
    appender.start();
    serviceLogger.addAppender(appender);
  }

  @AfterEach
  void tearDown() {
    serviceLogger.detachAppender(appender);
  }

  private RunnerExecutionSnapshot leasedRow() {
    return new RunnerExecutionSnapshot(
        "rex_lease0001",
        "run_abc",
        RunnerStage.INVESTIGATION,
        RunnerExecutionStatus.RUNNING,
        1,
        DISPATCHED,
        DISPATCHED.plusMinutes(10),
        null,
        null,
        DISPATCHED.minusMinutes(1),
        null,
        null,
        null,
        null,
        null,
        null,
        DISPATCHED,
        "worker-7",
        100,
        1,
        "corr-1",
        null,
        null,
        null);
  }

  @Test
  void mapsCountsToViewWithPoolSizeFromConfigAndIdleMath() {
    when(recordPort.loadQueueCounts(any(Duration.class), any(Duration.class), isNull()))
        .thenReturn(new RunnerQueueCounts(5L, 1L, 0L, 0L, 3L, DISPATCHED, 42L));
    when(recordPort.findLeasedRunning(isNull(), eq(1024))).thenReturn(List.of(leasedRow()));

    RunnerQueueStatus view = service.getRunnerQueueStatus(null);

    assertThat(view.poolSize()).isEqualTo(2); // RunnerWorkerPoolProperties.defaults().size()
    assertThat(view.queueDepth()).isEqualTo(5L);
    assertThat(view.activeWorkers()).isEqualTo(1L);
    assertThat(view.inFlightExecutions()).isEqualTo(1L);
    assertThat(view.idleWorkers()).isEqualTo(1L); // max(0, 2 - 1)
    assertThat(view.recentThroughputPerMinute()).isEqualTo(3L);
    assertThat(view.oldestQueuedAgeSeconds()).isEqualTo(42L);
    assertThat(view.workers()).hasSize(1);
    assertThat(view.workers().get(0).workerId()).isEqualTo("worker-7");
    assertThat(view.workers().get(0).state()).isEqualTo("busy");
    assertThat(view.workers().get(0).currentStage()).isEqualTo("investigation");
  }

  @Test
  void idleWorkersClampedToZeroWhenActiveExceedsPoolSize() {
    when(recordPort.loadQueueCounts(any(Duration.class), any(Duration.class), isNull()))
        .thenReturn(new RunnerQueueCounts(0L, 5L, 0L, 0L, 0L, null, 0L));
    when(recordPort.findLeasedRunning(isNull(), eq(1024))).thenReturn(List.of());

    RunnerQueueStatus view = service.getRunnerQueueStatus(null);

    assertThat(view.idleWorkers()).isZero(); // max(0, 2 - 5)
  }

  @Test
  void nonZeroStaleEmitsWarnLogLine() {
    when(recordPort.loadQueueCounts(any(Duration.class), any(Duration.class), isNull()))
        .thenReturn(new RunnerQueueCounts(5L, 1L, 2L, 1L, 0L, DISPATCHED, 99L));
    when(recordPort.findLeasedRunning(isNull(), eq(1024))).thenReturn(List.of());

    service.getRunnerQueueStatus(null);

    assertThat(appender.list)
        .anySatisfy(
            event -> {
              assertThat(event.getLevel()).isEqualTo(Level.WARN);
              assertThat(event.getFormattedMessage()).contains("stale-detected");
            });
  }

  @Test
  void noWarnWhenNoStaleRows() {
    when(recordPort.loadQueueCounts(any(Duration.class), any(Duration.class), isNull()))
        .thenReturn(new RunnerQueueCounts(5L, 1L, 0L, 0L, 0L, DISPATCHED, 99L));
    when(recordPort.findLeasedRunning(isNull(), eq(1024))).thenReturn(List.of());

    service.getRunnerQueueStatus(null);

    assertThat(appender.list).noneSatisfy(e -> assertThat(e.getLevel()).isEqualTo(Level.WARN));
  }
}
