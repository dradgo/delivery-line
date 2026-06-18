package org.dradgo.infrastructure.config;

import java.time.Duration;
import java.util.Objects;
import org.dradgo.application.integration.linear.LinearAutoIngestProperties;
import org.dradgo.application.integration.linear.LinearProperties;
import org.dradgo.application.integration.ticketsource.TicketSourceProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Linear integration subsystem wiring (story 1.14 Task 4 + Task 6):
 *
 * <ul>
 *   <li>binds {@link LinearProperties} from the {@code deliveryline.linear.*} namespace,
 *   <li>defines the {@code linearRestClient} bean under the {@code linear-real} profile only,
 *   <li>asserts that {@code linear-mock} and {@code linear-real} are mutually exclusive (mirrors
 *       the {@code RunnerConfiguration} fail-fast pattern from story 1.13).
 *   <li>binds {@link TicketSourceProperties} ({@code deliveryline.integration.ticket-source.kind})
 *       and fail-fasts at boot when the configured kind has no implementation on the classpath
 *       (story 3.32 AC5 / OQ-2 — the {@code kind} key is the documented selector; the load-bearing
 *       bean gating remains the mutually-exclusive {@code linear-mock}/{@code linear-real}
 *       profiles).
 * </ul>
 *
 * <p>The Linear polling host bean lives in {@link LinearPollingHost} (separate class so the
 * {@code @Scheduled} annotation and the {@link ConditionalOnProperty} gate are co-located with the
 * scheduling concern, not the wiring concern).
 */
@Configuration
@EnableConfigurationProperties({
  LinearProperties.class,
  LinearAutoIngestProperties.class,
  TicketSourceProperties.class
})
public class LinearConfiguration {

  static final String MOCK_PROFILE = "linear-mock";
  static final String REAL_PROFILE = "linear-real";

  public LinearConfiguration(
      Environment environment, TicketSourceProperties ticketSourceProperties) {
    assertExclusiveLinearProfile(environment);
    assertSupportedTicketSourceKind(ticketSourceProperties);
  }

  /**
   * Story 3.32 AC5 / OQ-2 — only the {@code linear} kind has an implementation on the classpath
   * today. A configured {@code kind=jira|github-issues|gitlab-issues} would silently leave the port
   * unbacked; fail fast at boot with a clear message instead. The {@code kind} defaults to {@code
   * linear} when unset, so this never trips a context that does not configure the selector.
   */
  private static void assertSupportedTicketSourceKind(TicketSourceProperties properties) {
    if (!properties.isLinear()) {
      throw new IllegalStateException(
          "deliveryline.integration.ticket-source.kind="
              + properties.kind()
              + " has no implementation on the classpath. The only supported kind today is '"
              + TicketSourceProperties.KIND_LINEAR
              + "'. Add a TicketSourceAdapter implementation + profile entry for the new kind, or"
              + " correct the configured kind.");
    }
  }

  /**
   * Provisions the dedicated {@code linearRestClient} bean used by {@code LinearRealAdapter}. Only
   * present under {@code linear-real} — the wiring-contract test (Task 7) asserts that no {@code
   * linearRestClient} bean exists under {@code linear-mock}, which satisfies AC10's "no network
   * call under linear-mock" guarantee.
   */
  @Bean(name = "linearRestClient")
  @Profile(REAL_PROFILE)
  public RestClient linearRestClient(LinearProperties properties) {
    Objects.requireNonNull(properties, "properties");
    if (properties.apiToken() == null || properties.apiToken().isBlank()) {
      throw new IllegalStateException(
          "deliveryline.linear.api-token must be configured (typically via LINEAR_API_TOKEN env var) "
              + "before activating the linear-real profile.");
    }
    SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
    requestFactory.setConnectTimeout((int) properties.timeout().connectMs());
    requestFactory.setReadTimeout(Duration.ofMillis(properties.timeout().readMs()));
    return RestClient.builder()
        .baseUrl(properties.baseUrl())
        .requestFactory(requestFactory)
        .defaultHeader("User-Agent", "DeliveryLine/1.0 (linear-real)")
        .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
        .defaultHeader("Accept", MediaType.APPLICATION_JSON_VALUE)
        .requestInterceptor(
            (request, body, execution) -> {
              // Read the token at request-time so rotation is observed without context refresh.
              String token = properties.apiToken();
              if (token != null && !token.isBlank()) {
                request.getHeaders().set("Authorization", token);
              }
              return execution.execute(request, body);
            })
        .build();
  }

  private static void assertExclusiveLinearProfile(Environment environment) {
    boolean mockActive = isProfileActive(environment, MOCK_PROFILE);
    boolean realActive = isProfileActive(environment, REAL_PROFILE);
    if (mockActive && realActive) {
      throw new IllegalStateException(
          "Linear profile conflict: "
              + MOCK_PROFILE
              + " and "
              + REAL_PROFILE
              + " are mutually exclusive but both are active. Activate exactly one.");
    }
  }

  private static boolean isProfileActive(Environment environment, String profile) {
    for (String active : environment.getActiveProfiles()) {
      if (active.equals(profile)) {
        return true;
      }
    }
    return false;
  }
}
