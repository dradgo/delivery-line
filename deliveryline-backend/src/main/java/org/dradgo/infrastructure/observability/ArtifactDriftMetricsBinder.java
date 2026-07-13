package org.dradgo.infrastructure.observability;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import org.dradgo.application.artifact.reconciliation.spi.ArtifactDriftReadPort;
import org.dradgo.application.artifact.reconciliation.spi.UnresolvedDriftCount;
import org.dradgo.domain.registry.DriftCategory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Story 4.15 (AC7) — the {@code deliveryline_artifact_drift_unresolved_count{category}} gauge (a
 * {@link MeterBinder}, mirroring {@code IntegrationConflictMetricsBinder}). The paired counter
 * {@code deliveryline_artifact_drift_detected_total} is incremented on the detection service. Meter
 * names use DOTS (the Prometheus registry renders them with underscores; a gauge gets no {@code
 * _total} suffix, so {@code …unresolved.count} → {@code …unresolved_count}).
 *
 * <p>One STABLE gauge is registered per {@link DriftCategory} over the finite category set, each
 * reading a cached count snapshot; a category with no unresolved drift reports {@code 0}, never
 * {@code NaN}.
 *
 * <p><strong>Weak-ref gotcha ({@code micrometer-gauge-weak-ref-nan-flake}).</strong> Micrometer
 * holds the gauge state weakly, so the cached counts live in a strongly-referenced {@code volatile}
 * field — a GC can never turn the gauges to {@code NaN}. Unconditional {@code @Component},
 * depending on the read port via {@link ObjectProvider} so it boots in contexts without a DB and
 * reports {@code 0} when the read side is absent; the scrape read is wrapped so it NEVER throws
 * (serves the last good snapshot).
 */
@Component
public class ArtifactDriftMetricsBinder implements MeterBinder {

  private static final Logger log = LoggerFactory.getLogger(ArtifactDriftMetricsBinder.class);
  private static final long CACHE_WINDOW_NANOS = Duration.ofSeconds(1).toNanos();
  private static final String GAUGE_NAME = "deliveryline.artifact.drift.unresolved.count";

  private final ObjectProvider<ArtifactDriftReadPort> readPortProvider;

  // Strongly-referenced gauge state: keeps the counts reachable so the weakly-held gauges never
  // read
  // back NaN. volatile for visibility across the scrape thread.
  private volatile Map<DriftCategory, Long> counts = new EnumMap<>(DriftCategory.class);
  private volatile long cachedAtNanos = Long.MIN_VALUE;

  public ArtifactDriftMetricsBinder(ObjectProvider<ArtifactDriftReadPort> readPortProvider) {
    this.readPortProvider = readPortProvider;
  }

  @Override
  public void bindTo(MeterRegistry registry) {
    for (DriftCategory category : DriftCategory.values()) {
      Gauge.builder(GAUGE_NAME, this, self -> self.value(category))
          .tag("category", category.value())
          .description("Currently unresolved artifact drift by category")
          .register(registry);
    }
    log.info("registered artifact-drift unresolved-count gauges (Prometheus)");
  }

  private double value(DriftCategory category) {
    return snapshot().getOrDefault(category, 0L).doubleValue();
  }

  private Map<DriftCategory, Long> snapshot() {
    long now = System.nanoTime();
    if (now - cachedAtNanos < CACHE_WINDOW_NANOS && cachedAtNanos != Long.MIN_VALUE) {
      return counts;
    }
    Map<DriftCategory, Long> fresh = compute();
    counts = fresh;
    cachedAtNanos = now;
    return fresh;
  }

  private Map<DriftCategory, Long> compute() {
    ArtifactDriftReadPort readPort = readPortProvider.getIfAvailable();
    if (readPort == null) {
      return new EnumMap<>(DriftCategory.class);
    }
    try {
      Map<DriftCategory, Long> fresh = new EnumMap<>(DriftCategory.class);
      for (UnresolvedDriftCount row : readPort.countUnresolvedByCategory()) {
        fresh.merge(row.driftCategory(), row.count(), Long::sum);
      }
      return fresh;
    } catch (RuntimeException error) {
      // A scrape must never crash the app or the actuator endpoint; serve the last good snapshot.
      log.warn("artifact-drift metrics read failed; serving last snapshot: {}", error.toString());
      return counts;
    }
  }
}
