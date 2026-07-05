package org.dradgo.application.workflow;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Story 3h-1 (AC4/AC5, FR75) — resolves the bounded build auto-fix cap, which is BOTH the maximum
 * number of fix attempts AND the escalation threshold. The build-validation twin of {@link
 * ImplementationRejectionEscalationThresholdProvider}.
 *
 * <p>Backed by the {@code deliveryline.workflow.build-fix-max-loops} application property (default
 * {@code 3}). Surfaced as a component (instead of an inline {@code @Value}) so unit tests can
 * inject a deterministic threshold without re-bootstrapping the application context.
 *
 * <p>On a BUILD failure the implementation runner is re-dispatched with the build-error log as
 * referenced feedback while {@code build_fix_loop_count <= threshold} (i.e. up to {@code threshold}
 * fix attempts); the run transitions to {@code FAILED} with {@code RUNNER_BUILD_FAILED} on the
 * {@code (threshold + 1)}-th consecutive build failure ({@code build_fix_loop_count > threshold})
 * and flips the (shared) per-run escalation marker ONCE, leaving the run for Epic-4 recovery.
 */
@Component
public class BuildFixEscalationThresholdProvider {

  static final int ULTIMATE_FALLBACK = 3;

  private static final Logger log =
      LoggerFactory.getLogger(BuildFixEscalationThresholdProvider.class);

  private final int threshold;

  public BuildFixEscalationThresholdProvider(
      @Value("${deliveryline.workflow.build-fix-max-loops:3}") int threshold) {
    if (threshold < 1) {
      log.warn(
          "build-fix-max-loops misconfigured (value={}, must be >=1) — falling back to {}",
          threshold,
          ULTIMATE_FALLBACK);
      this.threshold = ULTIMATE_FALLBACK;
    } else {
      this.threshold = threshold;
    }
  }

  public int get() {
    return threshold;
  }
}
