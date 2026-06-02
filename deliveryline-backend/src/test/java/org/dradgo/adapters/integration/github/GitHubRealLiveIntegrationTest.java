package org.dradgo.adapters.integration.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.dradgo.application.integration.github.GitHubProperties;
import org.dradgo.application.security.DataClassificationService;
import org.dradgo.application.security.RedactionPolicyService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Story 3.14 AC11(e) live GitHub smoke. Disabled by default for PR/local runs; nightly jobs opt in
 * with {@code -Ddeliveryline.github.live-tests=true}, {@code GITHUB_TOKEN}, and {@code
 * -Ddeliveryline.github.live.repo=owner/repo}.
 */
@Tag("gh-real-tests")
@EnabledIfSystemProperty(named = "deliveryline.github.live-tests", matches = "true")
class GitHubRealLiveIntegrationTest {

  @Test
  void liveRepositoryReadUsesRealGitHubWhenExplicitlyEnabled() {
    String token = System.getenv("GITHUB_TOKEN");
    String repoRef = System.getProperty("deliveryline.github.live.repo", "");
    assumeTrue(token != null && !token.isBlank(), "GITHUB_TOKEN is required for gh-real-tests");
    assumeTrue(
        repoRef.matches("^[A-Za-z0-9][A-Za-z0-9-]{0,38}/[A-Za-z0-9._-]+$"),
        "deliveryline.github.live.repo=owner/repo is required for gh-real-tests");

    GitHubProperties properties =
        new GitHubProperties(
            token,
            GitHubProperties.DEFAULT_BASE_URL,
            GitHubProperties.DEFAULT_API_VERSION,
            GitHubProperties.DEFAULT_RATE_LIMIT_WARN_THRESHOLD,
            GitHubProperties.Timeout.defaults());
    SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
    requestFactory.setConnectTimeout(properties.timeout().connectDuration());
    requestFactory.setReadTimeout(properties.timeout().readDuration());
    RestClient client =
        RestClient.builder()
            .baseUrl(properties.baseUrl())
            .requestFactory(requestFactory)
            .defaultHeader("Accept", "application/vnd.github+json")
            .defaultHeader("X-GitHub-Api-Version", properties.apiVersion())
            .defaultHeader("User-Agent", "DeliveryLine/1.0 (github-real-live-test)")
            .requestInterceptor(
                (request, body, execution) -> {
                  request.getHeaders().set("Authorization", "Bearer " + properties.token());
                  return execution.execute(request, body);
                })
            .build();
    GitHubRealAdapter adapter =
        new GitHubRealAdapter(
            client, properties, new RedactionPolicyService(new DataClassificationService()));

    var repository = adapter.getRepositoryByRef(repoRef);

    assertThat(repository).isPresent();
    assertThat(repository.get().repoRef()).isEqualTo(repoRef);
  }
}
