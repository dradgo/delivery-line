package org.dradgo.application.artifact.reconciliation;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Story 4.15 (AC1) — configuration for the artifact-drift-detection sweep, bound from the {@code
 * deliveryline.artifact.drift-detection.*} namespace.
 *
 * <ul>
 *   <li>{@code enabled} — master switch. The {@code @Scheduled} trigger bean (in {@code
 *       infrastructure.config}) is {@code @ConditionalOnProperty}-gated on this key, so when it is
 *       {@code false}/absent <strong>no</strong> scheduled bean is registered (ITs call {@code
 *       detectDrift()} directly). This field is informational for the sweep log; the load-bearing
 *       gate is the {@code @ConditionalOnProperty} on the trigger.
 *   <li>{@code intervalMs} — fixed-delay between sweep ticks (the scheduler reads the raw property
 *       via {@code fixedDelayString}; this mirror is for discoverability/logging).
 *   <li>{@code batchLimit} — max {@code available} artifacts scanned per tick; the sweep {@code
 *       WARN}s when a tick fills the batch (no silent truncation) and the remainder heals next
 *       tick.
 *   <li>{@code minAgeMinutes} — an {@code available} artifact must be at least this old before the
 *       missing-payload / checksum scan considers it, so a just-written payload mid-flush is never
 *       flagged.
 * </ul>
 *
 * <p>Like {@code IntegrationConflictDetectionProperties}, the compact constructor
 * <strong>normalizes-with-defaults and never throws</strong> (memory: {@code
 * validated-config-needs-test-yaml} — NOT {@code @Validated}) so the bean binds profile-neutrally
 * in every {@code @SpringBootTest} tier; non-positive {@code intervalMs}/{@code batchLimit}/{@code
 * minAgeMinutes} clamp to their defaults. The keys are mirrored into both {@code application.yml}
 * files. Registered via {@code @EnableConfigurationProperties} in the infrastructure {@code
 * ArtifactDriftConfiguration} so the application layer never depends on infrastructure.
 */
@ConfigurationProperties("deliveryline.artifact.drift-detection")
public record ArtifactDriftDetectionProperties(
    boolean enabled, long intervalMs, int batchLimit, long minAgeMinutes) {

  /**
   * Default fixed-delay between sweep ticks (15 minutes), matching the scheduler's {@code
   * :default}.
   */
  public static final long DEFAULT_INTERVAL_MS = 900_000L;

  /** Default per-tick available-artifact scan cap. */
  public static final int DEFAULT_BATCH_LIMIT = 100;

  /**
   * Default minimum age (minutes) before an available artifact is scanned for payload/checksum
   * drift.
   */
  public static final long DEFAULT_MIN_AGE_MINUTES = 5L;

  public ArtifactDriftDetectionProperties {
    intervalMs = intervalMs <= 0 ? DEFAULT_INTERVAL_MS : intervalMs;
    batchLimit = batchLimit <= 0 ? DEFAULT_BATCH_LIMIT : batchLimit;
    minAgeMinutes = minAgeMinutes <= 0 ? DEFAULT_MIN_AGE_MINUTES : minAgeMinutes;
  }

  /** Disabled, default-cadence, default-batch — the production-safe default posture. */
  public static ArtifactDriftDetectionProperties defaults() {
    return new ArtifactDriftDetectionProperties(
        false, DEFAULT_INTERVAL_MS, DEFAULT_BATCH_LIMIT, DEFAULT_MIN_AGE_MINUTES);
  }
}
