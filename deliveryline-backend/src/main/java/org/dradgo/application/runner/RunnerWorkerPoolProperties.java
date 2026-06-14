package org.dradgo.application.runner;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Story 3.17b (AC1/AC3/AC10) — configuration for the {@link
 * org.dradgo.application.runner.queue.RunnerWorkerPool}, bound from {@code
 * deliveryline.runner.worker-pool.*}.
 *
 * <p>Declared as its OWN {@code @ConfigurationProperties} record rather than extra components on
 * {@link RunnerProperties} deliberately: adding a component to that record fans out to every full-
 * argument {@code new RunnerProperties(...)} test site
 * ([[runnerproperties-record-component-fanout]]). A dedicated record keeps the worker-pool knobs
 * isolated and adds zero churn there.
 *
 * <ul>
 *   <li>{@code enabled} (Decision D9 / Trap T8) — master switch for the worker loop. Production
 *       ({@code application.yml}) sets it {@code true}; the shared test profile sets it {@code
 *       false} (mirroring {@code scheduling.enabled}) so an always-on pool never
 *       dequeues/dispatches during unrelated {@code @SpringBootTest}s. Spring binds a missing key
 *       to {@code false} (primitive default); {@link #defaults()} (non-Spring construction) is
 *       {@code true}.
 *   <li>{@code size} (AC1) — worker thread count = ThreadPoolTaskExecutor core=max. Default 2,
 *       clamped to [1, 32] (a pool below 1 would never drain; above 32 is unbounded resource use).
 *   <li>{@code backoff} (AC1) — idle worker backoff: starts at {@code initial} (default 1s),
 *       doubles up to {@code max} (default 10s), resets on work. LISTEN/NOTIFY shortens idle
 *       latency below this; the backoff is the always-correct liveness floor (Decision D8).
 * </ul>
 */
@ConfigurationProperties("deliveryline.runner.worker-pool")
public record RunnerWorkerPoolProperties(boolean enabled, int size, Backoff backoff) {

  private static final int DEFAULT_SIZE = 2;
  private static final int MAX_SIZE = 32;

  public RunnerWorkerPoolProperties {
    // size <= 0 means unset/misconfigured -> default 2; otherwise clamp the upper bound to 32.
    size = size <= 0 ? DEFAULT_SIZE : Math.min(size, MAX_SIZE);
    backoff = backoff == null ? Backoff.defaults() : backoff;
  }

  public static RunnerWorkerPoolProperties defaults() {
    return new RunnerWorkerPoolProperties(true, DEFAULT_SIZE, Backoff.defaults());
  }

  /** Idle-worker backoff window (story 3.17b AC1): {@code initial} grows to {@code max}. */
  public record Backoff(Duration initial, Duration max) {

    public Backoff {
      initial =
          initial == null || initial.isZero() || initial.isNegative()
              ? Duration.ofSeconds(1)
              : initial;
      max = max == null || max.isNegative() ? Duration.ofSeconds(10) : max;
      // A max below initial would invert the curve — coerce up to initial.
      if (max.compareTo(initial) < 0) {
        max = initial;
      }
    }

    public static Backoff defaults() {
      return new Backoff(Duration.ofSeconds(1), Duration.ofSeconds(10));
    }
  }
}
