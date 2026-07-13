package org.dradgo.infrastructure.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.dradgo.application.artifact.reconciliation.spi.ArtifactDriftReadPort;
import org.dradgo.application.artifact.reconciliation.spi.UnresolvedDriftCount;
import org.dradgo.domain.registry.DriftCategory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Story 4.15 (AC7, review test-coverage gap) — focused test for the {@code
 * deliveryline_artifact_drift_unresolved_count{category}} gauge. Verifies one STABLE gauge per
 * {@link DriftCategory}, reading the cached count snapshot, and the weak-ref/NaN-avoidance
 * contract: a category with no unresolved drift — or a context with no read port at all — reports
 * {@code 0}, never {@code NaN}.
 */
class ArtifactDriftMetricsBinderTest {

  private static final String GAUGE = "deliveryline.artifact.drift.unresolved.count";

  @Test
  void registersOneGaugePerCategoryReflectingCounts() {
    ArtifactDriftReadPort readPort = mock(ArtifactDriftReadPort.class);
    when(readPort.countUnresolvedByCategory())
        .thenReturn(
            List.of(
                new UnresolvedDriftCount(DriftCategory.ORPHAN_OPERATION, 3L),
                new UnresolvedDriftCount(DriftCategory.MISSING_PAYLOAD, 1L)));
    SimpleMeterRegistry registry = new SimpleMeterRegistry();

    new ArtifactDriftMetricsBinder(providerOf(readPort)).bindTo(registry);

    assertEquals(3.0, gauge(registry, DriftCategory.ORPHAN_OPERATION));
    assertEquals(1.0, gauge(registry, DriftCategory.MISSING_PAYLOAD));
    // No rows for this category → 0, never NaN (weak-ref/NaN trap).
    assertEquals(0.0, gauge(registry, DriftCategory.CHECKSUM_MISMATCH));
  }

  @Test
  void absentReadPortReportsZeroNotNaN() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();

    new ArtifactDriftMetricsBinder(providerOf(null)).bindTo(registry);

    for (DriftCategory category : DriftCategory.values()) {
      assertEquals(
          0.0,
          gauge(registry, category),
          "a context without the drift read port must report 0, not NaN, for " + category.value());
    }
  }

  @Test
  void readFailureServesLastSnapshotNotNaN() {
    ArtifactDriftReadPort readPort = mock(ArtifactDriftReadPort.class);
    when(readPort.countUnresolvedByCategory()).thenThrow(new RuntimeException("db down"));
    SimpleMeterRegistry registry = new SimpleMeterRegistry();

    new ArtifactDriftMetricsBinder(providerOf(readPort)).bindTo(registry);

    // A failing scrape must never surface NaN — it serves the (empty) last snapshot.
    assertEquals(0.0, gauge(registry, DriftCategory.ORPHAN_OPERATION));
  }

  private static double gauge(SimpleMeterRegistry registry, DriftCategory category) {
    return registry.get(GAUGE).tag("category", category.value()).gauge().value();
  }

  @SuppressWarnings("unchecked")
  private static ObjectProvider<ArtifactDriftReadPort> providerOf(ArtifactDriftReadPort readPort) {
    ObjectProvider<ArtifactDriftReadPort> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(readPort);
    return provider;
  }
}
