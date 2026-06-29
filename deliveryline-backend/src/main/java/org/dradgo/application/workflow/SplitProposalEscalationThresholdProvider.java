package org.dradgo.application.workflow;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Resolves the configured threshold above which {@code SplitProposalService.repropose} (story 3f-4)
 * raises the per-run escalation marker.
 *
 * <p>Backed by the {@code deliveryline.workflow.split-proposal-escalation-threshold} application
 * property (default {@code 3}). Surfaced as a component (instead of an inline {@code @Value}) so
 * unit tests can inject a deterministic threshold without re-bootstrapping the application context.
 * Mirrors {@code SpecRejectionEscalationThresholdProvider} exactly.
 *
 * <p>Per FR13: once the run's split-proposal re-propose counter reaches OR exceeds this threshold,
 * the SHARED escalation marker is set ONCE and an {@code escalation.required} event is appended
 * ONCE. Subsequent re-proposes continue to advance the counter and the run stays parked at its
 * spec/review gate (escalation does NOT terminate the workflow or move the gate).
 */
@Component
public class SplitProposalEscalationThresholdProvider {

  static final int ULTIMATE_FALLBACK = 3;

  private static final Logger log =
      LoggerFactory.getLogger(SplitProposalEscalationThresholdProvider.class);

  private final int threshold;

  public SplitProposalEscalationThresholdProvider(
      @Value("${deliveryline.workflow.split-proposal-escalation-threshold:3}") int threshold) {
    if (threshold < 1) {
      log.warn(
          "split-proposal-escalation-threshold misconfigured (value={}, must be >=1) — falling back to {}",
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
