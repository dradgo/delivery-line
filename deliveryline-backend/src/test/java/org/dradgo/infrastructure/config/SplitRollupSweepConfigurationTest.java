package org.dradgo.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.dradgo.application.workflow.SplitRollupReconciliationSweepService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Story 3f-8 (AC4) — the {@code @ConditionalOnProperty} gate on {@link
 * SplitRollupSweepConfiguration}. When the sweep is disabled (the test/default posture) the
 * scheduler config — and therefore the scheduled trigger bean — is never registered, so a disabled
 * sweep adds zero scheduled work.
 */
class SplitRollupSweepConfigurationTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withBean(
              SplitRollupReconciliationSweepService.class,
              () -> mock(SplitRollupReconciliationSweepService.class))
          .withUserConfiguration(SplitRollupSweepConfiguration.class);

  @Test
  void schedulerBeanAbsentWhenDisabled() {
    runner
        .withPropertyValues("deliveryline.complex-ticket-flow.rollup-sweep.enabled=false")
        .run(context -> assertThat(context).doesNotHaveBean(SplitRollupSweepConfiguration.class));
  }

  @Test
  void schedulerBeanAbsentWhenPropertyMissing() {
    runner.run(context -> assertThat(context).doesNotHaveBean(SplitRollupSweepConfiguration.class));
  }

  @Test
  void schedulerBeanPresentWhenEnabled() {
    runner
        .withPropertyValues("deliveryline.complex-ticket-flow.rollup-sweep.enabled=true")
        .run(context -> assertThat(context).hasSingleBean(SplitRollupSweepConfiguration.class));
  }
}
