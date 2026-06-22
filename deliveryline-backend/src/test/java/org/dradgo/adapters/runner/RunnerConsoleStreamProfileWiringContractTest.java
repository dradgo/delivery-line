package org.dradgo.adapters.runner;

import static org.assertj.core.api.Assertions.assertThat;

import org.dradgo.adapters.runner.docker.DockerEngineGateway;
import org.dradgo.adapters.runner.docker.DockerRunnerConsoleStreamAdapter;
import org.dradgo.application.runner.spi.RunnerConsoleStreamPort;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;

/**
 * Story 3d-6 (Task 10) — pins the profile-wiring invariant for {@link RunnerConsoleStreamPort}:
 * exactly ONE bean is active per profile. Under {@code runners.docker} the live {@link
 * DockerRunnerConsoleStreamAdapter} takes the slot; under the default (mock) profile the {@link
 * NoLiveRunnerConsoleStreamAdapter} does (so the application rejects with {@code console-not-live}
 * deterministically). Mirrors {@link RunnerProfileWiringContractTest} — an {@link
 * ApplicationContextRunner} slice, no DB / Flyway / real Docker daemon.
 */
class RunnerConsoleStreamProfileWiringContractTest {

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withUserConfiguration(ConsoleStreamStackTestConfiguration.class);

  @Test
  void noLiveConsoleAdapterLoadsByDefaultWhenNoProfileIsActive() {
    contextRunner.run(
        context -> {
          assertThat(context).hasNotFailed();
          assertThat(context).hasSingleBean(RunnerConsoleStreamPort.class);
          assertThat(context.getBean(RunnerConsoleStreamPort.class))
              .isInstanceOf(NoLiveRunnerConsoleStreamAdapter.class);
          assertThat(context).doesNotHaveBean(DockerRunnerConsoleStreamAdapter.class);
        });
  }

  @Test
  void dockerConsoleAdapterLoadsWhenRunnersDockerProfileIsActive() {
    contextRunner
        .withPropertyValues("spring.profiles.active=runners.docker")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).hasSingleBean(RunnerConsoleStreamPort.class);
              assertThat(context.getBean(RunnerConsoleStreamPort.class))
                  .isInstanceOf(DockerRunnerConsoleStreamAdapter.class);
              assertThat(context).doesNotHaveBean(NoLiveRunnerConsoleStreamAdapter.class);
            });
  }

  /**
   * Scans ONLY the two console-stream adapter classes (so {@code @Profile} is honored) plus a mock
   * {@link DockerEngineGateway} the Docker adapter's constructor needs.
   */
  @Configuration
  @ComponentScan(
      basePackageClasses = NoLiveRunnerConsoleStreamAdapter.class,
      useDefaultFilters = false,
      includeFilters =
          @ComponentScan.Filter(
              type = FilterType.ASSIGNABLE_TYPE,
              classes = {
                NoLiveRunnerConsoleStreamAdapter.class,
                DockerRunnerConsoleStreamAdapter.class
              }))
  static class ConsoleStreamStackTestConfiguration {

    @Bean
    DockerEngineGateway dockerEngineGateway() {
      return Mockito.mock(DockerEngineGateway.class);
    }
  }
}
