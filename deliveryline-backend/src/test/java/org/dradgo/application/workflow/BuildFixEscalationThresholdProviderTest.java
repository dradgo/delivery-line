package org.dradgo.application.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Story 3h-1 (Task 9, AC4/AC5) — the build-fix cap provider binds a valid value and falls back to
 * the ULTIMATE_FALLBACK for a misconfigured (&lt; 1) value.
 */
class BuildFixEscalationThresholdProviderTest {

  @Test
  void bindsAConfiguredPositiveThreshold() {
    assertEquals(5, new BuildFixEscalationThresholdProvider(5).get());
  }

  @Test
  void fallsBackWhenMisconfiguredBelowOne() {
    assertEquals(
        BuildFixEscalationThresholdProvider.ULTIMATE_FALLBACK,
        new BuildFixEscalationThresholdProvider(0).get());
    assertEquals(
        BuildFixEscalationThresholdProvider.ULTIMATE_FALLBACK,
        new BuildFixEscalationThresholdProvider(-3).get());
  }
}
