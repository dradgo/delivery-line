package org.dradgo.infrastructure.config;

import java.time.Duration;
import java.util.Objects;
import org.dradgo.adapters.integration.ticketsource.jira.JiraOAuthTokenProvider;
import org.dradgo.application.integration.jira.JiraProperties;
import org.dradgo.application.project.ProjectCredentialService;
import org.dradgo.domain.registry.ConnectorRole;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * JIRA integration subsystem wiring (story 3i-1 Task 2), mirroring {@code LinearConfiguration}:
 *
 * <ul>
 *   <li>binds {@link JiraProperties} from the {@code deliveryline.jira.*} namespace,
 *   <li>defines the {@code jiraRestClient} bean under the {@code jira-real} profile only,
 *   <li>asserts that {@code jira-mock} and {@code jira-real} are mutually exclusive (the {@code
 *       RunnerConfiguration}/{@code LinearConfiguration} fail-fast pattern).
 * </ul>
 *
 * <p>The connector-neutral ticket-source {@code kind} fail-fast lives in {@code
 * LinearConfiguration} (generalized in 3i-1 R4 to accept any registered {@code ConnectorKind}) —
 * this class owns only the JIRA-specific profile/bean wiring so a JIRA-only deployment boots
 * cleanly.
 */
@Configuration
@EnableConfigurationProperties(JiraProperties.class)
public class JiraConfiguration {

  static final String MOCK_PROFILE = "jira-mock";
  static final String REAL_PROFILE = "jira-real";

  public JiraConfiguration(Environment environment) {
    assertExclusiveJiraProfile(environment);
  }

  /**
   * Provisions the dedicated {@code jiraRestClient} bean used by {@code JiraRealAdapter}. Only
   * present under {@code jira-real} — a wiring-contract test asserts that no {@code jiraRestClient}
   * bean exists under {@code jira-mock}, satisfying the "no network call under jira-mock"
   * guarantee.
   *
   * <p>JIRA auth is bearer-token based. The Authorization header is assembled inside a request-time
   * interceptor (so token rotation is observed without a context refresh) and a per-request
   * credential override (the project-scoped OAuth access token, story 3c-8) takes precedence over
   * the optional host-env fallback token. The token is <strong>never logged</strong>.
   */
  @Bean(name = "jiraRestClient")
  @Profile(REAL_PROFILE)
  public RestClient jiraRestClient(JiraProperties properties) {
    Objects.requireNonNull(properties, "properties");
    if (properties.baseUrl() == null || properties.baseUrl().isBlank()) {
      throw new IllegalStateException(
          "deliveryline.jira.base-url must be configured (e.g. https://acme.atlassian.net) before"
              + " activating the jira-real profile.");
    }
    SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
    requestFactory.setConnectTimeout((int) properties.timeout().connectMs());
    requestFactory.setReadTimeout(Duration.ofMillis(properties.timeout().readMs()));
    return RestClient.builder()
        .baseUrl(properties.baseUrl())
        .requestFactory(requestFactory)
        .defaultHeader("User-Agent", "DeliveryLine/1.0 (jira-real)")
        .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
        .defaultHeader("Accept", MediaType.APPLICATION_JSON_VALUE)
        .requestInterceptor(
            (request, body, execution) -> {
              // Read the OAuth access token at request-time so project-scoped credential binding
              // can authenticate without requiring a host token at application startup. Never
              // logged.
              Object override =
                  request.getAttributes().get(JiraProperties.CREDENTIAL_OVERRIDE_ATTRIBUTE);
              String token =
                  (override instanceof String s && !s.isBlank()) ? s : properties.apiToken();
              if (token != null && !token.isBlank()) {
                request.getHeaders().setBearerAuth(token);
              }
              return execution.execute(request, body);
            })
        .build();
  }

  @Bean
  @Profile(REAL_PROFILE)
  public JiraOAuthTokenProvider jiraOAuthTokenProvider(
      ObjectProvider<ProjectCredentialService> credentialServiceProvider) {
    return new JiraOAuthTokenProvider(
        RestClient.builder().baseUrl("https://auth.atlassian.com").build(),
        (projectPublicId, plaintextGrant) -> {
          ProjectCredentialService credentialService = credentialServiceProvider.getIfAvailable();
          if (credentialService != null) {
            credentialService.setCredential(
                projectPublicId, ConnectorRole.TICKET_SOURCE, plaintextGrant);
          }
        });
  }

  private static void assertExclusiveJiraProfile(Environment environment) {
    boolean mockActive = isProfileActive(environment, MOCK_PROFILE);
    boolean realActive = isProfileActive(environment, REAL_PROFILE);
    if (mockActive && realActive) {
      throw new IllegalStateException(
          "JIRA profile conflict: "
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
