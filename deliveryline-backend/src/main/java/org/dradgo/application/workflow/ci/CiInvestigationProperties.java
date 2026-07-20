package org.dradgo.application.workflow.ci;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Story 3h-5 (AC2/AC4, Decision 4) — configuration for the CI-investigation sweep, bound from the
 * {@code deliveryline.workflow.ci-investigation.*} namespace.
 *
 * <p>Deliberately a <strong>standalone</strong> record (NOT a nested component on {@code
 * RunnerProperties} — that would fan out to ~17 positional constructor sites,
 * runnerproperties-record-component-fanout). Registered alongside the other workflow properties in
 * the infrastructure {@code WorkflowConfiguration} {@code @EnableConfigurationProperties} set so
 * the application layer never depends on infrastructure.
 *
 * <ul>
 *   <li>{@code enabled} — informational mirror. The scheduler trigger bean ({@code
 *       CiInvestigationConfiguration}) is {@code @ConditionalOnProperty}-gated on this key, so when
 *       it is {@code false}/absent <strong>no</strong> scheduled bean is registered and the
 *       delivery tail is byte-identical to pre-3h-5 (AC4). The load-bearing gate is the
 *       {@code @ConditionalOnProperty}, not this field.
 *   <li>{@code intervalMs} — fixed-delay between sweep ticks (the scheduler reads the raw property
 *       via {@code fixedDelayString}; this mirror is for discoverability/logging).
 *   <li>{@code batchLimit} — max pending runs scanned per tick; the sweep {@code WARN}s when a tick
 *       fills the batch (no silent truncation) and the remainder is scanned next tick.
 *   <li>{@code maxPollAttempts} — the bounded poll budget; once a run's {@code ci_poll_attempts}
 *       exceeds this it is recorded {@code unavailable} and polling stops (best-effort).
 * </ul>
 *
 * <p>Like {@link RollupSweepProperties}, the compact constructor <strong>normalizes-with-defaults
 * and never throws</strong> (memory: {@code validated-config-needs-test-yaml} — NOT
 * {@code @Validated}) so the bean binds profile-neutrally in every {@code @SpringBootTest} tier
 * without a {@code src/test/resources/application.yml} mirror; non-positive values clamp to their
 * defaults.
 */
@ConfigurationProperties("deliveryline.workflow.ci-investigation")
public record CiInvestigationProperties(
    boolean enabled, long intervalMs, int batchLimit, int maxPollAttempts) {

  /** Default fixed-delay between sweep ticks (30s), matching the scheduler's {@code :default}. */
  public static final long DEFAULT_INTERVAL_MS = 30_000L;

  /** Default per-tick scan cap. */
  public static final int DEFAULT_BATCH_LIMIT = 20;

  /** Default bounded poll budget before a run is recorded {@code unavailable}. */
  public static final int DEFAULT_MAX_POLL_ATTEMPTS = 60;

  public CiInvestigationProperties {
    intervalMs = intervalMs <= 0 ? DEFAULT_INTERVAL_MS : intervalMs;
    batchLimit = batchLimit <= 0 ? DEFAULT_BATCH_LIMIT : batchLimit;
    maxPollAttempts = maxPollAttempts <= 0 ? DEFAULT_MAX_POLL_ATTEMPTS : maxPollAttempts;
  }

  /** Disabled, default-cadence/batch/budget — the production-safe default posture. */
  public static CiInvestigationProperties defaults() {
    return new CiInvestigationProperties(
        false, DEFAULT_INTERVAL_MS, DEFAULT_BATCH_LIMIT, DEFAULT_MAX_POLL_ATTEMPTS);
  }
}
