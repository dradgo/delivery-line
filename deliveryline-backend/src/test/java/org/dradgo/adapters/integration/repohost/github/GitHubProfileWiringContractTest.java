package org.dradgo.adapters.integration.repohost.github;

import static org.assertj.core.api.Assertions.assertThat;

import org.dradgo.application.integration.repohost.RepositoryHostAdapter;
import org.dradgo.infrastructure.config.GitHubConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.web.client.RestClient;

/**
 * Pins story 3.13 AC6 ("no GitHub network call under github-mock") and the github-mock vs
 * github-real fail-fast invariant from Task 4 (mirrors {@code IntegrationProfileWiringContractTest}
 * for Linear). Uses {@link ApplicationContextRunner} so the slice is isolated from the persistence
 * stack and full Spring Boot auto-config — only the GitHub adapter slice + {@link
 * GitHubConfiguration} are scanned.
 *
 * <p>The {@code github-real} adapter does not exist until story 3.14, so there is no
 * "real-profile-loads-the-real-adapter" assertion here — the exclusivity guard works off active
 * profile names alone and is exercised by the both-profiles-active case.
 */
class GitHubProfileWiringContractTest {

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner().withUserConfiguration(GitHubWiringTestConfiguration.class);

  @Test
  void underGitHubMockProfileMockAdapterLoadsAndNoGitHubHttpClientBeanExists() {
    contextRunner
        .withPropertyValues("spring.profiles.active=github-mock")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).hasSingleBean(RepositoryHostAdapter.class);
              assertThat(context.getBean(RepositoryHostAdapter.class))
                  .isInstanceOf(GitHubMockAdapter.class);
              assertThat(context).hasSingleBean(GitHubMockScenarioRegistry.class);
              // AC6: no network capability under github-mock — no GitHub HTTP-client bean exists.
              assertThat(context).doesNotHaveBean("gitHubRestClient");
              assertThat(context).doesNotHaveBean(RestClient.class);
            });
  }

  @Test
  void withBothProfilesActiveContextRefreshFailsFast() {
    contextRunner
        .withPropertyValues("spring.profiles.active=github-mock,github-real")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure())
                  .hasStackTraceContaining("GitHub profile conflict");
            });
  }

  @Test
  void noProfileLeavesTheGitHubAdapterSliceInactive() {
    // AC2: mock activation is explicit via github-mock. A runtime that forgets to opt in must not
    // silently fall back to fixture-backed behavior.
    contextRunner.run(
        context -> {
          assertThat(context).hasNotFailed();
          assertThat(context).doesNotHaveBean(RepositoryHostAdapter.class);
          assertThat(context).doesNotHaveBean(GitHubMockAdapter.class);
          assertThat(context).doesNotHaveBean(GitHubMockScenarioRegistry.class);
        });
  }

  /**
   * Scans only the GitHub-adapter slice and explicitly imports {@link GitHubConfiguration} so the
   * broader {@code infrastructure.config} package is not pulled in.
   */
  @Configuration
  @ComponentScan(basePackageClasses = GitHubMockAdapter.class)
  @Import(GitHubConfiguration.class)
  static class GitHubWiringTestConfiguration {}
}
