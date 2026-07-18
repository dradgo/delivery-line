package org.dradgo.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.dradgo.application.integration.conflict.IntegrationConflictTerminalRunReconciliationSweepService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Story 4.30 (AC4) — the {@code @ConditionalOnProperty} gate on {@link
 * IntegrationConflictTerminalSweepConfiguration}. When the terminal-run sweep is disabled (the
 * test/default posture) the scheduler config — and therefore the scheduled trigger bean — is never
 * registered, so a disabled sweep adds zero scheduled work (byte-identical to pre-story behavior).
 */
class IntegrationConflictTerminalSweepConfigurationTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withBean(
              IntegrationConflictTerminalRunReconciliationSweepService.class,
              () -> mock(IntegrationConflictTerminalRunReconciliationSweepService.class))
          .withUserConfiguration(IntegrationConflictTerminalSweepConfiguration.class);

  @Test
  void schedulerBeanAbsentWhenDisabled() {
    runner
        .withPropertyValues("deliveryline.integration-conflict.terminal-sweep.enabled=false")
        .run(
            context ->
                assertThat(context)
                    .doesNotHaveBean(IntegrationConflictTerminalSweepConfiguration.class));
  }

  @Test
  void schedulerBeanAbsentWhenPropertyMissing() {
    runner.run(
        context ->
            assertThat(context)
                .doesNotHaveBean(IntegrationConflictTerminalSweepConfiguration.class));
  }

  @Test
  void schedulerBeanPresentWhenEnabled() {
    runner
        .withPropertyValues("deliveryline.integration-conflict.terminal-sweep.enabled=true")
        .run(
            context ->
                assertThat(context)
                    .hasSingleBean(IntegrationConflictTerminalSweepConfiguration.class));
  }
}
