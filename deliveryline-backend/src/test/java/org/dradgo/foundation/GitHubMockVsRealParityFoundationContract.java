package org.dradgo.foundation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.dradgo.adapters.integration.repohost.github.GitHubMockAdapter;
import org.dradgo.adapters.integration.repohost.github.GitHubMockScenarioRegistry;
import org.dradgo.adapters.integration.repohost.github.GitHubRealAdapter;
import org.dradgo.application.integration.github.GitHubProperties;
import org.dradgo.application.integration.repohost.RepositoryHostAdapter;
import org.dradgo.application.integration.repohost.RepositoryHostAdapterException;
import org.dradgo.application.security.DataClassificationService;
import org.dradgo.application.security.RedactionPolicyService;
import org.dradgo.domain.integration.repohost.PullRequest;
import org.dradgo.domain.integration.repohost.PullRequestRef;
import org.dradgo.domain.integration.repohost.Repository;
import org.dradgo.domain.integration.repohost.RepositoryRef;
import org.dradgo.domain.registry.IntegrationFailureCategory;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Foundation contract #11 (story 3.14 AC8, reciprocal of story 3.13 AC10) — mock-vs-real GitHub
 * adapter parity. The {@code GitHubMockAdapter} and {@code GitHubRealAdapter} implement the same
 * {@link RepositoryHostAdapter} port (extracted from the GitHub-shaped port by story 3.33); this
 * contract drives equivalent scenarios against both (the real one against {@code
 * MockRestServiceServer}-stubbed HTTP) and asserts <strong>domain-result equivalence</strong>: the
 * DTO content differs (mock fixtures vs stubbed octo/hello), but the typed shape and {@link
 * IntegrationFailureCategory} outcomes match.
 *
 * <p>Read 404s are intentionally NOT compared head-to-head: the mock's adversarial refs throw on
 * reads by design (3.13 D2), while the real adapter's read-404 contract is {@link
 * java.util.Optional#empty()}. Parity is therefore asserted on operations/refs that produce a
 * matching outcome in both: the happy reads and the classified-failure write/validation paths.
 *
 * <p>The {@code live-repo} variant of this parity check (real HTTP against a documented test
 * repository) is gated behind the {@code gh-real-tests} profile and runs nightly per story 3.35 AC3
 * — it is NOT part of this foundation-gate contract.
 */
@Tag("foundation-gate")
class GitHubMockVsRealParityFoundationContract {

  private static final String BASE_URL = "https://api.github.com";
  private static final String REPO_JSON =
      """
      {"full_name":"octo/hello","default_branch":"main","html_url":"https://github.com/octo/hello"}
      """;
  private static final String PR_JSON =
      """
      {"number":7,"head":{"ref":"feature/x"},"state":"open",
       "html_url":"https://github.com/octo/hello/pull/7","created_at":"2026-05-01T10:00:00Z"}
      """;

  private final RepositoryHostAdapter mock =
      new GitHubMockAdapter(new GitHubMockScenarioRegistry());

  @Test
  void bothAdaptersImplementTheSamePortMethodSet() {
    // The compiler already guarantees both implement RepositoryHostAdapter; this assertion makes
    // the parity premise explicit and fails loudly if either type stops implementing the port.
    assertTrue(
        RepositoryHostAdapter.class.isAssignableFrom(GitHubMockAdapter.class),
        FoundationGateAssertions.tagged(
            "3.14", "GitHubMockAdapter must implement RepositoryHostAdapter"));
    assertTrue(
        RepositoryHostAdapter.class.isAssignableFrom(GitHubRealAdapter.class),
        FoundationGateAssertions.tagged(
            "3.14", "GitHubRealAdapter must implement RepositoryHostAdapter"));
  }

