package org.dradgo.adapters.integration.repohost.github;

import static org.assertj.core.api.Assertions.assertThat;

import org.dradgo.infrastructure.config.GitHubConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.client.RestClient;

/**
 * Pins story 3.14 AC11(c): under {@code github-real} the {@code gitHubRestClient} bean is present;
 * a blank token fail-fasts; and the bean is absent when {@code github-real} is inactive. Uses
 * {@link ApplicationContextRunner} importing only {@link GitHubConfiguration} so the slice stays
 * free of the adapter's other collaborators (mirrors {@code GitHubProfileWiringContractTest}).
 */
class GitHubRealConfigurationTest {

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner().withUserConfiguration(GitHubConfiguration.class);

  @Test
  void gitHubRestClientBeanPresentUnderGithubRealWithToken() {
    contextRunner
        .withPropertyValues(
            "spring.profiles.active=github-real", "deliveryline.github.token=ghp_real_test_token")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).hasBean("gitHubRestClient");
              assertThat(context.getBean("gitHubRestClient")).isInstanceOf(RestClient.class);
            });
  }

  @Test
  void githubTokenEnvironmentAliasCanPopulateConfiguredToken() {
    contextRunner
        .withSystemProperties("GITHUB_TOKEN=ghp_env_token")
        .withPropertyValues(
            "spring.profiles.active=github-real", "deliveryline.github.token=${GITHUB_TOKEN:}")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).hasBean("gitHubRestClient");
            });
  }

  @Test
  void blankTokenUnderGithubRealStillStartsSoDoctorCanReportTokenMissing() {
    contextRunner
        .withPropertyValues("spring.profiles.active=github-real")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).hasBean("gitHubRestClient");
            });
  }

  @Test
  void excessiveConnectTimeoutIsNormalizedInsteadOfOverflowing() {
    contextRunner
        .withPropertyValues(
            "spring.profiles.active=github-real",
            "deliveryline.github.token=ghp_real_test_token",
            "deliveryline.github.timeout.connect-ms=999999999999")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).hasBean("gitHubRestClient");
            });
  }

  @Test
  void noGitHubRestClientBeanWhenGithubRealInactive() {
    contextRunner
        .withPropertyValues("spring.profiles.active=github-mock")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).doesNotHaveBean("gitHubRestClient");
            });
  }
}
