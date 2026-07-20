package org.dradgo.application.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Story 3h-2 (AC5) — the lint fix cap resolver (twin of BuildFixEscalationThresholdProvider). */
class LintFixEscalationThresholdProviderTest {

  @Test
  void honorsAConfiguredPositiveThreshold() {
    assertThat(new LintFixEscalationThresholdProvider(5).get()).isEqualTo(5);
    assertThat(new LintFixEscalationThresholdProvider(1).get()).isEqualTo(1);
  }

  @Test
  void fallsBackToTheUltimateFallbackWhenMisconfiguredBelowOne() {
    assertThat(new LintFixEscalationThresholdProvider(0).get())
        .isEqualTo(LintFixEscalationThresholdProvider.ULTIMATE_FALLBACK);
    assertThat(new LintFixEscalationThresholdProvider(-3).get())
        .isEqualTo(LintFixEscalationThresholdProvider.ULTIMATE_FALLBACK);
  }
}
