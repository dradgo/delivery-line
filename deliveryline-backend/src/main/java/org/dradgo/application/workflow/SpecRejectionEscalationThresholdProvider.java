package org.dradgo.application.workflow;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Resolves the configured threshold above which {@code ApprovalService.rejectSpec} (story 2.10)
 * raises the per-run escalation marker.
 *
 * <p>Backed by the {@code deliveryline.workflow.spec-rejection-escalation-threshold} application
 * property (default {@code 3}). Surfaced as a component (instead of an inline {@code @Value}) so
 * unit tests can inject a deterministic threshold without re-bootstrapping the application context.
 *
 * <p>Per FR13 ("expose unresolved specification loops for human escalation"): once the run's spec
 * rejection loop counter reaches OR exceeds this threshold, the escalation marker is set ONCE and
 * an {@code escalation.required} event is appended ONCE. Subsequent rejections continue to advance
 * the counter and the workflow remains in {@code Investigating} (escalation does NOT terminate the
 * workflow).
 */
@Component
public class SpecRejectionEscalationThresholdProvider {

  static final int ULTIMATE_FALLBACK = 3;

  private static final Logger log =
      LoggerFactory.getLogger(SpecRejectionEscalationThresholdProvider.class);

  private final int threshold;

  public SpecRejectionEscalationThresholdProvider(
      @Value("${deliveryline.workflow.spec-rejection-escalation-threshold:3}") int threshold) {
    if (threshold < 1) {
      log.warn(
          "spec-rejection-escalation-threshold misconfigured (value={}, must be >=1) — falling back to {}",
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
