package org.dradgo.infrastructure.observability;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.time.Duration;
import java.util.Objects;
import org.dradgo.application.runner.RunnerProperties;
import org.dradgo.application.runner.RunnerWorkerPoolProperties;
import org.dradgo.application.runner.spi.RunnerExecutionRecordPort;
import org.dradgo.application.runner.spi.RunnerQueueCounts;
import org.dradgo.domain.registry.RunnerStage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Story 3.19 (AC5/AC10) — registers the runner-queue Prometheus gauges. Meter names use DOTS;
 * Micrometer's Prometheus registry renders them with underscores (Reconciliation 5), so {@code
 * deliveryline.runner.queue.depth} scrapes as {@code deliveryline_runner_queue_depth} — the
 * headline metric (AC5/AC11).
 *
 * <p>Lives in {@code infrastructure.observability} (an Infrastructure-layer cross-cut may depend on
 * the Application layer): the gauge suppliers read through the {@link RunnerExecutionRecordPort}
 * read port + config properties — NOT the typed {@code RunnerQueueStatus} view. AC10 allows the
 * exporter to read "via the service OR the read port"; using the port keeps this off the view's
 * type-reference set (so it never needs to live in the AC10 allow-list) and avoids duplicating the
 * counting SQL. The {@code application.runner.queue} placement rule whitelists that package away
 * from {@code io.micrometer}, which is why the binder cannot live next to the worker pool.
 *
 * <p>All five gauges read ONE cached {@link RunnerQueueCounts} snapshot per scrape (refreshed at
 * most once per {@link #CACHE_WINDOW_NANOS}) so a 15s scrape costs a single query, not one per
 * gauge.
 */
@Component
public class RunnerQueueMetricsBinder implements MeterBinder {

  private static final Logger log = LoggerFactory.getLogger(RunnerQueueMetricsBinder.class);
  // Recent-completion window backing the read; the gauges below do not surface throughput, but the
  // single aggregate query takes it (the counter at the completion site is the throughput surface).
  private static final Duration THROUGHPUT_WINDOW = Duration.ofSeconds(60);
  // Reuse the same counts across the gauges of one scrape (they fire within microseconds of each
  // other); 1s is far below the 15s scrape cadence so each scrape still recomputes once.
  private static final long CACHE_WINDOW_NANOS = Duration.ofSeconds(1).toNanos();

  private final RunnerExecutionRecordPort recordPort;
  private final RunnerProperties runnerProperties;
  private final RunnerWorkerPoolProperties workerPoolProperties;

  private volatile RunnerQueueCounts cached = RunnerQueueCounts.empty();
  private volatile long cachedAtNanos = Long.MIN_VALUE;

  public RunnerQueueMetricsBinder(
      RunnerExecutionRecordPort recordPort,
      RunnerProperties runnerProperties,
      RunnerWorkerPoolProperties workerPoolProperties) {
    this.recordPort = Objects.requireNonNull(recordPort, "recordPort");
    this.runnerProperties = Objects.requireNonNull(runnerProperties, "runnerProperties");
    this.workerPoolProperties =
        Objects.requireNonNull(workerPoolProperties, "workerPoolProperties");
  }

  @Override
  public void bindTo(MeterRegistry registry) {
    Gauge.builder("deliveryline.runner.pool.size", this, b -> b.workerPoolProperties.size())
        .description("Configured runner worker-pool size")
        .register(registry);
    Gauge.builder("deliveryline.runner.active.workers", this, b -> b.counts().activeWorkers())
        .description("Leased running executions (busy workers)")
        .register(registry);
    Gauge.builder("deliveryline.runner.idle.workers", this, RunnerQueueMetricsBinder::idleWorkers)
        .description("Idle workers = max(0, poolSize - activeWorkers)")
        .register(registry);
    Gauge.builder("deliveryline.runner.queue.depth", this, b -> b.counts().queueDepth())
        .description("Runner executions currently queued (headline metric)")
        .register(registry);
    Gauge.builder(
            "deliveryline.runner.queue.oldest.age.seconds",
            this,
            b -> b.counts().oldestQueuedAgeSeconds())
        .description("Age in seconds of the oldest queued runner execution")
        .register(registry);
    log.info("registered 5 runner-queue gauges (Prometheus)");
  }

  private static double idleWorkers(RunnerQueueMetricsBinder binder) {
    return Math.max(0L, binder.workerPoolProperties.size() - binder.counts().activeWorkers());
  }

  /** One cached aggregate snapshot per scrape window (global, unfiltered). */
  private RunnerQueueCounts counts() {
    long now = System.nanoTime();
    RunnerQueueCounts snapshot = cached;
    if (now - cachedAtNanos < CACHE_WINDOW_NANOS && cachedAtNanos != Long.MIN_VALUE) {
      return snapshot;
    }
    try {
      RunnerQueueCounts fresh =
          recordPort.loadQueueCounts(maxStaleWindow(), THROUGHPUT_WINDOW, null);
      cached = fresh;
      cachedAtNanos = now;
      return fresh;
    } catch (RuntimeException error) {
      // A scrape must never crash the app or the actuator endpoint; serve the last good snapshot.
      log.warn("runner-queue metrics read failed; serving last snapshot: {}", error.toString());
      return snapshot;
    }
  }

  private Duration maxStaleWindow() {
    Duration max = Duration.ZERO;
    for (RunnerStage stage : RunnerStage.values()) {
      Duration window = runnerProperties.staleThresholdFor(stage);
      if (window.compareTo(max) > 0) {
        max = window;
      }
    }
    return max.isZero() ? Duration.ofSeconds(1) : max;
  }
}
