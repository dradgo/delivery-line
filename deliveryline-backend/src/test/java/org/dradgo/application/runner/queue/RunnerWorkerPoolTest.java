package org.dradgo.application.runner.queue;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.dradgo.application.observability.MdcKeys;
import org.dradgo.application.runner.RunnerBroker;
import org.dradgo.application.runner.RunnerWorkerPoolProperties;
import org.dradgo.application.runner.RunnerWorkerPoolProperties.Backoff;
import org.dradgo.application.runner.spi.RunnerExecutionSnapshot;
import org.dradgo.domain.registry.FailureCategory;
import org.dradgo.domain.registry.RunnerExecutionStatus;
import org.dradgo.domain.registry.RunnerStage;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/**
 * Story 3.17b (AC1/AC4/AC10) — unit coverage of {@link RunnerWorkerPool}: the disabled-pool gate
 * (Trap T8), the dequeue → executeQueuedDispatch → repeat loop, the worker-id MDC restored for the
 * dispatch duration, and graceful stop. Uses a real {@link RunnerQueueSignal} (so idle waits
 * return) with a short backoff to keep the test fast; the queue + broker are mocked.
 */
class RunnerWorkerPoolTest {

  private static final RunnerWorkerPoolProperties FAST =
      new RunnerWorkerPoolProperties(
          true, 1, new Backoff(Duration.ofMillis(20), Duration.ofMillis(50)));

  private static RunnerExecutionSnapshot leasedRow() {
    OffsetDateTime now = OffsetDateTime.parse("2026-06-14T10:00:00Z");
    return new RunnerExecutionSnapshot(
        "rex_worker00001a",
        "run_worker00001a",
        RunnerStage.INVESTIGATION,
        RunnerExecutionStatus.RUNNING,
        1,
        now,
        now.plusMinutes(10),
        (FailureCategory) null,
        null,
        now,
        null);
  }

  @Test
  void disabledPoolDoesNotStartDequeueOrDispatch() {
    RunnerExecutionQueue queue = mock(RunnerExecutionQueue.class);
    RunnerBroker broker = mock(RunnerBroker.class);
    RunnerWorkerPool pool =
        new RunnerWorkerPool(
            queue,
            broker,
            new RunnerQueueSignal(),
            new RunnerWorkerPoolProperties(false, 2, null),
            Duration.ofSeconds(5));

    pool.start();

    assertFalse(pool.isRunning(), "a disabled pool must not start");
    assertFalse(pool.isAutoStartup());
    verifyNoInteractions(queue);
    verifyNoInteractions(broker);
    pool.stop();
  }

  @Test
  void enabledPoolDequeuesDispatchesRestoresWorkerMdcThenDrainsOnStop() throws Exception {
    RunnerExecutionQueue queue = mock(RunnerExecutionQueue.class);
    RunnerBroker broker = mock(RunnerBroker.class);
    RunnerExecutionSnapshot leased = leasedRow();
    // First dequeue returns work, subsequent dequeues drain the queue.
    when(queue.dequeue(any())).thenReturn(Optional.of(leased)).thenReturn(Optional.empty());

    CountDownLatch dispatched = new CountDownLatch(1);
    AtomicReference<String> workerIdInMdc = new AtomicReference<>();
    doAnswer(
            invocation -> {
              // AC4 — the worker restores the worker id into MDC for the dispatch duration.
              workerIdInMdc.set(MDC.get(MdcKeys.WORKER_ID));
              dispatched.countDown();
              return null;
            })
        .when(broker)
        .executeQueuedDispatch(leased);

    RunnerWorkerPool pool =
        new RunnerWorkerPool(queue, broker, new RunnerQueueSignal(), FAST, Duration.ofSeconds(5));
    pool.start();
    try {
      assertTrue(pool.isRunning());
      assertTrue(
          dispatched.await(5, TimeUnit.SECONDS),
          "the worker must dequeue + dispatch the leased row");
    } finally {
      pool.stop();
    }

    assertFalse(pool.isRunning(), "stop() must clear the running flag");
    verify(broker).executeQueuedDispatch(leased);
    assertNotNull(workerIdInMdc.get(), "worker id must be in MDC during dispatch");
    assertTrue(
        workerIdInMdc.get().startsWith("runner-worker-"),
        () -> "unexpected worker id: " + workerIdInMdc.get());
    // MDC is cleaned up after the worker thread exits — the test thread carries no worker id.
    org.junit.jupiter.api.Assertions.assertEquals(null, MDC.get(MdcKeys.WORKER_ID));
  }
}
