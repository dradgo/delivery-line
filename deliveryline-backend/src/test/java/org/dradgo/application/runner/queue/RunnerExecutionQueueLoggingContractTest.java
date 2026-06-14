package org.dradgo.application.runner.queue;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.dradgo.application.runner.RunnerProperties;
import org.dradgo.application.runner.spi.RunnerExecutionEventPort;
import org.dradgo.application.runner.spi.RunnerExecutionRecordPort;
import org.dradgo.application.runner.spi.RunnerExecutionSnapshot;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.RunnerExecutionStatus;
import org.dradgo.domain.registry.RunnerStage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Story 3.17a (logging instrumentation) — pins the enqueue/dequeue/backpressure log surfaces of
 * {@link RunnerExecutionQueue} via a focused list-appender. INFO on enqueue (queuePriority +
 * resulting depth) and dequeue (worker + leased rex); WARN on backpressure rejection (both depths).
 * No bundle bytes / secrets are ever logged.
 */
class RunnerExecutionQueueLoggingContractTest {

  private static final String RUN_ID = "run_qlog12345678";
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-06-14T10:00:00Z"), ZoneOffset.UTC);

  private RunnerExecutionRecordPort recordPort;
  private RunnerExecutionEventPort eventPort;
  private RunnerExecutionQueue queue;
  private ListAppender<ILoggingEvent> appender;
  private Logger queueLogger;

  @BeforeEach
  void setUp() {
    recordPort = mock(RunnerExecutionRecordPort.class);
    eventPort = mock(RunnerExecutionEventPort.class);
    queue = new RunnerExecutionQueue(recordPort, eventPort, RunnerProperties.defaults(), CLOCK);
    queueLogger = (Logger) LoggerFactory.getLogger(RunnerExecutionQueue.class);
    appender = new ListAppender<>();
    appender.start();
    queueLogger.addAppender(appender);
    queueLogger.setLevel(Level.DEBUG);
  }

  @AfterEach
  void tearDown() {
    queueLogger.detachAppender(appender);
    appender.stop();
  }

  @Test
  void enqueueLogsInfoWithPriorityAndDepth() {
    when(recordPort.countQueued()).thenReturn(5L);
    when(recordPort.nextContextBundleVersion(any(), any())).thenReturn(1);
    when(recordPort.insertQueued(any(), any(), any(), anyInt(), any(), anyInt(), any()))
        .thenAnswer(invocation -> snapshotFor(invocation.getArgument(0)));
    when(eventPort.append(any(), any(), any(), any(), any(), any(), any())).thenReturn("evt_q1");

    queue.enqueue(RUN_ID, RunnerStage.INVESTIGATION, "bundle", "idem", "corr", 70);

    String logs = render();
    assertTrue(
        hasLevel(Level.INFO) && logs.contains("enqueue runnerExecutionId="),
        () -> "expected INFO enqueue line; logs=\n" + logs);
    assertTrue(
        logs.contains("queuePriority=70") && logs.contains("currentDepth=6"),
        () -> "enqueue INFO must carry queuePriority + resulting depth; logs=\n" + logs);
  }

  @Test
  void backpressureRejectionLogsWarnWithDepths() {
    when(recordPort.countQueued()).thenReturn(100L);

    assertThrows(
        DomainException.class,
        () -> queue.enqueue(RUN_ID, RunnerStage.EXECUTION, "bundle", "idem", "corr", 100));

    String logs = render();
    assertTrue(hasLevel(Level.WARN), () -> "expected a WARN line; logs=\n" + logs);
    assertTrue(
        logs.contains("runner queue full")
            && logs.contains("currentDepth=100")
            && logs.contains("maxDepth=100"),
        () -> "backpressure WARN must carry both depths; logs=\n" + logs);
  }

  @Test
  void dequeueLogsInfoWithWorkerAndLeasedRex() {
    when(recordPort.dequeueNext("worker-7"))
        .thenReturn(Optional.of(snapshotFor("rex_leased0001a")));

    queue.dequeue("worker-7");

    String logs = render();
    assertTrue(
        logs.contains("dequeue leased runnerExecutionId=rex_leased0001a")
            && logs.contains("workerId=worker-7"),
        () -> "dequeue INFO must carry worker + leased rex; logs=\n" + logs);
  }

  private boolean hasLevel(Level level) {
    return appender.list.stream().anyMatch(e -> e.getLevel() == level);
  }

  private String render() {
    StringBuilder sb = new StringBuilder();
    for (ILoggingEvent event : List.copyOf(appender.list)) {
      sb.append(event.getFormattedMessage()).append('\n');
    }
    return sb.toString();
  }

  private static RunnerExecutionSnapshot snapshotFor(String publicId) {
    OffsetDateTime now = OffsetDateTime.now(CLOCK);
    return new RunnerExecutionSnapshot(
        publicId,
        RUN_ID,
        RunnerStage.INVESTIGATION,
        RunnerExecutionStatus.QUEUED,
        1,
        now,
        now.plusMinutes(10),
        null,
        null,
        now,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        70,
        0,
        "corr");
  }
}
