package org.dradgo.application.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Story 3h-5 (AC2/AC6) — the CI fix cap clamps a misconfigured value to the fallback. */
class CiFixEscalationThresholdProviderTest {

  @Test
  void returnsConfiguredThresholdWhenValid() {
    assertThat(new CiFixEscalationThresholdProvider(5).get()).isEqualTo(5);
    assertThat(new CiFixEscalationThresholdProvider(1).get()).isEqualTo(1);
  }

  @Test
  void clampsNonPositiveToFallback() {
    assertThat(new CiFixEscalationThresholdProvider(0).get())
        .isEqualTo(CiFixEscalationThresholdProvider.ULTIMATE_FALLBACK);
    assertThat(new CiFixEscalationThresholdProvider(-3).get())
        .isEqualTo(CiFixEscalationThresholdProvider.ULTIMATE_FALLBACK);
  }
}
