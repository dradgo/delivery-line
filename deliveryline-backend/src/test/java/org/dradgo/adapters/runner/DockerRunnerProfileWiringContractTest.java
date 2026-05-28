package org.dradgo.adapters.runner;

import static org.assertj.core.api.Assertions.assertThat;

import org.dradgo.adapters.runner.docker.DockerEngineGateway;
import org.dradgo.application.runner.RunnerProperties;
import org.dradgo.application.runner.spi.RunnerAdapter;
import org.dradgo.application.runner.spi.RunnerScratchStore;
import org.dradgo.application.runner.spi.RunnerWorkspaceStore;
import org.dradgo.infrastructure.config.RunnerConfiguration;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Story 3.1 AC10(m) — pins {@link DockerRunnerAdapter} profile wiring explicitly (mirror of {@code
 * RunnerProfileWiringContractTest} for the mock side). The shared {@code
 * RunnerProfileWiringContractTest} already covers the resolved-bean assertion; this class adds:
 *
 * <ul>
 *   <li>{@link RunnerConfiguration} mutual-exclusion safeguard (both {@code runners.mock} + {@code
 *       runners.docker}) raises {@link IllegalStateException} per existing wiring.
 *   <li>{@link RunnerWorkspaceStore} is autowired into the adapter (the new SPI port introduced in
 *       this story).
 *   <li>Trap T10: {@link DockerEngineGateway} is profile-gated, so contributors without Docker can
 *       still boot the fast-tier context under no-profile.
 * </ul>
 */
class DockerRunnerProfileWiringContractTest {

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner().withUserConfiguration(DockerSliceTestConfiguration.class);

  @Test
  void dockerRunnerAdapterIsSingleAdapterUnderRunnersDockerProfile() {
    contextRunner
        .withPropertyValues("spring.profiles.active=runners.docker")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).hasSingleBean(RunnerAdapter.class);
              assertThat(context.getBean(RunnerAdapter.class))
                  .isInstanceOf(DockerRunnerAdapter.class);
              assertThat(context).doesNotHaveBean(MockRunnerAdapter.class);
            });
  }

  @Test
  void dockerRunnerAdapterIsExcludedWhenNoProfileIsActive() {
    // Trap T10: the docker-gated bean must not load under no profile so contributors without
    // Docker can boot the fast tier without the gateway probing the engine.
    contextRunner.run(
        context -> {
          assertThat(context).hasNotFailed();
          assertThat(context).doesNotHaveBean(DockerRunnerAdapter.class);
        });
  }

  @Test
  void mutuallyExclusiveProfilesFailFastAtStartup() {
    // RunnerConfiguration.assertExclusiveRunnerProfile fires in the constructor — wiring it
    // here under both profiles must throw IllegalStateException with "mutually exclusive" in the
    // message. Uses a dedicated ApplicationContextRunner (NOT the shared `contextRunner` field)
    // so the slice's adapter-scan config does not collide with the mutex-guard config's bean
    // definitions.
    new ApplicationContextRunner()
        .withUserConfiguration(MutexProfileGuardTestConfiguration.class)
        .withPropertyValues("spring.profiles.active=runners.mock,runners.docker")
        .run(
            context -> {
              assertThat(context).hasFailed();
              Throwable failure = context.getStartupFailure();
              assertThat(failure).isNotNull();
              org.assertj.core.api.Assertions.assertThat(failure)
                  .rootCause()
                  .isInstanceOf(IllegalStateException.class)
                  .hasMessageContaining("mutually exclusive");
            });
  }

  @Configuration
  @org.springframework.context.annotation.ComponentScan(
      basePackageClasses = MockRunnerAdapter.class,
      excludeFilters = {
        @org.springframework.context.annotation.ComponentScan.Filter(
            type = org.springframework.context.annotation.FilterType.REGEX,
            pattern = ".*TestConfiguration"),
        @org.springframework.context.annotation.ComponentScan.Filter(
            type = org.springframework.context.annotation.FilterType.REGEX,
            pattern = "org\\.dradgo\\.adapters\\.runner\\.docker\\.DockerConfiguration")
      })
  static class DockerSliceTestConfiguration {

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

    @Bean
    RunnerProperties runnerProperties() {
      return RunnerProperties.defaults();
    }
  }

  /**
   * Minimal harness to exercise {@link
   * RunnerConfiguration#assertExclusiveRunnerProfile(org.springframework.core.env.Environment)}
   * without dragging in the broker or scheduling machinery. Spring will construct {@code
   * RunnerConfiguration}, which validates both profiles being active and throws.
   */
  @Configuration
  @org.springframework.context.annotation.Import(RunnerConfiguration.class)
  static class MutexProfileGuardTestConfiguration {

    @Bean
    org.dradgo.application.runner.RunnerBroker runnerBroker() {
      return Mockito.mock(org.dradgo.application.runner.RunnerBroker.class);
    }

    @Bean
    RunnerProperties runnerProperties() {
      return RunnerProperties.defaults();
    }
  }
}
