package org.dradgo.application.workflow;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Story 3f-8 (AC4) — configuration for the split-rollup reconciliation sweep, bound from the {@code
 * deliveryline.complex-ticket-flow.rollup-sweep.*} namespace.
 *
 * <p>Deliberately a <strong>separate</strong> record from {@link ComplexTicketFlowProperties}
 * rather than two more components on its canonical constructor: adding components there would fan
 * out to {@code ComplexTicketFlowProperties.defaults()} and every {@code new
 * ComplexTicketFlowProperties(3)} in {@code SplitProposalServiceTest}. Registered alongside it in
 * the infrastructure {@code WorkflowConfiguration} {@code @EnableConfigurationProperties} set so
 * the application layer never depends on infrastructure.
 *
 * <ul>
 *   <li>{@code enabled} — master switch. The scheduler trigger bean (in {@code
 *       infrastructure.config}) is {@code @ConditionalOnProperty}-gated on this key, so when it is
 *       {@code false}/absent <strong>no</strong> scheduled bean is registered and the 3f-7 hook
 *       path is byte-identical to today (AC4). This record's {@code enabled} field is informational
 *       for the sweep service log; the load-bearing gate is the {@code @ConditionalOnProperty} on
 *       the trigger.
 *   <li>{@code intervalMs} — fixed-delay between sweep ticks (the scheduler reads the raw property
 *       via {@code fixedDelayString}; this mirror is for discoverability/logging).
 *   <li>{@code batchLimit} — max stranded parents scanned per tick; the sweep {@code WARN}s when a
 *       tick fills the batch (no silent truncation) and the remainder heals on the next tick.
 * </ul>
 *
 * <p>Like {@link ComplexTicketFlowProperties}/{@link WorkflowProperties}, the compact constructor
 * <strong>normalizes-with-defaults and never throws</strong> (memory: {@code
 * validated-config-needs-test-yaml} — NOT {@code @Validated}) so the bean binds profile-neutrally
 * in every {@code @SpringBootTest} tier; non-positive {@code intervalMs}/{@code batchLimit} clamp
 * to their defaults. The keys are mirrored into both {@code application.yml} files.
 */
@ConfigurationProperties("deliveryline.complex-ticket-flow.rollup-sweep")
public record RollupSweepProperties(boolean enabled, long intervalMs, int batchLimit) {

  /** Default fixed-delay between sweep ticks (60s), matching the scheduler's {@code :default}. */
  public static final long DEFAULT_INTERVAL_MS = 60_000L;

  /** Default per-tick scan cap. */
  public static final int DEFAULT_BATCH_LIMIT = 100;

  public RollupSweepProperties {
    intervalMs = intervalMs <= 0 ? DEFAULT_INTERVAL_MS : intervalMs;
    batchLimit = batchLimit <= 0 ? DEFAULT_BATCH_LIMIT : batchLimit;
  }

  /** Disabled, default-cadence, default-batch — the production-safe default posture. */
  public static RollupSweepProperties defaults() {
    return new RollupSweepProperties(false, DEFAULT_INTERVAL_MS, DEFAULT_BATCH_LIMIT);
  }
}
