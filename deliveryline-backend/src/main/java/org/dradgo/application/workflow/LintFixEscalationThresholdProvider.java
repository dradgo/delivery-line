package org.dradgo.application.workflow;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Story 3h-2 (AC5, FR76) — resolves the bounded lint fix-loop cap, which is the threshold at which
 * the shared per-run escalation marker is flipped (for VISIBILITY only — the lint fix loop is
 * operator-driven and NEVER auto-fails the run, Decision 3). The lint-gate twin of {@link
 * BuildFixEscalationThresholdProvider}.
 *
 * <p>Backed by the {@code deliveryline.workflow.lint-fix-max-loops} application property (default
 * {@code 3}). Surfaced as a component (instead of an inline {@code @Value}) so unit tests can
 * inject a deterministic threshold without re-bootstrapping the application context.
 *
 * <p>On each operator {@code request_lint_fix} the implementation runner is re-dispatched and the
 * run re-parks at {@code WaitingForLintApproval}; when {@code lint_fix_loop_count} reaches this
 * threshold the shared escalation marker is flipped ONCE (there is no {@code FAILED} transition and
 * no infinite-loop risk — each iteration requires a manual operator action).
 */
@Component
public class LintFixEscalationThresholdProvider {

  static final int ULTIMATE_FALLBACK = 3;

  private static final Logger log =
      LoggerFactory.getLogger(LintFixEscalationThresholdProvider.class);

  private final int threshold;

  public LintFixEscalationThresholdProvider(
      @Value("${deliveryline.workflow.lint-fix-max-loops:3}") int threshold) {
    if (threshold < 1) {
      log.warn(
          "lint-fix-max-loops misconfigured (value={}, must be >=1) — falling back to {}",
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
