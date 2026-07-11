package org.dradgo.application.workflow.ci;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Story 3h-5 (AC4) — the CI-investigation properties normalize-with-defaults and never throw. */
class CiInvestigationPropertiesTest {

  @Test
  void defaultsAreDisabledWithProductionSafeCadence() {
    CiInvestigationProperties defaults = CiInvestigationProperties.defaults();
    assertThat(defaults.enabled()).isFalse();
    assertThat(defaults.intervalMs()).isEqualTo(CiInvestigationProperties.DEFAULT_INTERVAL_MS);
    assertThat(defaults.batchLimit()).isEqualTo(CiInvestigationProperties.DEFAULT_BATCH_LIMIT);
    assertThat(defaults.maxPollAttempts())
        .isEqualTo(CiInvestigationProperties.DEFAULT_MAX_POLL_ATTEMPTS);
  }

  @Test
  void clampsNonPositiveIntervalBatchAndBudgetToDefaults() {
    CiInvestigationProperties clamped = new CiInvestigationProperties(true, 0, -1, 0);
    assertThat(clamped.enabled()).isTrue();
    assertThat(clamped.intervalMs()).isEqualTo(CiInvestigationProperties.DEFAULT_INTERVAL_MS);
    assertThat(clamped.batchLimit()).isEqualTo(CiInvestigationProperties.DEFAULT_BATCH_LIMIT);
    assertThat(clamped.maxPollAttempts())
        .isEqualTo(CiInvestigationProperties.DEFAULT_MAX_POLL_ATTEMPTS);
  }

  @Test
  void honoursConfiguredPositiveValues() {
    CiInvestigationProperties custom = new CiInvestigationProperties(true, 15_000, 5, 10);
    assertThat(custom.intervalMs()).isEqualTo(15_000);
    assertThat(custom.batchLimit()).isEqualTo(5);
    assertThat(custom.maxPollAttempts()).isEqualTo(10);
  }
}
