package org.dradgo.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.dradgo.application.integration.repohost.RepositoryHostProperties;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

/**
 * Story 3.33 (AC5 / AC11 / OQ-4) — config-driven repository-host selection. The {@code
 * deliveryline.integration.repo-host.kind} selector defaults to {@code github} and is validated at
 * boot by {@link GitHubConfiguration}: a kind with no implementation on the classpath fail-fasts.
 * The load-bearing bean gating remains the {@code github-mock}/{@code github-real} profiles, which
 * this test leaves inactive (no profile is needed to exercise the kind guard).
 */
class RepositoryHostConfigurationTest {

  private static Environment noProfiles() {
    Environment environment = mock(Environment.class);
    when(environment.getActiveProfiles()).thenReturn(new String[] {});
    return environment;
  }

  @Test
  void kindDefaultsToGithubAndNormalizes() {
    assertThat(new RepositoryHostProperties(null).kind()).isEqualTo("github");
    assertThat(new RepositoryHostProperties("  ").kind()).isEqualTo("github");
    assertThat(new RepositoryHostProperties(" GitHub ").kind()).isEqualTo("github");
    assertThat(RepositoryHostProperties.defaults().isGithub()).isTrue();
  }

  @Test
  void githubKindActivatesWithoutFailFast() {
    assertThatCode(
            () -> new GitHubConfiguration(noProfiles(), new RepositoryHostProperties("github")))
        .doesNotThrowAnyException();
  }

  @Test
  void unsupportedKindFailsFastAtBoot() {
    assertThatThrownBy(
            () -> new GitHubConfiguration(noProfiles(), new RepositoryHostProperties("bitbucket")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("repo-host.kind=bitbucket")
        .hasMessageContaining("github");
  }
}
