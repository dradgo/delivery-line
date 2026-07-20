package org.dradgo.infrastructure.observability;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.dradgo.application.integration.conflict.spi.IntegrationConflictReadPort;
import org.dradgo.application.integration.conflict.spi.UnresolvedConflictCount;
import org.dradgo.domain.registry.IntegrationConflictCategory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Story 4.17 (AC7) — the {@code deliveryline_integration_conflict_unresolved_count{category,
 * integration}} gauge (a {@link MeterBinder}, mirroring {@code ProviderLimitMetricsBinder}/{@code
 * RunnerQueueMetricsBinder}). The paired counter {@code
 * deliveryline_integration_conflict_detected_total} is incremented on the detection service. Meter
 * names use DOTS (the Prometheus registry renders them with underscores; a gauge gets no {@code
 * _total} suffix, so {@code …unresolved.count} → {@code …unresolved_count}).
 *
 * <p>One STABLE gauge is registered per {@code (category, integration)} pair over the finite {@link
 * IntegrationConflictCategory} × {@code {github, linear}} grid, each reading a cached grouped-count
 * snapshot; a pair with no unresolved conflicts reports {@code 0}, never {@code NaN}.
 *
 * <p><strong>Weak-ref gotcha ({@code micrometer-gauge-weak-ref-nan-flake}).</strong> Micrometer
 * holds the gauge state weakly, so the cached counts live in a strongly-referenced {@code volatile}
 * field — a GC can never turn the gauges to {@code NaN}. Unconditional {@code @Component},
 * depending on the read port via {@link ObjectProvider} so it boots in contexts without a DB and
 * reports {@code 0} when the read side is absent; the scrape read is wrapped so it NEVER throws
 * (serves the last good snapshot).
 */
@Component
public class IntegrationConflictMetricsBinder implements MeterBinder {

  private static final Logger log = LoggerFactory.getLogger(IntegrationConflictMetricsBinder.class);
  private static final long CACHE_WINDOW_NANOS = Duration.ofSeconds(1).toNanos();

  private static final String GITHUB_INTEGRATION_TAG = "github";
  private static final String LINEAR_INTEGRATION_TAG = "linear";
  private static final String UNKNOWN_INTEGRATION_TAG = "unknown";
  private static final String GAUGE_NAME = "deliveryline.integration.conflict.unresolved.count";

  private final ObjectProvider<IntegrationConflictReadPort> readPortProvider;

  // Strongly-referenced gauge state: keeps the counts reachable so the weakly-held gauges never
  // read
  // back NaN. volatile for visibility across the scrape thread. Keyed by "category|integration".
  private volatile Map<String, Long> counts = Map.of();
  private volatile long cachedAtNanos = Long.MIN_VALUE;

  public IntegrationConflictMetricsBinder(
      ObjectProvider<IntegrationConflictReadPort> readPortProvider) {
    this.readPortProvider = readPortProvider;
  }

  @Override
  public void bindTo(MeterRegistry registry) {
    for (IntegrationConflictCategory category : IntegrationConflictCategory.values()) {
      for (String integration :
          List.of(GITHUB_INTEGRATION_TAG, LINEAR_INTEGRATION_TAG, UNKNOWN_INTEGRATION_TAG)) {
        String key = key(category.value(), integration);
        Gauge.builder(GAUGE_NAME, this, self -> self.value(key))
            .tag("category", category.value())
            .tag("integration", integration)
            .description("Currently unresolved integration conflicts by category and integration")
            .register(registry);
      }
    }
    log.info("registered integration-conflict unresolved-count gauges (Prometheus)");
  }

  private double value(String key) {
    return snapshot().getOrDefault(key, 0L).doubleValue();
  }

  private Map<String, Long> snapshot() {
    long now = System.nanoTime();
    if (now - cachedAtNanos < CACHE_WINDOW_NANOS && cachedAtNanos != Long.MIN_VALUE) {
      return counts;
    }
    Map<String, Long> fresh = compute();
    counts = fresh;
    cachedAtNanos = now;
    return fresh;
  }

  private Map<String, Long> compute() {
    IntegrationConflictReadPort readPort = readPortProvider.getIfAvailable();
    if (readPort == null) {
      return Map.of();
    }
    try {
      Map<String, Long> fresh = new LinkedHashMap<>();
      for (UnresolvedConflictCount row : readPort.countUnresolvedByCategoryAndIntegration()) {
        fresh.merge(
            key(row.conflictCategory(), integrationTag(row.integrationType())),
            row.count(),
            Long::sum);
      }
      return fresh;
    } catch (RuntimeException error) {
      // A scrape must never crash the app or the actuator endpoint; serve the last good snapshot.
      log.warn(
          "integration-conflict metrics read failed; serving last snapshot: {}", error.toString());
      return counts;
    }
  }

  private static String integrationTag(String integrationType) {
    if ("github_pr".equals(integrationType)) {
      return GITHUB_INTEGRATION_TAG;
    }
    if (LINEAR_INTEGRATION_TAG.equals(integrationType)) {
      return LINEAR_INTEGRATION_TAG;
    }
    return UNKNOWN_INTEGRATION_TAG;
  }

  private static String key(String category, String integration) {
    return category + "|" + integration;
  }
}
