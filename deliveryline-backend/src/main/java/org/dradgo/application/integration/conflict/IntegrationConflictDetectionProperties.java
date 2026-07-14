package org.dradgo.application.integration.conflict;

import java.util.List;
import org.dradgo.domain.registry.IntegrationConflictCategory;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Story 4.17 (AC1) — configuration for the integration-conflict-detection sweep, bound from the
 * {@code deliveryline.integration.conflict-detection.*} namespace.
 *
 * <ul>
 *   <li>{@code enabled} — master switch. The {@code @Scheduled} trigger bean (in {@code
 *       infrastructure.config}) is {@code @ConditionalOnProperty}-gated on this key, so when it is
 *       {@code false}/absent <strong>no</strong> scheduled bean is registered (ITs call {@code
 *       sweep()} directly). This field is informational for the sweep log; the load-bearing gate is
 *       the {@code @ConditionalOnProperty} on the trigger.
 *   <li>{@code intervalMs} — fixed-delay between sweep ticks (the scheduler reads the raw property
 *       via {@code fixedDelayString}; this mirror is for discoverability/logging).
 *   <li>{@code batchLimit} — max active links scanned per integration type per tick; the sweep
 *       {@code WARN}s when a tick fills the batch (no silent truncation) and the remainder heals on
 *       the next tick.
 * </ul>
 *
 * <p>Like {@code RollupSweepProperties}, the compact constructor <strong>normalizes-with-defaults
 * and never throws</strong> (memory: {@code validated-config-needs-test-yaml} — NOT
 * {@code @Validated}) so the bean binds profile-neutrally in every {@code @SpringBootTest} tier;
 * non-positive {@code intervalMs}/{@code batchLimit} clamp to their defaults. The keys are mirrored
 * into both {@code application.yml} files. Registered via {@code @EnableConfigurationProperties} in
 * the infrastructure {@code IntegrationConflictConfiguration} so the application layer never
 * depends on infrastructure.
 */
@ConfigurationProperties("deliveryline.integration.conflict-detection")
public record IntegrationConflictDetectionProperties(
    boolean enabled, long intervalMs, int batchLimit, List<String> autoPauseOnCategories) {

  /**
   * Default fixed-delay between sweep ticks (5 minutes), matching the scheduler's {@code :default}.
   */
  public static final long DEFAULT_INTERVAL_MS = 300_000L;

  /** Default per-tick, per-type scan cap. */
  public static final int DEFAULT_BATCH_LIMIT = 100;

  /**
   * Story 4.18 (AC5) — the default auto-pause set: the two state-drift categories NFR21 requires
   * the run to be paused on. An UNSET key (absent from {@code application.yml}) normalizes to this
   * default; an EXPLICITLY empty list ({@code []}) is honored as an opt-out (no auto-pause) — the
   * distinction is preserved because Spring binds the record component to {@code null} when the key
   * is absent and to an empty list when it is present-but-empty.
   */
  public static final List<String> DEFAULT_AUTO_PAUSE_CATEGORIES =
      List.of(
          IntegrationConflictCategory.EXTERNAL_STATE_ADVANCED.value(),
          IntegrationConflictCategory.EXTERNAL_STATE_REVERTED.value());

  public IntegrationConflictDetectionProperties {
    intervalMs = intervalMs <= 0 ? DEFAULT_INTERVAL_MS : intervalMs;
    batchLimit = batchLimit <= 0 ? DEFAULT_BATCH_LIMIT : batchLimit;
    // Normalize-never-throw (memory: validated-config-needs-test-yaml). null (unset) → the default
    // two-category set; a present list is trimmed + blank-stripped but preserved (empty = opt-out).
    // Unknown category tokens are NOT rejected here (the record must never throw); the
    // ConflictAutoPauseHandler resolves them via IntegrationConflictCategory.fromValue inside a
    // try/catch and skips any that do not parse.
    autoPauseOnCategories =
        autoPauseOnCategories == null
            ? DEFAULT_AUTO_PAUSE_CATEGORIES
            : autoPauseOnCategories.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .toList();
  }

  /**
   * Disabled, default-cadence, default-batch, default auto-pause categories — the production-safe
   * default posture.
   */
  public static IntegrationConflictDetectionProperties defaults() {
    return new IntegrationConflictDetectionProperties(
        false, DEFAULT_INTERVAL_MS, DEFAULT_BATCH_LIMIT, DEFAULT_AUTO_PAUSE_CATEGORIES);
  }
}
