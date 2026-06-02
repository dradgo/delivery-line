package org.dradgo.adapters.integration.github;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.SocketTimeoutException;
import java.util.Optional;
import org.dradgo.application.integration.github.GitHubAdapterException;
import org.dradgo.application.integration.github.GitHubBranch;
import org.dradgo.application.integration.github.GitHubProperties;
import org.dradgo.application.integration.github.GitHubPullRequest;
import org.dradgo.application.integration.github.GitHubRepository;
import org.dradgo.application.security.DataClassificationService;
import org.dradgo.application.security.RedactionPolicyService;
import org.dradgo.domain.registry.IntegrationFailureCategory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Unit tests for {@link GitHubRealAdapter} with a mocked HTTP layer ({@code
 * MockRestServiceServer.bindTo(RestClient.builder())}, mirroring {@code
 * LinearRealAdapterUnitTest}). Covers story 3.14 AC11(a): every AC7 failure-classification
 * category, rate-limit WARN-threshold + remaining=0 raise (AC5), redaction-on-egress (AC6),
 * idempotent PR creation (AC4), and the request-time {@code Authorization: Bearer} header (AC2).
 *
 * <p>The test client replicates the {@code GitHubConfiguration.gitHubRestClient} recipe (version
 * header + request-time Bearer interceptor) so the Authorization/version headers are asserted on
 * the exact request shape the production bean produces.
 */
class GitHubRealAdapterUnitTest {

  private static final String BASE_URL = "https://api.github.com";
  private static final String TOKEN = "test-token";

  private static final String REPO_JSON =
      """
      {"full_name":"octo/hello","default_branch":"main","html_url":"https://github.com/octo/hello"}
      """;
  private static final String PR_JSON =
      """
      {"number":7,"head":{"ref":"feature/x"},"state":"open",
       "html_url":"https://github.com/octo/hello/pull/7","created_at":"2026-05-01T10:00:00Z"}
      """;

  private MockRestServiceServer mockServer;
  private GitHubRealAdapter adapter;

  @BeforeEach
  void setUp() {
    RestClient.Builder builder =
        RestClient.builder()
            .baseUrl(BASE_URL)
            .defaultHeader("Accept", "application/vnd.github+json")
            .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
            .requestInterceptor(
                (request, body, execution) -> {
                  request.getHeaders().set("Authorization", "Bearer " + TOKEN);
                  return execution.execute(request, body);
                });
    mockServer = MockRestServiceServer.bindTo(builder).build();
    RestClient restClient = builder.build();
    GitHubProperties properties =
        new GitHubProperties(
            TOKEN, BASE_URL, "2022-11-28", 100, new GitHubProperties.Timeout(5_000L, 30_000L));
    adapter =
        new GitHubRealAdapter(
            restClient, properties, new RedactionPolicyService(new DataClassificationService()));
  }

  // -------- Reads (AC3) + request-time Authorization header (AC2) ------------------------------

  @Test
  void getRepositoryHappyPathMapsJsonAndSendsAuthAndVersionHeaders() {
    mockServer
        .expect(requestTo(BASE_URL + "/repos/octo/hello"))
        .andExpect(method(HttpMethod.GET))
        .andExpect(header("Authorization", "Bearer " + TOKEN))
        .andExpect(header("X-GitHub-Api-Version", "2022-11-28"))
        .andExpect(header("Accept", "application/vnd.github+json"))
        .andRespond(withSuccess(REPO_JSON, MediaType.APPLICATION_JSON));

    Optional<GitHubRepository> repo = adapter.getRepositoryByRef("octo/hello");

    assertTrue(repo.isPresent());
    assertEquals("octo/hello", repo.get().repoRef());
    assertEquals("octo/hello", repo.get().fullName());
    assertEquals("main", repo.get().defaultBranch());
    mockServer.verify();
  }

  @Test
  void getRepositoryReturnsEmptyOn404() {
    mockServer
        .expect(requestTo(BASE_URL + "/repos/octo/missing"))
        .andRespond(withStatus(HttpStatus.NOT_FOUND));

    assertTrue(adapter.getRepositoryByRef("octo/missing").isEmpty());
    mockServer.verify();
  }

