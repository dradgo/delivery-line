package org.dradgo.infrastructure.observability;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.time.Duration;
import org.dradgo.application.runner.spi.ProviderUsageSnapshotReadPort;
import org.dradgo.application.runner.spi.ProviderUsageSnapshotReadPort.SignalStateCounts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Story 3d-7 (FR69, AC6) — OPTIONAL per-provider limit-status observability, surfaced consistently
 * with existing local observability via a {@link MeterBinder} (mirrors {@link
 * ProjectHealthMetricsBinder} / {@code RunnerQueueMetricsBinder}). Meter names use DOTS (the
 * Prometheus registry renders them with underscores); one cached read per scrape window.
 *
 * <ul>
 *   <li>{@code deliveryline.provider.usage.snapshots.available} — count of captured snapshots whose
 *       provider EXPOSED the window status.
 *   <li>{@code deliveryline.provider.usage.snapshots.not_exposed} — count whose provider did NOT
 *       expose it (the spike outcome / documented degraded state).
 * </ul>
 *
 * <p><strong>Weak-ref gotcha ({@code micrometer-gauge-weak-ref-nan-flake}).</strong> Micrometer
 * holds the gauge state object weakly, so the cached {@link SignalStateCounts} lives in a
 * strongly-referenced {@code volatile} field — a GC can never turn the gauges to {@code NaN}.
 * Unconditional {@code @Component} (meaningful only when a Prometheus registry scrapes), depending
 * on the read port via {@link ObjectProvider} so it boots in contexts without a DB and reports
 * {@code 0} (never {@code NaN}) when the read side is absent.
 */
@Component
public class ProviderLimitMetricsBinder implements MeterBinder {

  private static final Logger log = LoggerFactory.getLogger(ProviderLimitMetricsBinder.class);
  private static final long CACHE_WINDOW_NANOS = Duration.ofSeconds(1).toNanos();

  private final ObjectProvider<ProviderUsageSnapshotReadPort> readPortProvider;

  // Strongly-referenced gauge state: keeps the snapshot reachable so the weakly-held gauges never
  // read back NaN. volatile for visibility across the scrape thread.
  private volatile SignalStateCounts state = SignalStateCounts.EMPTY;
  private volatile long cachedAtNanos = Long.MIN_VALUE;

  public ProviderLimitMetricsBinder(
      ObjectProvider<ProviderUsageSnapshotReadPort> readPortProvider) {
    this.readPortProvider = readPortProvider;
  }

  @Override
  public void bindTo(MeterRegistry registry) {
    Gauge.builder(
            "deliveryline.provider.usage.snapshots.available", this, b -> b.snapshot().available())
        .description("Captured provider usage snapshots whose provider exposed the window status")
        .register(registry);
    Gauge.builder(
            "deliveryline.provider.usage.snapshots.not_exposed",
            this,
            b -> b.snapshot().notExposed())
        .description("Captured provider usage snapshots whose provider did NOT expose the window")
        .register(registry);
    log.info("registered provider-limit usage gauges (Prometheus)");
  }

  private SignalStateCounts snapshot() {
    long now = System.nanoTime();
    SignalStateCounts current = state;
    if (now - cachedAtNanos < CACHE_WINDOW_NANOS && cachedAtNanos != Long.MIN_VALUE) {
      return current;
    }
    SignalStateCounts fresh = compute();
    state = fresh;
    cachedAtNanos = now;
    return fresh;
  }

  private SignalStateCounts compute() {
    ProviderUsageSnapshotReadPort readPort = readPortProvider.getIfAvailable();
    if (readPort == null) {
      return SignalStateCounts.EMPTY;
    }
    try {
      return readPort.countActiveBySignalState();
    } catch (RuntimeException error) {
      // A scrape must never crash the app or the actuator endpoint; serve the last good snapshot.
      log.warn("provider-limit metrics read failed; serving last snapshot: {}", error.toString());
      return state;
    }
  }
}
