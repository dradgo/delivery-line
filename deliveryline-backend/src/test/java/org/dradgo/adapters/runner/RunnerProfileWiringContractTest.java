package org.dradgo.adapters.runner;

import static org.assertj.core.api.Assertions.assertThat;

import org.dradgo.adapters.runner.docker.DockerEngineGateway;
import org.dradgo.application.runner.RunnerProperties;
import org.dradgo.application.runner.RunnerSecretsService;
import org.dradgo.application.runner.spi.RunnerAdapter;
import org.dradgo.application.runner.spi.RunnerScratchStore;
import org.dradgo.application.runner.spi.RunnerWorkspaceStore;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Pins AC9 profile-wiring invariant: {@link MockRunnerAdapter} is the default {@link RunnerAdapter}
 * bean (the broker can autowire it without an explicit profile switch), and {@link
 * DockerRunnerAdapter} takes the slot under {@code runners.docker} per story 3.1 AC1.
 *
 * <p>Implemented with {@link ApplicationContextRunner} rather than {@code @SpringBootTest} so the
 * assertion isolates the profile semantics of the runner adapter stack from the rest of the
 * application context (no database, no Flyway, no slow boot, no real Docker daemon).
 */
class RunnerProfileWiringContractTest {

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner().withUserConfiguration(RunnerStackTestConfiguration.class);

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
          assertThat(context).doesNotHaveBean(DockerRunnerAdapter.class);
        });
  }

  @Test
  void dockerRunnerAdapterLoadsWhenRunnersDockerProfileIsActive() {
    // Story 3.1 AC1: under runners.docker, the Docker adapter is the single RunnerAdapter bean
    // and the mock stack is excluded (so there's no duplicate-bean conflict).
    contextRunner
        .withPropertyValues("spring.profiles.active=runners.docker")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).doesNotHaveBean(MockRunnerAdapter.class);
              assertThat(context).doesNotHaveBean(MockRunnerScenarioRegistry.class);
              assertThat(context).hasSingleBean(RunnerAdapter.class);
              assertThat(context.getBean(RunnerAdapter.class))
                  .isInstanceOf(DockerRunnerAdapter.class);
              assertThat(context).hasSingleBean(RunnerWorkspaceStore.class);
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
   * Slice configuration that declares the runner adapter stack (both mock + Docker scan into the
   * same {@code adapters.runner} package) plus the minimal collaborators each adapter's constructor
   * needs. Mockito stubs stand in for the {@link RunnerScratchStore} + {@link RunnerWorkspaceStore}
   * + {@link DockerEngineGateway} ports so the slice does not pull in the persistence stack or
   * require a real Docker daemon.
   */
  @Configuration
  @org.springframework.context.annotation.ComponentScan(
      basePackageClasses = MockRunnerAdapter.class,
      excludeFilters = {
        @org.springframework.context.annotation.ComponentScan.Filter(
            type = org.springframework.context.annotation.FilterType.REGEX,
            pattern = ".*TestConfiguration"),
        @org.springframework.context.annotation.ComponentScan.Filter(
            type = org.springframework.context.annotation.FilterType.REGEX,
            pattern = "org\\.dradgo\\.adapters\\.runner\\.docker\\.DockerConfiguration"),
        @org.springframework.context.annotation.ComponentScan.Filter(
            type = org.springframework.context.annotation.FilterType.REGEX,
            pattern =
                "org\\.dradgo\\.adapters\\.runner\\.docker\\.DockerRunnerLifecycleConfiguration.*")
      })
  static class RunnerStackTestConfiguration {

    @Bean
    RunnerScratchStore scratchStore() {
      return Mockito.mock(RunnerScratchStore.class);
    }

    @Bean
    RunnerWorkspaceStore runnerWorkspaceStore() {
      return Mockito.mock(RunnerWorkspaceStore.class);
    }

    @Bean
    DockerEngineGateway dockerEngineGateway() {
      return Mockito.mock(DockerEngineGateway.class);
    }

    // Story 3.5: DockerRunnerAdapter's constructor now requires RunnerSecretsService. The slice
    // only exercises profile-wiring (no dispatch), so a mock satisfies the dependency.
    @Bean
    RunnerSecretsService runnerSecretsService() {
      return Mockito.mock(RunnerSecretsService.class);
    }

    @Bean
    RunnerProperties runnerProperties() {
      return RunnerProperties.defaults();
    }
  }
}
