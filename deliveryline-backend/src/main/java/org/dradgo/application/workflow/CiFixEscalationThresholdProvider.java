package org.dradgo.application.workflow;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Story 3h-5 (AC2/AC6, FR79) — resolves the bounded CI investigation/fix cap, which is BOTH the
 * maximum number of CI fix attempts AND the escalation threshold. The CI-investigation twin of
 * {@link BuildFixEscalationThresholdProvider} / {@code LintFixEscalationThresholdProvider}.
 *
 * <p>Backed by the {@code deliveryline.workflow.ci-fix-max-loops} application property (default
 * {@code 3}). Surfaced as a component (instead of an inline {@code @Value}) so unit tests can
 * inject a deterministic threshold without re-bootstrapping the application context.
 *
 * <p>On a red CI the implementation runner is re-dispatched with the CI-failure log as referenced
 * feedback while {@code ci_fix_loop_count <= threshold} (i.e. up to {@code threshold} fix
 * attempts); on the {@code (threshold + 1)}-th consecutive red CI ({@code ci_fix_loop_count >
 * threshold}) the run flips the shared per-run escalation marker ONCE and is <strong>left parked at
 * {@code WaitingForReview}</strong> for Epic-4 recovery — it NEVER transitions to {@code Failed}
 * ({@code WaitingForReview} has no {@code → Failed} edge; Decision 5). This is the lint-loop cap
 * semantics, NOT the build-loop cap (which fails the run).
 */
@Component
public class CiFixEscalationThresholdProvider {

  static final int ULTIMATE_FALLBACK = 3;

  private static final Logger log = LoggerFactory.getLogger(CiFixEscalationThresholdProvider.class);

  private final int threshold;

  public CiFixEscalationThresholdProvider(
      @Value("${deliveryline.workflow.ci-fix-max-loops:3}") int threshold) {
    if (threshold < 1) {
      log.warn(
          "ci-fix-max-loops misconfigured (value={}, must be >=1) — falling back to {}",
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
