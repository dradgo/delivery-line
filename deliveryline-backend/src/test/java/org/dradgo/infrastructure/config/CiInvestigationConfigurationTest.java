package org.dradgo.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.dradgo.application.workflow.ci.CiStatusPollingService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Story 3h-5 (AC4, Decision 4) — the load-bearing parity gate. The CI-investigation sweep trigger
 * ({@link CiInvestigationConfiguration}, and therefore its {@code @Scheduled poll}) must be
 * registered ONLY when {@code deliveryline.workflow.ci-investigation.enabled=true}; when the flag
 * is {@code false} or absent the whole configuration is not registered, so a disabled sweep adds
 * zero scheduled work and the delivery tail is byte-identical to pre-3h-5.
 *
 * <p>Uses {@link ApplicationContextRunner} (no Spring Boot / Postgres) so the conditional wiring is
 * asserted directly and cheaply — the review flagged this gate as having no coverage at any level.
 */
class CiInvestigationConfigurationTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withBean(CiStatusPollingService.class, () -> mock(CiStatusPollingService.class))
          .withUserConfiguration(CiInvestigationConfiguration.class);

  @Test
  void sweepTriggerAbsentWhenFlagAbsent() {
    runner.run(context -> assertThat(context).doesNotHaveBean(CiInvestigationConfiguration.class));
  }

  @Test
  void sweepTriggerAbsentWhenFlagFalse() {
    runner
        .withPropertyValues("deliveryline.workflow.ci-investigation.enabled=false")
        .run(context -> assertThat(context).doesNotHaveBean(CiInvestigationConfiguration.class));
  }

  @Test
  void sweepTriggerRegisteredWhenFlagTrue() {
    runner
        .withPropertyValues("deliveryline.workflow.ci-investigation.enabled=true")
        .run(context -> assertThat(context).hasSingleBean(CiInvestigationConfiguration.class));
  }
}
