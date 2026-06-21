package org.dradgo.infrastructure.observability;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.time.Duration;
import java.util.List;
import org.dradgo.application.project.ProjectConfigChecks;
import org.dradgo.application.project.ProjectConnectorResolver;
import org.dradgo.application.project.ProjectStore;
import org.dradgo.domain.project.Project;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Story 3c-10 (AC5) — registers the per-project health Prometheus gauges, mirroring {@link
 * RunnerQueueMetricsBinder} exactly: meter names use DOTS (Micrometer's Prometheus registry renders
 * them with underscores — {@code deliveryline.projects.total} scrapes as {@code
 * deliveryline_projects_total}); one cached snapshot per scrape window so a scrape costs a single
 * {@code findAll()} read, not one per gauge.
 *
 * <ul>
 *   <li>{@code deliveryline.projects.total} — count of non-archived projects.
 *   <li>{@code deliveryline.projects.configured} — count of ACTIVE projects passing the structural
 *       configuration check ({@link ProjectConfigChecks#isStructurallyConfigured} = repo bound +
 *       connector kinds resolvable). Credential PRESENCE is reported by the doctor {@code projects}
 *       probe only and deliberately does NOT gate this gauge — the binder never reads a credential
 *       or host-env value on the scrape hot path (Open Decision #3).
 * </ul>
 *
 * <p><strong>Weak-ref gotcha (R6 / {@code micrometer-gauge-weak-ref-nan-flake}).</strong>
 * Micrometer holds the gauge state object weakly. The cached {@link ProjectHealthState} lives in a
 * strongly-referenced instance field so a GC can never turn the gauges to {@code NaN}. The binder
 * is an unconditional {@code @Component} (registered in all contexts, meaningful only when a
 * Prometheus registry is scraping); it depends only on the {@link ProjectStore} + {@link
 * ProjectConnectorResolver} via {@link ObjectProvider}, so it boots in contexts without a DB and
 * the gauges report {@code 0} (never {@code NaN}) when the read side is absent.
 */
@Component
public class ProjectHealthMetricsBinder implements MeterBinder {

  private static final Logger log = LoggerFactory.getLogger(ProjectHealthMetricsBinder.class);
  // 1s is far below the 15s scrape cadence, so each scrape still recomputes once but the two gauges
  // of a single scrape share one read.
  private static final long CACHE_WINDOW_NANOS = Duration.ofSeconds(1).toNanos();

  private final ObjectProvider<ProjectStore> projectStoreProvider;
  private final ObjectProvider<ProjectConnectorResolver> projectConnectorResolverProvider;

  // Strongly-referenced gauge state (R6): keeps the snapshot reachable so the weakly-held gauges
  // never read back NaN. volatile for visibility across the scrape thread.
  private volatile ProjectHealthState state = ProjectHealthState.EMPTY;
  private volatile long cachedAtNanos = Long.MIN_VALUE;

  public ProjectHealthMetricsBinder(
      ObjectProvider<ProjectStore> projectStoreProvider,
      ObjectProvider<ProjectConnectorResolver> projectConnectorResolverProvider) {
    this.projectStoreProvider = projectStoreProvider;
    this.projectConnectorResolverProvider = projectConnectorResolverProvider;
  }

  @Override
  public void bindTo(MeterRegistry registry) {
    Gauge.builder("deliveryline.projects.total", this, b -> b.snapshot().total())
        .description("Projects known to DeliveryLine (non-archived)")
        .register(registry);
    Gauge.builder("deliveryline.projects.configured", this, b -> b.snapshot().configured())
        .description(
            "Active projects passing the structural configuration check (repo bound + kinds"
                + " resolvable)")
        .register(registry);
    log.info("registered project-health gauges (Prometheus)");
  }

  /**
   * One cached snapshot per scrape window; {@code 0} (never {@code NaN}) when the store is absent.
   */
  private ProjectHealthState snapshot() {
    long now = System.nanoTime();
    ProjectHealthState current = state;
    if (now - cachedAtNanos < CACHE_WINDOW_NANOS && cachedAtNanos != Long.MIN_VALUE) {
      return current;
    }
    ProjectHealthState fresh = compute();
    state = fresh;
    cachedAtNanos = now;
    return fresh;
  }

  private ProjectHealthState compute() {
    ProjectStore store = projectStoreProvider.getIfAvailable();
    if (store == null) {
      return ProjectHealthState.EMPTY;
    }
    try {
      ProjectConnectorResolver resolver = projectConnectorResolverProvider.getIfAvailable();
      List<Project> projects =
          store.findAll().stream().filter(p -> p.archivedAt() == null).toList();
      long configured =
          projects.stream()
              .filter(p -> ProjectConfigChecks.isStructurallyConfigured(p, resolver))
              .count();
      return new ProjectHealthState(projects.size(), configured);
    } catch (RuntimeException error) {
      // A scrape must never crash the app or the actuator endpoint; serve the last good snapshot.
      log.warn("project-health metrics read failed; serving last snapshot: {}", error.toString());
      return state;
    }
  }

  /** Immutable per-scrape snapshot of the two gauge values. */
  record ProjectHealthState(long total, long configured) {
    static final ProjectHealthState EMPTY = new ProjectHealthState(0L, 0L);
  }
}
