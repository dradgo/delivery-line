package org.dradgo.adapters.runner;

import static org.assertj.core.api.Assertions.assertThat;

import org.dradgo.application.runner.RunnerProperties;
import org.dradgo.application.runner.spi.RunnerAdapter;
import org.dradgo.application.runner.spi.RunnerScratchStore;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Pins AC9 profile-wiring invariant: {@link MockRunnerAdapter} is the default {@link RunnerAdapter}
 * bean (the broker can autowire it without an explicit profile switch), and it is intentionally NOT
 * loaded when the {@code runners.docker} profile is active so the Epic-3 Docker runner adapter can
 * take its slot without a duplicate-bean conflict.
 *
 * <p>Implemented with {@link ApplicationContextRunner} rather than {@code @SpringBootTest} so the
 * assertion isolates the profile semantics of the mock-runner stack from the rest of the
 * application context (no database, no Flyway, no slow boot).
 */
class RunnerProfileWiringContractTest {

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner().withUserConfiguration(MockRunnerStackTestConfiguration.class);

  @Test
  void mockRunnerAdapterLoadsByDefaultWhenNoProfileIsActive() {
    // AC9: mock is the default adapter. Under no explicit profile the bean must resolve to
    // MockRunnerAdapter so the broker has a working RunnerAdapter for local/test/demo flows.
    contextRunner.run(
        context -> {
          assertThat(context).hasNotFailed();
          assertThat(context).hasSingleBean(RunnerAdapter.class);
          assertThat(context.getBean(RunnerAdapter.class)).isInstanceOf(MockRunnerAdapter.class);
          assertThat(context).hasSingleBean(MockRunnerScenarioRegistry.class);
        });
  }

  @Test
  void mockRunnerAdapterIsExcludedWhenRunnersDockerProfileIsActive() {
    // AC9: docker is the Epic-3 opt-in. When runners.docker is active, the mock bean must
    // not be registered so the future DockerRunnerAdapter occupies the RunnerAdapter slot
    // without a duplicate-bean conflict.
    contextRunner
        .withPropertyValues("spring.profiles.active=runners.docker")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).doesNotHaveBean(MockRunnerAdapter.class);
              assertThat(context).doesNotHaveBean(MockRunnerScenarioRegistry.class);
              assertThat(context).doesNotHaveBean(RunnerAdapter.class);
            });
  }

  @Test
  void mockRunnerAdapterStillLoadsUnderExplicitRunnersMockProfile() {
    // Backwards-compatibility: legacy {@code runners.mock} activations (e.g., production
    // {@code application.yml} that explicitly enables the profile group) still resolve to
    // the mock adapter — the move to @Profile("!runners.docker") must not regress this.
    contextRunner
        .withPropertyValues("spring.profiles.active=runners.mock")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).hasSingleBean(RunnerAdapter.class);
              assertThat(context.getBean(RunnerAdapter.class))
                  .isInstanceOf(MockRunnerAdapter.class);
            });
  }

  /**
   * Slice configuration that only declares the mock-runner stack plus the minimal collaborators the
   * adapter constructor needs. Mockito stubs stand in for the {@link RunnerScratchStore} port and
   * {@link RunnerProperties} record so the slice does not pull in the persistence stack.
   */
  @Configuration
  @org.springframework.context.annotation.ComponentScan(
      basePackageClasses = MockRunnerAdapter.class)
  static class MockRunnerStackTestConfiguration {

    @Bean
    RunnerScratchStore scratchStore() {
      return Mockito.mock(RunnerScratchStore.class);
    }

    @Bean
    RunnerProperties runnerProperties() {
      return RunnerProperties.defaults();
    }
  }
}
