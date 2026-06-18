package org.dradgo.adapters.integration.repohost.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.dradgo.application.integration.github.GitHubProperties;
import org.dradgo.application.integration.repohost.RepositoryHostAdapterException;
import org.dradgo.application.security.DataClassificationService;
import org.dradgo.application.security.RedactionPolicyService;
import org.dradgo.domain.integration.repohost.RepositoryRef;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Story 3.14 logging instrumentation contract: pins the rate-limited WARN, the
 * idempotent-existing-PR WARN, and the invariant that the PAT never appears in any log line for an
 * auth-carrying call.
 */
class GitHubRealAdapterLoggingContractTest {

  private static final String BASE_URL = "https://api.github.com";
  private static final String TOKEN = "ghp_logging_contract_secret_token";

  private static final String REPO_JSON =
      """
      {"full_name":"octo/hello","default_branch":"main","html_url":"https://github.com/octo/hello"}
      """;
  private static final String PR_JSON =
      """
      {"number":7,"head":{"ref":"feature/x"},"state":"open",
       "html_url":"https://github.com/octo/hello/pull/7","created_at":"2026-05-01T10:00:00Z"}
      """;

  private ListAppender<ILoggingEvent> appender;
  private MockRestServiceServer mockServer;
  private GitHubRealAdapter adapter;

  @BeforeEach
  void setUp() {
    appender = attach();
    RestClient.Builder builder =
        RestClient.builder()
            .baseUrl(BASE_URL)
            .requestInterceptor(
                (request, body, execution) -> {
                  request.getHeaders().set("Authorization", "Bearer " + TOKEN);
                  return execution.execute(request, body);
                });
    mockServer = MockRestServiceServer.bindTo(builder).build();
    GitHubProperties properties =
        new GitHubProperties(
            TOKEN, BASE_URL, "2022-11-28", 100, new GitHubProperties.Timeout(5_000L, 30_000L));
    adapter =
        new GitHubRealAdapter(
            builder.build(),
            properties,
            new RedactionPolicyService(new DataClassificationService()));
  }

  @AfterEach
  void tearDown() {
    detach(appender);
  }

  @Test
  void rateLimitedWarnIsLogged() {
    mockServer
        .expect(requestTo(BASE_URL + "/repos/octo/hello"))
        .andRespond(
            withSuccess(REPO_JSON, MediaType.APPLICATION_JSON)
                .header("X-RateLimit-Remaining", "0")
                .header("X-RateLimit-Reset", "1700000000"));

    assertThrows(
        RepositoryHostAdapterException.class,
        () -> adapter.getRepositoryByRef(RepositoryRef.of("octo/hello")));

    assertThat(warnMessages())
        .anyMatch(m -> m.contains("rate_limited") && m.contains("resetAtSeconds=1700000000"));
  }

  @Test
  void idempotentExistingPrWarnIsLogged() {
    mockServer
        .expect(requestTo(BASE_URL + "/repos/octo/hello"))
        .andRespond(withSuccess(REPO_JSON, MediaType.APPLICATION_JSON));
    mockServer
        .expect(requestTo(Matchers.startsWith(BASE_URL + "/repos/octo/hello/pulls")))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess("[" + PR_JSON + "]", MediaType.APPLICATION_JSON));

    adapter.createPullRequest(RepositoryRef.of("octo/hello"), "feature/x", null, "title", "body");

    assertThat(infoAndWarnMessages()).anyMatch(m -> m.contains("resolution=idempotent_existing"));
  }

  @Test
  void tokenNeverAppearsInAnyLogLineForAnAuthCarryingCall() {
    mockServer
        .expect(requestTo(BASE_URL + "/repos/octo/hello"))
        .andRespond(
            withSuccess(REPO_JSON, MediaType.APPLICATION_JSON)
                .header("X-RateLimit-Remaining", "50"));

    adapter.getRepositoryByRef(RepositoryRef.of("octo/hello"));

    assertThat(appender.list).isNotEmpty();
    assertThat(appender.list)
        .allSatisfy(event -> assertThat(event.getFormattedMessage()).doesNotContain(TOKEN));
  }

  private java.util.List<String> warnMessages() {
    return appender.list.stream()
        .filter(e -> e.getLevel() == Level.WARN)
        .map(ILoggingEvent::getFormattedMessage)
        .toList();
  }

  private java.util.List<String> infoAndWarnMessages() {
    return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
  }

  private static ListAppender<ILoggingEvent> attach() {
    Logger logger = (Logger) LoggerFactory.getLogger(GitHubRealAdapter.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    return appender;
  }

  private static void detach(ListAppender<ILoggingEvent> appender) {
    Logger logger = (Logger) LoggerFactory.getLogger(GitHubRealAdapter.class);
    logger.detachAppender(appender);
  }
}
