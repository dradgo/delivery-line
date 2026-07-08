package org.dradgo.adapters.integration.ticketsource.jira;

import static org.assertj.core.api.Assertions.assertThat;

import org.dradgo.application.integration.jira.JiraProperties;
import org.dradgo.application.integration.ticketsource.TicketSourceAdapter;
import org.dradgo.infrastructure.config.JiraConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.web.client.RestClient;

/**
 * Story 3i-1 Task 2 — jira-mock vs jira-real profile wiring: the mock loads with no {@code
 * jiraRestClient} bean (no network capability), the real adapter loads with the {@code
 * jiraRestClient} bean, both-active fails fast, and neither profile leaves the slice inactive.
 * Mirrors {@code IntegrationProfileWiringContractTest}.
 */
class JiraProfileWiringContractTest {

  private static final String[] JIRA_PROPERTIES =
      new String[] {
        "deliveryline.jira.api-token=test-token",
        "deliveryline.jira.email=pilot@acme.example",
        "deliveryline.jira.base-url=https://acme.atlassian.net",
        "deliveryline.jira.poll-batch-size=50",
        "deliveryline.jira.timeout.connect-ms=5000",
        "deliveryline.jira.timeout.read-ms=30000"
      };

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withUserConfiguration(JiraWiringTestConfiguration.class)
          .withPropertyValues(JIRA_PROPERTIES);

  @Test
  void underJiraMockProfileMockAdapterLoadsAndJiraRestClientIsAbsent() {
    contextRunner
        .withPropertyValues("spring.profiles.active=jira-mock")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).hasSingleBean(TicketSourceAdapter.class);
              assertThat(context.getBean(TicketSourceAdapter.class))
                  .isInstanceOf(JiraMockAdapter.class);
              assertThat(context).doesNotHaveBean("jiraRestClient");
            });
  }

  @Test
  void underJiraRealProfileRealAdapterLoadsWithoutAHostTokenAndRestClientIsPresent() {
    contextRunner
        .withPropertyValues("spring.profiles.active=jira-real")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).hasSingleBean(TicketSourceAdapter.class);
              assertThat(context.getBean(TicketSourceAdapter.class))
                  .isInstanceOf(JiraRealAdapter.class);
              assertThat(context).hasBean("jiraRestClient");
              assertThat(context.getBean("jiraRestClient")).isInstanceOf(RestClient.class);
              assertThat(context).doesNotHaveBean(JiraMockAdapter.class);
            });
  }

  @Test
  void withBothProfilesActiveContextRefreshFailsFast() {
    contextRunner
        .withPropertyValues("spring.profiles.active=jira-mock,jira-real")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure())
                  .hasStackTraceContaining("JIRA profile conflict");
            });
  }

  @Test
  void noProfileLeavesTheJiraAdapterSliceInactive() {
    contextRunner.run(
        context -> {
          assertThat(context).hasNotFailed();
          assertThat(context).doesNotHaveBean(TicketSourceAdapter.class);
          assertThat(context).doesNotHaveBean(JiraMockAdapter.class);
        });
  }

  @Configuration
  @ComponentScan(basePackageClasses = JiraMockAdapter.class)
  @Import(JiraConfiguration.class)
  @org.springframework.boot.context.properties.EnableConfigurationProperties(JiraProperties.class)
  static class JiraWiringTestConfiguration {}
}