  @Test
  void getPullRequestHappyPathMapsPrRefFromOwnerRepoAndNumber() {
    mockServer
        .expect(requestTo(BASE_URL + "/repos/octo/hello/pulls/7"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess(PR_JSON, MediaType.APPLICATION_JSON));

    Optional<GitHubPullRequest> pr = adapter.getPullRequestByRef("octo/hello#7");

    assertTrue(pr.isPresent());
    assertEquals("octo/hello#7", pr.get().prRef());
    assertEquals("octo/hello", pr.get().repoRef());
    assertEquals(7, pr.get().number());
    assertEquals("feature/x", pr.get().sourceBranch());
    assertEquals("open", pr.get().state());
    mockServer.verify();
  }

  @Test
  void getBranchHappyPathMapsHeadSha() {
    mockServer
        .expect(requestTo(BASE_URL + "/repos/octo/hello/branches/main"))
        .andRespond(
            withSuccess(
                "{\"name\":\"main\",\"commit\":{\"sha\":\"abc123\"}}", MediaType.APPLICATION_JSON));

    Optional<GitHubBranch> branch = adapter.getBranchByRef("octo/hello", "main");

    assertTrue(branch.isPresent());
    assertEquals("main", branch.get().name());
    assertEquals("abc123", branch.get().headSha());
    mockServer.verify();
  }

  @Test
  void getBranchEncodesSlashContainingBranchAsSinglePathSegment() {
    mockServer
        .expect(requestTo(BASE_URL + "/repos/octo/hello/branches/feature%2Fx"))
        .andRespond(
            withSuccess(
                "{\"name\":\"feature/x\",\"commit\":{\"sha\":\"abc123\"}}",
                MediaType.APPLICATION_JSON));

    Optional<GitHubBranch> branch = adapter.getBranchByRef("octo/hello", "feature/x");

    assertTrue(branch.isPresent());
    assertEquals("feature/x", branch.get().name());
    mockServer.verify();
  }

  @Test
  void repoRefWithUriMetacharactersIsClassifiedWithoutNetwork() {
    assertCategory(
        IntegrationFailureCategory.GITHUB_REPO_NOT_FOUND,
        () -> adapter.getRepositoryByRef("octo/hello?x=1"));
    mockServer.verify();
  }

  @Test
  void getPullRequestReturnsEmptyOn404() {
    mockServer
        .expect(requestTo(BASE_URL + "/repos/octo/hello/pulls/999"))
        .andRespond(withStatus(HttpStatus.NOT_FOUND));

    assertTrue(adapter.getPullRequestByRef("octo/hello#999").isEmpty());
    mockServer.verify();
  }

  // -------- Failure classification ladder (AC7) ------------------------------------------------

  @Test
  void on401MapsToAuthFailed() {
    expectRepoGet(withStatus(HttpStatus.UNAUTHORIZED));
    assertCategory(
        IntegrationFailureCategory.GITHUB_AUTH_FAILED,
        () -> adapter.getRepositoryByRef("octo/hello"));
  }

  @Test
  void on403NonRateLimitMapsToPermissionDenied() {
    expectRepoGet(withStatus(HttpStatus.FORBIDDEN).header("X-RateLimit-Remaining", "50"));
    assertCategory(
        IntegrationFailureCategory.GITHUB_PERMISSION_DENIED,
        () -> adapter.getRepositoryByRef("octo/hello"));
  }

  @Test
  void on403WithRemainingZeroMapsToRateLimited() {
    expectRepoGet(
        withStatus(HttpStatus.FORBIDDEN)
            .header("X-RateLimit-Remaining", "0")
            .header("X-RateLimit-Reset", "1700000000"));
    assertCategory(
        IntegrationFailureCategory.GITHUB_RATE_LIMITED,
        () -> adapter.getRepositoryByRef("octo/hello"));
  }

  @Test
  void on429MapsToRateLimited() {
    expectRepoGet(withStatus(HttpStatus.TOO_MANY_REQUESTS));
    assertCategory(
        IntegrationFailureCategory.GITHUB_RATE_LIMITED,
        () -> adapter.getRepositoryByRef("octo/hello"));
  }

  @Test
  void on404OnPullRequestReadIsEmptyButOnWriteIsClassified() {
    // A 404 on the repo lookup inside createPullRequest is a classified write-context failure.
    mockServer
        .expect(requestTo(BASE_URL + "/repos/octo/missing"))
        .andRespond(withStatus(HttpStatus.NOT_FOUND));
    assertCategory(
        IntegrationFailureCategory.GITHUB_REPO_NOT_FOUND,
        () -> adapter.createPullRequest("octo/missing", "feature/x", "t", "b"));
  }

  @Test
  void on415MapsToApiVersionIncompatible() {
    expectRepoGet(withStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE));
    assertCategory(
        IntegrationFailureCategory.GITHUB_API_VERSION_INCOMPATIBLE,
        () -> adapter.getRepositoryByRef("octo/hello"));
  }

