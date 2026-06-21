package org.dradgo.infrastructure.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.OffsetDateTime;
import java.util.List;
import org.dradgo.application.project.ProjectConnectorResolver;
import org.dradgo.application.project.ProjectStore;
import org.dradgo.domain.project.Project;
import org.dradgo.domain.registry.ConnectorKind;
import org.dradgo.domain.registry.ProjectStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Story 3c-10 (AC5/AC6) — the project-health gauge binder registers {@code
 * deliveryline.projects.total} (non-archived count) and {@code deliveryline.projects.configured}
 * (active + structurally-configured count) with DOT-named meters, reflecting the {@link
 * ProjectStore} read. Includes the weak-ref-NaN regression guard ({@code
 * micrometer-gauge-weak-ref-nan-flake}): the gauges must stay {@code 0} (never {@code NaN}) after a
 * forced GC and when the read side is absent.
 */
class ProjectHealthMetricsBinderTest {

  // Micrometer gauges hold their state object (the binder) WEAKLY. In production the binder is a
  // strongly-held @Component; here it is a local, so without a strong reference a GC could collect
  // it between bind() and the assertions and gauge.value() would return NaN. The field keeps it
  // reachable for the test method.
  @SuppressWarnings("unused")
  private ProjectHealthMetricsBinder boundBinder;

  @SuppressWarnings("unchecked")
  private SimpleMeterRegistry bind(ProjectStore store, ProjectConnectorResolver resolver) {
    ObjectProvider<ProjectStore> storeProvider = mock(ObjectProvider.class);
    when(storeProvider.getIfAvailable()).thenReturn(store);
    ObjectProvider<ProjectConnectorResolver> resolverProvider = mock(ObjectProvider.class);
    when(resolverProvider.getIfAvailable()).thenReturn(resolver);
    ProjectHealthMetricsBinder binder =
        new ProjectHealthMetricsBinder(storeProvider, resolverProvider);
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
  void registersTotalAndConfiguredGaugesFromTheStore() {
    ProjectStore store = mock(ProjectStore.class);
    when(store.findAll())
        .thenReturn(
            List.of(
                project("prj_active", ProjectStatus.ACTIVE, "https://github.com/acme/repo"),
                project("prj_disabled", ProjectStatus.DISABLED, "https://github.com/acme/other")));
    ProjectConnectorResolver resolver = mock(ProjectConnectorResolver.class);

    SimpleMeterRegistry registry = bind(store, resolver);

    assertThat(gauge(registry, "deliveryline.projects.total")).isEqualTo(2.0);
    // Only the ACTIVE + repo-bound + kinds-resolvable project counts as configured; the disabled
    // one is in `total` but not `configured`.
    assertThat(gauge(registry, "deliveryline.projects.configured")).isEqualTo(1.0);
  }

  @Test
  void gaugesReportZeroNotNaNWhenStoreAbsent() {
    SimpleMeterRegistry registry = bind(null, null);

    // Force a GC: with the snapshot held in a strong instance field, the gauges must not collapse
    // to NaN (the weak-ref regression guard).
    System.gc();

    double total = gauge(registry, "deliveryline.projects.total");
    double configured = gauge(registry, "deliveryline.projects.configured");
    assertThat(Double.isNaN(total)).as("total gauge must not be NaN").isFalse();
    assertThat(Double.isNaN(configured)).as("configured gauge must not be NaN").isFalse();
    assertThat(total).isEqualTo(0.0);
    assertThat(configured).isEqualTo(0.0);
  }

  private static Project project(String publicId, ProjectStatus status, String repositoryUrl) {
    return new Project(
        publicId,
        "Name " + publicId,
        "slug-" + publicId,
        status,
        repositoryUrl,
        ConnectorKind.LINEAR,
        ConnectorKind.GITHUB,
        false,
        OffsetDateTime.parse("2026-06-20T00:00:00Z"),
        null);
  }
}
