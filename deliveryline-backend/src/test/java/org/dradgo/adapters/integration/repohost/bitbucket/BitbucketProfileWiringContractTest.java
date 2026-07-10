package org.dradgo.adapters.integration.repohost.bitbucket;

import static org.assertj.core.api.Assertions.assertThat;

import org.dradgo.application.integration.repohost.RepositoryHostAdapter;
import org.dradgo.infrastructure.config.BitbucketConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.web.client.RestClient;

/**
 * Story 3i-3 (FR82) — pins "no Bitbucket network call under bitbucket-mock" and the bitbucket-mock
 * vs bitbucket-real fail-fast invariant. Mirrors {@code GitHubProfileWiringContractTest}. Uses
 * {@link ApplicationContextRunner} so the slice is isolated from the persistence stack and full
 * Spring Boot auto-config — only the Bitbucket adapter slice + {@link BitbucketConfiguration} are
 * scanned.
 */
class BitbucketProfileWiringContractTest {

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner().withUserConfiguration(BitbucketWiringTestConfiguration.class);

  @Test
  void underBitbucketMockProfileMockAdapterLoadsAndNoBitbucketHttpClientBeanExists() {
    contextRunner
        .withPropertyValues("spring.profiles.active=bitbucket-mock")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).hasSingleBean(RepositoryHostAdapter.class);
              assertThat(context.getBean(RepositoryHostAdapter.class))
                  .isInstanceOf(BitbucketMockAdapter.class);
              assertThat(context).hasSingleBean(BitbucketMockScenarioRegistry.class);
              // No network capability under bitbucket-mock — no Bitbucket HTTP-client bean exists.
              assertThat(context).doesNotHaveBean("bitbucketRestClient");
              assertThat(context).doesNotHaveBean(RestClient.class);
            });
  }

  @Test
  void withBothProfilesActiveContextRefreshFailsFast() {
    contextRunner
        .withPropertyValues("spring.profiles.active=bitbucket-mock,bitbucket-real")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure())
                  .hasStackTraceContaining("Bitbucket profile conflict");
            });
  }

  @Test
  void noProfileLeavesTheBitbucketAdapterSliceInactive() {
    contextRunner.run(
        context -> {
          assertThat(context).hasNotFailed();
          assertThat(context).doesNotHaveBean(RepositoryHostAdapter.class);
          assertThat(context).doesNotHaveBean(BitbucketMockAdapter.class);
          assertThat(context).doesNotHaveBean(BitbucketMockScenarioRegistry.class);
        });
  }

  /**
   * Scans only the Bitbucket-adapter slice and explicitly imports {@link BitbucketConfiguration} so
   * the broader {@code infrastructure.config} package is not pulled in.
   */
  @Configuration
  @ComponentScan(basePackageClasses = BitbucketMockAdapter.class)
  @Import(BitbucketConfiguration.class)
  static class BitbucketWiringTestConfiguration {}
}
