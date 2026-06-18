package org.dradgo.adapters.integration.ticketsource.linear;

import static org.assertj.core.api.Assertions.assertThat;

import org.dradgo.application.integration.linear.LinearProperties;
import org.dradgo.application.integration.ticketsource.TicketSourceAdapter;
import org.dradgo.infrastructure.config.LinearConfiguration;
import org.dradgo.infrastructure.config.LinearPollingHost;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.web.client.RestClient;

/**
 * Pins story 1.14 AC10 ("no network call under linear-mock") and the linear-mock vs linear-real
 * fail-fast invariant from Task 6. Uses {@link ApplicationContextRunner} so the slice is isolated
 * from the persistence stack and full Spring Boot auto-config — only the linear adapter slice +
 * {@link LinearConfiguration} are scanned. Story 3.32 — beans are typed by the vendor-neutral
 * {@link TicketSourceAdapter} port.
 */
class IntegrationProfileWiringContractTest {

  private static final String[] LINEAR_PROPERTIES =
      new String[] {
        "deliveryline.linear.api-token=test-token",
        "deliveryline.linear.base-url=https://api.linear.app/graphql",
        "deliveryline.linear.poll-interval-ms=60000",
        "deliveryline.linear.poll-batch-size=50",
        "deliveryline.linear.stale-threshold-multiplier=2.0",
        "deliveryline.linear.timeout.connect-ms=5000",
        "deliveryline.linear.timeout.read-ms=30000",
        "deliveryline.linear.polling.enabled=false"
      };

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withUserConfiguration(LinearWiringTestConfiguration.class)
          .withPropertyValues(LINEAR_PROPERTIES);

  @Test
  void underLinearMockProfileMockAdapterLoadsAndLinearRestClientIsAbsent() {
    contextRunner
        .withPropertyValues("spring.profiles.active=linear-mock")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).hasSingleBean(TicketSourceAdapter.class);
              assertThat(context.getBean(TicketSourceAdapter.class))
                  .isInstanceOf(LinearMockAdapter.class);
              // AC10: no network capability under linear-mock — the dedicated linearRestClient bean
              // must not exist.
              assertThat(context).doesNotHaveBean("linearRestClient");
              // Sanity: the polling host is real-profile-only.
              assertThat(context).doesNotHaveBean("linearPollingHost");
            });
  }

  @Test
  void underLinearRealProfileRealAdapterLoadsAndRestClientIsPresent() {
    contextRunner
        .withPropertyValues("spring.profiles.active=linear-real")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).hasSingleBean(TicketSourceAdapter.class);
              assertThat(context.getBean(TicketSourceAdapter.class))
                  .isInstanceOf(LinearRealAdapter.class);
              assertThat(context).hasBean("linearRestClient");
              assertThat(context.getBean("linearRestClient")).isInstanceOf(RestClient.class);
              assertThat(context).doesNotHaveBean(LinearMockAdapter.class);
            });
  }

  @Test
  void withBothProfilesActiveContextRefreshFailsFast() {
    contextRunner
        .withPropertyValues("spring.profiles.active=linear-mock,linear-real")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure())
                  .hasStackTraceContaining("Linear profile conflict");
            });
  }

  @Test
  void noProfileLeavesTheLinearAdapterSliceInactive() {
    // AC2: mock activation is explicit via linear-mock. A runtime that forgets to opt in to
    // either linear profile must not silently fall back to fixture-backed behavior.
    contextRunner.run(
        context -> {
          assertThat(context).hasNotFailed();
          assertThat(context).doesNotHaveBean(TicketSourceAdapter.class);
          assertThat(context).doesNotHaveBean(LinearMockAdapter.class);
          assertThat(context).doesNotHaveBean(LinearMockScenarioRegistry.class);
        });
  }

  /**
   * Scans only the Linear-adapter slice and explicitly imports {@link LinearConfiguration} and
   * {@link LinearPollingHost} so the broader {@code infrastructure.config} package is not pulled in
   * (which would drag {@code RunnerConfiguration} → {@code RunnerBroker} into the slice).
   */
  @Configuration
  @ComponentScan(basePackageClasses = LinearMockAdapter.class)
  @Import({LinearConfiguration.class, LinearPollingHost.class})
  @org.springframework.boot.context.properties.EnableConfigurationProperties(LinearProperties.class)
  static class LinearWiringTestConfiguration {}
}
