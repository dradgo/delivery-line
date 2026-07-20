package org.dradgo.infrastructure.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.dradgo.application.integration.conflict.spi.IntegrationConflictReadPort;
import org.dradgo.application.integration.conflict.spi.UnresolvedConflictCount;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Story 4.17 (AC7) — the unresolved-count gauge reads the grouped snapshot and never reports {@code
 * NaN}: the binder is held in a field (weak-ref defense) and a pair with no conflicts reads {@code
 * 0}. A read failure serves the last snapshot, never throwing at scrape time.
 */
class IntegrationConflictMetricsBinderTest {

  private static final String GAUGE = "deliveryline.integration.conflict.unresolved.count";

  @SuppressWarnings("unchecked")
  private ObjectProvider<IntegrationConflictReadPort> providerOf(IntegrationConflictReadPort port) {
    ObjectProvider<IntegrationConflictReadPort> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(port);
    return provider;
  }

  @Test
  void gaugeReflectsGroupedUnresolvedCountAndZeroForAbsentPairs() {
    IntegrationConflictReadPort readPort = mock(IntegrationConflictReadPort.class);
    when(readPort.countUnresolvedByCategoryAndIntegration())
        .thenReturn(
            List.of(
                new UnresolvedConflictCount("link_broken", "github_pr", 3L),
                new UnresolvedConflictCount("external_resource_removed", "linear", 2L)));

    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    // Keep the binder in a field (mirrors production wiring) — the weak-ref state must survive.
    IntegrationConflictMetricsBinder binder =
        new IntegrationConflictMetricsBinder(providerOf(readPort));
    binder.bindTo(registry);

    assertThat(gauge(registry, "link_broken", "github")).isEqualTo(3.0);
    assertThat(gauge(registry, "external_resource_removed", "linear")).isEqualTo(2.0);
    // A pair with no unresolved conflicts reads 0.0, never NaN.
    assertThat(gauge(registry, "metadata_drift", "github")).isEqualTo(0.0);
  }

  @Test
  void scrapeNeverThrowsWhenReadPortFails() {
    IntegrationConflictReadPort readPort = mock(IntegrationConflictReadPort.class);
    when(readPort.countUnresolvedByCategoryAndIntegration())
        .thenThrow(new RuntimeException("db down"));

    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    IntegrationConflictMetricsBinder binder =
        new IntegrationConflictMetricsBinder(providerOf(readPort));
    binder.bindTo(registry);

    // Reading the gauge (which triggers the failing snapshot) must not throw and must not be NaN.
    double value = gauge(registry, "link_broken", "github");
    assertThat(value).isEqualTo(0.0);
  }

  private static double gauge(SimpleMeterRegistry registry, String category, String integration) {
    Gauge gauge =
        registry.find(GAUGE).tag("category", category).tag("integration", integration).gauge();
    assertThat(gauge).as("gauge %s{%s,%s}", GAUGE, category, integration).isNotNull();
    return gauge.value();
  }
}
