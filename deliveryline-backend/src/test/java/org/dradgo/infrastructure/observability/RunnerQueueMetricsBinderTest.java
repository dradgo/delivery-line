package org.dradgo.infrastructure.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.dradgo.application.runner.RunnerProperties;
import org.dradgo.application.runner.RunnerWorkerPoolProperties;
import org.dradgo.application.runner.spi.RunnerExecutionRecordPort;
import org.dradgo.application.runner.spi.RunnerQueueCounts;
import org.junit.jupiter.api.Test;

/**
 * Story 3.19 (AC5) — the runner-queue gauge binder registers the five gauges with DOT-named meters
 * (Prometheus underscores them at scrape) and reflects the read-port aggregate, with poolSize from
 * config and idle = max(0, poolSize − active).
 */
class RunnerQueueMetricsBinderTest {

  private final RunnerExecutionRecordPort recordPort = mock(RunnerExecutionRecordPort.class);

  // Micrometer gauges hold their state object (the binder) via a WEAK reference. In production the
  // binder is a strongly-held @Component, but here it is a local; without a strong reference the GC
  // can collect it between bind() and the assertions, so gauge.value() returns NaN (a windows-only
  // CI flake). Retaining the last-bound binder in a field keeps it reachable for the test method.
  @SuppressWarnings("unused")
  private RunnerQueueMetricsBinder boundBinder;

  private SimpleMeterRegistry bind(RunnerQueueCounts counts) {
    when(recordPort.loadQueueCounts(any(Duration.class), any(Duration.class), isNull()))
        .thenReturn(counts);
    RunnerQueueMetricsBinder binder =
        new RunnerQueueMetricsBinder(
            recordPort, RunnerProperties.defaults(), RunnerWorkerPoolProperties.defaults());
    this.boundBinder = binder;
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    binder.bindTo(registry);
    return registry;
  }

  private static double gauge(SimpleMeterRegistry registry, String name) {
    Gauge g = registry.find(name).gauge();
    assertThat(g).as("gauge %s registered", name).isNotNull();
    return g.value();
  }

  @Test
  void registersFiveGaugesWithDottedNamesReflectingTheAggregate() {
    SimpleMeterRegistry registry =
        bind(
            new RunnerQueueCounts(
                7L,
                1L,
                0L,
                0L,
                0L,
                OffsetDateTime.of(2026, 6, 15, 12, 0, 0, 0, ZoneOffset.UTC),
                123L));

    assertThat(gauge(registry, "deliveryline.runner.pool.size"))
        .isEqualTo(2.0); // defaults().size()
    assertThat(gauge(registry, "deliveryline.runner.active.workers")).isEqualTo(1.0);
    assertThat(gauge(registry, "deliveryline.runner.idle.workers")).isEqualTo(1.0); // max(0,2-1)
    assertThat(gauge(registry, "deliveryline.runner.queue.depth")).isEqualTo(7.0);
    assertThat(gauge(registry, "deliveryline.runner.queue.oldest.age.seconds")).isEqualTo(123.0);
  }

  @Test
  void idleGaugeClampsToZeroWhenActiveExceedsPoolSize() {
    SimpleMeterRegistry registry = bind(new RunnerQueueCounts(0L, 9L, 0L, 0L, 0L, null, 0L));

    assertThat(gauge(registry, "deliveryline.runner.idle.workers")).isEqualTo(0.0); // max(0,2-9)
  }
}