  @Test
  void on422OnCreateMapsToBranchProtected() {
    mockServer
        .expect(requestTo(BASE_URL + "/repos/octo/hello"))
        .andRespond(withSuccess(REPO_JSON, MediaType.APPLICATION_JSON));
    mockServer
        .expect(requestTo(startsWith(BASE_URL + "/repos/octo/hello/pulls")))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
    mockServer
        .expect(requestTo(BASE_URL + "/repos/octo/hello/pulls"))
        .andExpect(method(HttpMethod.POST))
        .andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY));

    assertCategory(
        IntegrationFailureCategory.GITHUB_BRANCH_PROTECTED,
        () -> adapter.createPullRequest("octo/hello", "feature/x", "title", "body"));
  }

  @Test
  void on5xxMapsToNetworkFailure() {
    expectRepoGet(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
    assertCategory(
        IntegrationFailureCategory.GITHUB_NETWORK_FAILURE,
        () -> adapter.getRepositoryByRef("octo/hello"));
  }

  @Test
  void onNetworkIoMapsToNetworkFailure() {
    expectRepoGet(withException(new SocketTimeoutException("read timed out")));
    assertCategory(
        IntegrationFailureCategory.GITHUB_NETWORK_FAILURE,
        () -> adapter.getRepositoryByRef("octo/hello"));
  }

  // -------- Rate-limit awareness (AC5) ---------------------------------------------------------

  @Test
  void rateLimitBelowThresholdWarnsButStillReturns() {
    mockServer
        .expect(requestTo(BASE_URL + "/repos/octo/hello"))
        .andRespond(
            withSuccess(REPO_JSON, MediaType.APPLICATION_JSON)
                .header("X-RateLimit-Remaining", "50")
                .header("X-RateLimit-Reset", "1700000000"));

    Optional<GitHubRepository> repo = adapter.getRepositoryByRef("octo/hello");

    assertTrue(repo.isPresent(), "below-threshold remaining is WARN-only — the call still returns");
    mockServer.verify();
  }

  @Test
  void rateLimitRemainingZeroOnSuccessRaisesRateLimited() {
    mockServer
        .expect(requestTo(BASE_URL + "/repos/octo/hello"))
        .andRespond(
            withSuccess(REPO_JSON, MediaType.APPLICATION_JSON)
                .header("X-RateLimit-Remaining", "0")
                .header("X-RateLimit-Reset", "1700000000"));

    GitHubAdapterException error =
        assertThrows(GitHubAdapterException.class, () -> adapter.getRepositoryByRef("octo/hello"));
    assertEquals(IntegrationFailureCategory.GITHUB_RATE_LIMITED, error.failureCategory());
    assertEquals("1700000000", error.details().get("resetAtSeconds"));
    mockServer.verify();
  }

  // -------- Redaction-on-egress (AC6) ----------------------------------------------------------

  @Test
  void commentOnPullRequestRedactsSecretInOutboundBody() {
    String secret = "ghp_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    mockServer
        .expect(requestTo(BASE_URL + "/repos/octo/hello/issues/7/comments"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(content().string(not(containsString(secret))))
        .andExpect(content().string(containsString("[REDACTED_GITHUB_TOKEN]")))
        .andRespond(withSuccess("{\"id\":123}", MediaType.APPLICATION_JSON));

    adapter.commentOnPullRequest("octo/hello#7", "Run summary with token " + secret);

    mockServer.verify();
  }

  @Test
  void createPullRequestRedactsSecretInTitleAndBody() {
    String secret = "ghp_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    mockServer
        .expect(requestTo(BASE_URL + "/repos/octo/hello"))
        .andRespond(withSuccess(REPO_JSON, MediaType.APPLICATION_JSON));
    mockServer
        .expect(requestTo(containsString("head=octo%3Afeature%2Fx")))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
    mockServer
        .expect(requestTo(BASE_URL + "/repos/octo/hello/pulls"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(content().string(not(containsString(secret))))
        .andExpect(content().string(containsString("[REDACTED_GITHUB_TOKEN]")))
        .andRespond(withSuccess(PR_JSON, MediaType.APPLICATION_JSON));

    adapter.createPullRequest("octo/hello", "feature/x", "title " + secret, "body " + secret);

    mockServer.verify();
  }

  @Test
  void updatePullRequestRedactsSecretInBody() {
    String secret = "ghp_cccccccccccccccccccccccccccccccccccc";
    mockServer
        .expect(requestTo(BASE_URL + "/repos/octo/hello/pulls/7"))
        .andExpect(method(HttpMethod.PATCH))
        .andExpect(content().string(not(containsString(secret))))
        .andExpect(content().string(containsString("[REDACTED_GITHUB_TOKEN]")))
        .andRespond(withSuccess(PR_JSON, MediaType.APPLICATION_JSON));

    adapter.updatePullRequest("octo/hello#7", "new body " + secret);

    mockServer.verify();
  }

  // -------- Idempotent PR creation (AC4) -------------------------------------------------------

  @Test
  void createPullRequestReturnsExistingOpenPrWithoutPosting() {
    mockServer
        .expect(requestTo(BASE_URL + "/repos/octo/hello"))
        .andRespond(withSuccess(REPO_JSON, MediaType.APPLICATION_JSON));
    mockServer
        .expect(requestTo(containsString("head=octo%3Afeature%2Fx")))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess("[" + PR_JSON + "]", MediaType.APPLICATION_JSON));
    // No POST expectation — an unexpected POST would fail mockServer.verify().

    GitHubPullRequest pr = adapter.createPullRequest("octo/hello", "feature/x", "title", "body");

    assertEquals("octo/hello#7", pr.prRef());
    assertEquals(7, pr.number());
    mockServer.verify();
  }

  @Test
  void createPullRequestPostsDraftWhenNoExistingPr() {
    mockServer
        .expect(requestTo(BASE_URL + "/repos/octo/hello"))
        .andRespond(withSuccess(REPO_JSON, MediaType.APPLICATION_JSON));
    mockServer
        .expect(requestTo(containsString("head=octo%3Afeature%2Fx")))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
    mockServer
        .expect(requestTo(BASE_URL + "/repos/octo/hello/pulls"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(content().string(containsString("\"draft\":true")))
        .andExpect(content().string(containsString("\"base\":\"main\"")))
        .andExpect(content().string(containsString("\"head\":\"feature/x\"")))
        .andRespond(withSuccess(PR_JSON, MediaType.APPLICATION_JSON));

    GitHubPullRequest pr = adapter.createPullRequest("octo/hello", "feature/x", "title", "body");

    assertEquals("octo/hello#7", pr.prRef());
    mockServer.verify();
  }

  // -------- Update + comment happy paths -------------------------------------------------------

  @Test
  void updatePullRequestPatchesAndMaps() {
    mockServer
        .expect(requestTo(BASE_URL + "/repos/octo/hello/pulls/7"))
        .andExpect(method(HttpMethod.PATCH))
        .andRespond(withSuccess(PR_JSON, MediaType.APPLICATION_JSON));

    GitHubPullRequest pr = adapter.updatePullRequest("octo/hello#7", "new body");

    assertEquals("octo/hello#7", pr.prRef());
    mockServer.verify();
  }

  @Test
  void oversizedPullRequestNumberThrowsClassifiedExceptionWithoutNetwork() {
    assertCategory(
        IntegrationFailureCategory.GITHUB_PR_NOT_FOUND,
        () -> adapter.updatePullRequest("octo/hello#999999999999999999999999999999", "body"));
    mockServer.verify();
  }

  @Test
  void unprocessableOnUpdateDoesNotClassifyAsBranchProtected() {
    mockServer
        .expect(requestTo(BASE_URL + "/repos/octo/hello/pulls/7"))
        .andExpect(method(HttpMethod.PATCH))
        .andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY));

    assertCategory(
        IntegrationFailureCategory.GITHUB_NETWORK_FAILURE,
        () -> adapter.updatePullRequest("octo/hello#7", "body"));
  }

  @Test
  void malformedRepoRefThrowsClassifiedWithoutNetwork() {
    assertCategory(
        IntegrationFailureCategory.GITHUB_REPO_NOT_FOUND,
        () -> adapter.getRepositoryByRef("not-a-valid-ref"));
    // No expectation registered + no call made — implicit "no network" assertion.
    mockServer.verify();
  }

  private void expectRepoGet(org.springframework.test.web.client.ResponseCreator r) {
    mockServer
        .expect(requestTo(BASE_URL + "/repos/octo/hello"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(r);
  }

  private void assertCategory(IntegrationFailureCategory expected, Runnable call) {
    GitHubAdapterException error = assertThrows(GitHubAdapterException.class, call::run);
    assertEquals(expected, error.failureCategory());
    mockServer.verify();
  }
}