  @Test
  void happyRepositoryReadReturnsPresentTypedResultInBoth() {
    var present =
        mock.getRepositoryByRef(RepositoryRef.of(GitHubMockScenarioRegistry.REPO_FEATURE_LOW_RISK));
    assertTrue(present.isPresent(), parity("mock repo read present"));
    assertInstanceOf(Repository.class, present.get());

    RealHarness harness = realHarness();
    harness.server.expect(requestTo(BASE_URL + "/repos/octo/hello")).andRespond(repoOk());
    var realPresent = harness.adapter.getRepositoryByRef(RepositoryRef.of("octo/hello"));
    assertTrue(realPresent.isPresent(), parity("real repo read present"));
    assertInstanceOf(Repository.class, realPresent.get());
    harness.server.verify();
  }

  @Test
  void happyPullRequestReadReturnsPresentTypedResultInBoth() {
    var present =
        mock.getPullRequestByRef(PullRequestRef.of(GitHubMockScenarioRegistry.PR_FEATURE_LOW_RISK));
    assertTrue(present.isPresent(), parity("mock PR read present"));
    assertInstanceOf(PullRequest.class, present.get());

    RealHarness harness = realHarness();
    harness.server.expect(requestTo(BASE_URL + "/repos/octo/hello/pulls/7")).andRespond(prOk());
    var realPresent = harness.adapter.getPullRequestByRef(PullRequestRef.of("octo/hello#7"));
    assertTrue(realPresent.isPresent(), parity("real PR read present"));
    assertInstanceOf(PullRequest.class, realPresent.get());
    harness.server.verify();
  }

  @Test
  void permissionDeniedSurfacesSameCategoryInBoth() {
    IntegrationFailureCategory mockCategory =
        assertThrows(
                RepositoryHostAdapterException.class,
                () ->
                    mock.getPullRequestByRef(
                        PullRequestRef.of(GitHubMockScenarioRegistry.REF_PR_PERMISSION_DENIED)))
            .failureCategory();

    RealHarness harness = realHarness();
    harness
        .server
        .expect(requestTo(BASE_URL + "/repos/octo/hello/pulls/7"))
        .andRespond(withStatus(HttpStatus.FORBIDDEN).header("X-RateLimit-Remaining", "50"));
    IntegrationFailureCategory realCategory =
        assertThrows(
                RepositoryHostAdapterException.class,
                () -> harness.adapter.getPullRequestByRef(PullRequestRef.of("octo/hello#7")))
            .failureCategory();

    assertParityCategory(
        IntegrationFailureCategory.GITHUB_PERMISSION_DENIED, mockCategory, realCategory);
    harness.server.verify();
  }

  @Test
  void rateLimitedSurfacesSameCategoryInBoth() {
    IntegrationFailureCategory mockCategory =
        assertThrows(
                RepositoryHostAdapterException.class,
                () ->
                    mock.commentOnPullRequest(
                        PullRequestRef.of(GitHubMockScenarioRegistry.REF_PR_RATE_LIMITED),
                        "summary"))
            .failureCategory();

    RealHarness harness = realHarness();
    harness
        .server
        .expect(requestTo(BASE_URL + "/repos/octo/hello/issues/7/comments"))
        .andExpect(method(HttpMethod.POST))
        .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
    IntegrationFailureCategory realCategory =
        assertThrows(
                RepositoryHostAdapterException.class,
                () ->
                    harness.adapter.commentOnPullRequest(
                        PullRequestRef.of("octo/hello#7"), "summary"))
            .failureCategory();

    assertParityCategory(
        IntegrationFailureCategory.GITHUB_RATE_LIMITED, mockCategory, realCategory);
    harness.server.verify();
  }

