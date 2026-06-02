package org.dradgo.infrastructure.config;

import java.util.Objects;
import org.dradgo.application.integration.github.GitHubProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * GitHub integration subsystem wiring (story 3.13 Task 4 + story 3.14 Task 1). Mirrors {@code
 * LinearConfiguration}'s mock/real exclusivity guard and real-RestClient bean.
 *
 * <ul>
 *   <li>binds {@link GitHubProperties} from the {@code deliveryline.github.*} namespace
 *       (profile-neutral with safe defaults — see {@link GitHubProperties} Decision D6),
 *   <li>defines the {@code gitHubRestClient} bean under the {@code github-real} profile only,
 *   <li>asserts that {@code github-mock} and {@code github-real} are mutually exclusive.
 * </ul>
 *
 * <p>The exclusivity guard reads only the active profile names so it has worked since story 3.13
 * even before the real adapter existed. The {@code gitHubRestClient} bean is present only under
 * {@code github-real}; {@code GitHubProfileWiringContractTest} asserts no such bean exists under
 * {@code github-mock} (story 3.13 AC6 — no GitHub network call under the mock).
 */
@Configuration
@EnableConfigurationProperties(GitHubProperties.class)
public class GitHubConfiguration {

  static final String MOCK_PROFILE = "github-mock";
  static final String REAL_PROFILE = "github-real";

  public GitHubConfiguration(Environment environment) {
    assertExclusiveGitHubProfile(environment);
  }

  /**
   * Provisions the dedicated {@code gitHubRestClient} bean used by {@code GitHubRealAdapter} and
   * the doctor {@code github-real} auth probe (story 3.14 AC2/AC9). Only present under {@code
   * github-real}. Mirrors {@code LinearConfiguration.linearRestClient}: a {@code
   * SimpleClientHttpRequestFactory} carries the connect/read timeouts, the version-pinned default
   * headers are set once, and a request interceptor reads the PAT at request time. A blank token is
   * not startup-fatal; the doctor probe reports {@code DOCTOR_GITHUB_TOKEN_MISSING} under {@code
   * github-real} before attempting a network call.
   */
  @Bean(name = "gitHubRestClient")
  @Profile(REAL_PROFILE)
  public RestClient gitHubRestClient(GitHubProperties properties) {
    Objects.requireNonNull(properties, "properties");
    SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
    requestFactory.setConnectTimeout(properties.timeout().connectDuration());
    requestFactory.setReadTimeout(properties.timeout().readDuration());
    return RestClient.builder()
        .baseUrl(properties.baseUrl())
        .requestFactory(requestFactory)
        .defaultHeader("Accept", "application/vnd.github+json")
        .defaultHeader("X-GitHub-Api-Version", properties.apiVersion())
        .defaultHeader("User-Agent", "DeliveryLine/1.0 (github-real)")
        .requestInterceptor(
            (request, body, execution) -> {
              // Read the token at request time so PAT rotation is observed without a context
              // refresh. Never logged, never embedded in a URL (AC2).
              String token = properties.token();
              if (token != null && !token.isBlank()) {
                request.getHeaders().set("Authorization", "Bearer " + token);
              }
              return execution.execute(request, body);
            })
        .build();
  }

  private static void assertExclusiveGitHubProfile(Environment environment) {
    boolean mockActive = isProfileActive(environment, MOCK_PROFILE);
    boolean realActive = isProfileActive(environment, REAL_PROFILE);
    if (mockActive && realActive) {
      throw new IllegalStateException(
          "GitHub profile conflict: "
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
