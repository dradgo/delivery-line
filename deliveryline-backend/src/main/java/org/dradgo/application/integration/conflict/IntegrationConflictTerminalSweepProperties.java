package org.dradgo.application.integration.conflict;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Story 4.30 (AC4/AC5) — configuration for the terminal-run integration-conflict reconciliation
 * sweep, bound from the {@code deliveryline.integration-conflict.terminal-sweep.*} namespace.
 *
 * <ul>
 *   <li>{@code enabled} — master switch. The {@code @Scheduled} trigger bean (in {@code
 *       infrastructure.config}) is {@code @ConditionalOnProperty}-gated on this key, so when it is
 *       {@code false}/absent <strong>no</strong> scheduled bean is registered and no
 *       {@code @EnableScheduling} for it spins up — byte-identical to pre-story behavior (AC4).
 *       This field is informational for the sweep log; the load-bearing gate is the
 *       {@code @ConditionalOnProperty} on the trigger.
 *   <li>{@code intervalMs} — fixed-delay between sweep ticks (the scheduler reads the raw property
 *       via {@code fixedDelayString}; this mirror is for discoverability/logging).
 *   <li>{@code batchLimit} — max stranded terminal-run conflicts scanned/cleared per tick; the
 *       sweep {@code WARN}s when a tick fills the batch (no silent truncation — AC5) and the
 *       remainder heals on the next tick.
 * </ul>
 *
 * <p>Like {@code RollupSweepProperties} / {@code IntegrationConflictDetectionProperties}, the
 * compact constructor <strong>normalizes-with-defaults and never throws</strong> (memory: {@code
 * validated-config-needs-test-yaml} — NOT {@code @Validated}) so the bean binds profile-neutrally
 * in every {@code @SpringBootTest} tier. Note the clamp protects only the value the sweep reads
 * from this record — {@code batchLimit}: a non-positive {@code batchLimit} clamps to its default.
 * The {@code intervalMs} clamp is inert for scheduling because the {@code @Scheduled} trigger reads
 * the RAW {@code interval-ms} property via {@code fixedDelayString} (not this record), so a
 * non-positive {@code interval-ms} on an enabled sweep fails fast at context startup rather than
 * being normalized; the clamped {@code intervalMs} here is only the record's logging/discovery
 * mirror. The keys are mirrored into both {@code application.yml} files. Registered via
 * {@code @EnableConfigurationProperties} in the infrastructure {@code
 * IntegrationConflictConfiguration} so the application layer never depends on infrastructure.
 */
@ConfigurationProperties("deliveryline.integration-conflict.terminal-sweep")
public record IntegrationConflictTerminalSweepProperties(
    boolean enabled, long intervalMs, int batchLimit) {

  /** Default fixed-delay between sweep ticks (60s), matching the scheduler's {@code :default}. */
  public static final long DEFAULT_INTERVAL_MS = 60_000L;

  /** Default per-tick scan/clear cap. */
  public static final int DEFAULT_BATCH_LIMIT = 100;

  public IntegrationConflictTerminalSweepProperties {
    intervalMs = intervalMs <= 0 ? DEFAULT_INTERVAL_MS : intervalMs;
    batchLimit = batchLimit <= 0 ? DEFAULT_BATCH_LIMIT : batchLimit;
  }

  /** Disabled, default-cadence, default-batch — the production-safe default posture. */
  public static IntegrationConflictTerminalSweepProperties defaults() {
    return new IntegrationConflictTerminalSweepProperties(
        false, DEFAULT_INTERVAL_MS, DEFAULT_BATCH_LIMIT);
  }
}