  @Test
  void branchProtectedSurfacesSameCategoryInBoth() {
    IntegrationFailureCategory mockCategory =
        assertThrows(
                RepositoryHostAdapterException.class,
                () ->
                    mock.createPullRequest(
                        RepositoryRef.of(GitHubMockScenarioRegistry.REPO_FEATURE_LOW_RISK),
                        GitHubMockScenarioRegistry.REF_BRANCH_PROTECTED,
                        null,
                        "title",
                        "body"))
            .failureCategory();

    RealHarness harness = realHarness();
    harness.server.expect(requestTo(BASE_URL + "/repos/octo/hello")).andRespond(repoOk());
    harness
        .server
        .expect(requestTo(startsWithPulls()))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
    harness
        .server
        .expect(requestTo(BASE_URL + "/repos/octo/hello/pulls"))
        .andExpect(method(HttpMethod.POST))
        .andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY));
    IntegrationFailureCategory realCategory =
        assertThrows(
                RepositoryHostAdapterException.class,
                () ->
                    harness.adapter.createPullRequest(
                        RepositoryRef.of("octo/hello"), "feature/x", null, "title", "body"))
            .failureCategory();

    assertParityCategory(
        IntegrationFailureCategory.GITHUB_BRANCH_PROTECTED, mockCategory, realCategory);
    harness.server.verify();
  }

  @Test
  void pullRequestNotFoundSurfacesSameCategoryInBoth() {
    IntegrationFailureCategory mockCategory =
        assertThrows(
                RepositoryHostAdapterException.class,
                () -> mock.updatePullRequest(PullRequestRef.of("PR-NEVER-SEEDED"), "body"))
            .failureCategory();

    RealHarness harness = realHarness();
    harness
        .server
        .expect(requestTo(BASE_URL + "/repos/octo/hello/pulls/999"))
        .andExpect(method(HttpMethod.PATCH))
        .andRespond(withStatus(HttpStatus.NOT_FOUND));
    IntegrationFailureCategory realCategory =
        assertThrows(
                RepositoryHostAdapterException.class,
                () ->
                    harness.adapter.updatePullRequest(PullRequestRef.of("octo/hello#999"), "body"))
            .failureCategory();

    assertParityCategory(
        IntegrationFailureCategory.GITHUB_PR_NOT_FOUND, mockCategory, realCategory);
    harness.server.verify();
  }

  // -------- helpers ----------------------------------------------------------------------------

  private void assertParityCategory(
      IntegrationFailureCategory expected,
      IntegrationFailureCategory mockCategory,
      IntegrationFailureCategory realCategory) {
    assertEquals(expected, mockCategory, parity("mock category"));
    assertEquals(
        mockCategory,
        realCategory,
        parity("mock and real must classify the same scenario identically"));
  }

  private static String parity(String detail) {
    return FoundationGateAssertions.tagged("3.14", "GitHub mock-vs-real parity: " + detail);
  }

  private RealHarness realHarness() {
    RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    GitHubProperties properties =
        new GitHubProperties(
            "ghp_parity_token",
            BASE_URL,
            "2022-11-28",
            100,
            new GitHubProperties.Timeout(5_000L, 30_000L));
    GitHubRealAdapter adapter =
        new GitHubRealAdapter(
            builder.build(),
            properties,
            new RedactionPolicyService(new DataClassificationService()));
    return new RealHarness(server, adapter);
  }

  private static org.springframework.test.web.client.response.DefaultResponseCreator repoOk() {
    return withSuccess(REPO_JSON, MediaType.APPLICATION_JSON);
  }

  private static org.springframework.test.web.client.response.DefaultResponseCreator prOk() {
    return withSuccess(PR_JSON, MediaType.APPLICATION_JSON);
  }

  private static org.hamcrest.Matcher<String> startsWithPulls() {
    return org.hamcrest.Matchers.startsWith(BASE_URL + "/repos/octo/hello/pulls");
  }

  /** Plain holder (public fields) so call-sites read {@code harness.server} / {@code .adapter}. */
  private static final class RealHarness {
    private final MockRestServiceServer server;
    private final GitHubRealAdapter adapter;

    private RealHarness(MockRestServiceServer server, GitHubRealAdapter adapter) {
      this.server = server;
      this.adapter = adapter;
    }
  }
}
