package org.dradgo.adapters.integration.repohost.bitbucket;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.SocketTimeoutException;
import java.util.Optional;
import org.dradgo.application.integration.ConnectivityResult;
import org.dradgo.application.integration.bitbucket.BitbucketProperties;
import org.dradgo.application.integration.repohost.RepositoryHostAdapterException;
import org.dradgo.application.security.DataClassificationService;
import org.dradgo.application.security.RedactionPolicyService;
import org.dradgo.domain.integration.repohost.Branch;
import org.dradgo.domain.integration.repohost.PullRequest;
import org.dradgo.domain.integration.repohost.PullRequestRef;
import org.dradgo.domain.integration.repohost.Repository;
import org.dradgo.domain.integration.repohost.RepositoryRef;
import org.dradgo.domain.registry.IntegrationFailureCategory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Unit tests for {@link BitbucketRealAdapter} with a mocked HTTP layer ({@code
 * MockRestServiceServer.bindTo(RestClient.builder())}, mirroring {@code
 * GitHubRealAdapterUnitTest}). Covers the failure-classification ladder, redaction-on-egress,
 * idempotent PR creation, the Bitbucket Cloud REST v2 endpoint shapes, and the connectivity probe
 * (story 3i-3 / FR82).
 *
 * <p>Redaction is content-based (not vendor-scoped), so a {@code ghp_}-shaped token embedded in an
 * outbound Bitbucket PR body is still scrubbed to {@code [REDACTED_GITHUB_TOKEN]} — the assertion
 * simply proves the write went through {@code RedactionPolicyService} before egress.
 */
class BitbucketRealAdapterUnitTest {

  private static final String BASE_URL = "https://api.bitbucket.org";

  /** The versioned API root — the host base URL plus the Bitbucket Cloud REST v2 segment. */
  private static final String API = BASE_URL + "/2.0";

  private static final String REPO_JSON =
      """
      {"full_name":"octo/hello","mainbranch":{"name":"main"},\
      "links":{"html":{"href":"https://bitbucket.org/octo/hello"}}}
      """;
  private static final String PR_JSON =
      """
      {"id":7,"source":{"branch":{"name":"feature/x"}},"state":"OPEN",\
      "links":{"html":{"href":"https://bitbucket.org/octo/hello/pull-requests/7"}},\
      "created_on":"2026-05-01T10:00:00+00:00"}
      """;

  private MockRestServiceServer mockServer;
  private BitbucketRealAdapter adapter;

  @BeforeEach
  void setUp() {
    RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
    mockServer = MockRestServiceServer.bindTo(builder).build();
    RestClient restClient = builder.build();
    BitbucketProperties properties =
        new BitbucketProperties(
            "workspace:app_password", BASE_URL, new BitbucketProperties.Timeout(5_000L, 30_000L));
    adapter =
        new BitbucketRealAdapter(
            restClient, properties, new RedactionPolicyService(new DataClassificationService()));
  }

  // -------- Reads -----------------------------------------------------------------------------

  @Test
  void getRepositoryHappyPathMapsJson() {
    mockServer
        .expect(requestTo(API + "/repositories/octo/hello"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess(REPO_JSON, MediaType.APPLICATION_JSON));

    Optional<Repository> repo = adapter.getRepositoryByRef(RepositoryRef.of("octo/hello"));

    assertTrue(repo.isPresent());
    assertEquals("octo/hello", repo.get().repoRef().value());
    assertEquals("octo/hello", repo.get().fullName());
    assertEquals("main", repo.get().defaultBranch());
    assertEquals("https://bitbucket.org/octo/hello", repo.get().url());
    mockServer.verify();
  }

  @Test
  void getRepositoryReturnsEmptyOn404() {
    mockServer
        .expect(requestTo(API + "/repositories/octo/missing"))
        .andRespond(withStatus(HttpStatus.NOT_FOUND));

    assertTrue(adapter.getRepositoryByRef(RepositoryRef.of("octo/missing")).isEmpty());
    mockServer.verify();
  }

  @Test
  void getPullRequestHappyPathMapsIdAndState() {
    mockServer
        .expect(requestTo(API + "/repositories/octo/hello/pullrequests/7"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess(PR_JSON, MediaType.APPLICATION_JSON));

    Optional<PullRequest> pr = adapter.getPullRequestByRef(PullRequestRef.of("octo/hello#7"));

    assertTrue(pr.isPresent());
    assertEquals("octo/hello#7", pr.get().prRef().value());
    assertEquals("octo/hello", pr.get().repoRef().value());
    assertEquals(7, pr.get().number());
    assertEquals("feature/x", pr.get().sourceBranch());
    assertEquals("open", pr.get().state());
    assertFalse(pr.get().merged());
    mockServer.verify();
  }

  @Test
  void getPullRequestMergedStateDerivesMergedBoolean() {
    String mergedPr =
        """
        {"id":8,"source":{"branch":{"name":"feature/y"}},"state":"MERGED",\
        "links":{"html":{"href":"https://bitbucket.org/octo/hello/pull-requests/8"}},\
        "created_on":"2026-05-01T10:00:00+00:00"}
        """;
    mockServer
        .expect(requestTo(API + "/repositories/octo/hello/pullrequests/8"))
        .andRespond(withSuccess(mergedPr, MediaType.APPLICATION_JSON));

    Optional<PullRequest> pr = adapter.getPullRequestByRef(PullRequestRef.of("octo/hello#8"));

    assertTrue(pr.isPresent());
    assertTrue(pr.get().merged());
    assertEquals("merged", pr.get().state());
    mockServer.verify();
  }

  @Test
  void getBranchHappyPathMapsHeadSha() {
    mockServer
        .expect(requestTo(API + "/repositories/octo/hello/refs/branches/main"))
        .andRespond(
            withSuccess(
                "{\"name\":\"main\",\"target\":{\"hash\":\"abc123\"}}",
                MediaType.APPLICATION_JSON));

    Optional<Branch> branch = adapter.getBranchByRef(RepositoryRef.of("octo/hello"), "main");

    assertTrue(branch.isPresent());
    assertEquals("main", branch.get().name());
    assertEquals("abc123", branch.get().headSha());
    mockServer.verify();
  }

  // -------- Failure classification ladder -----------------------------------------------------

  @Test
  void on401MapsToAuthFailed() {
    expectRepoGet(withStatus(HttpStatus.UNAUTHORIZED));
    assertCategory(
        IntegrationFailureCategory.BITBUCKET_AUTH_FAILED,
        () -> adapter.getRepositoryByRef(RepositoryRef.of("octo/hello")));
  }

  @Test
  void on403MapsToPermissionDenied() {
    expectRepoGet(withStatus(HttpStatus.FORBIDDEN));
    assertCategory(
        IntegrationFailureCategory.BITBUCKET_PERMISSION_DENIED,
        () -> adapter.getRepositoryByRef(RepositoryRef.of("octo/hello")));
  }

  @Test
  void on429MapsToRateLimited() {
    expectRepoGet(withStatus(HttpStatus.TOO_MANY_REQUESTS));
    assertCategory(
        IntegrationFailureCategory.BITBUCKET_RATE_LIMITED,
        () -> adapter.getRepositoryByRef(RepositoryRef.of("octo/hello")));
  }

  @Test
  void on404OnCreateIsClassifiedRepoNotFound() {
    mockServer
        .expect(requestTo(API + "/repositories/octo/missing"))
        .andRespond(withStatus(HttpStatus.NOT_FOUND));
    assertCategory(
        IntegrationFailureCategory.BITBUCKET_REPO_NOT_FOUND,
        () ->
            adapter.createPullRequest(
                RepositoryRef.of("octo/missing"), "feature/x", null, "t", "b"));
  }

  @Test
  void on400OnCreateMapsToBranchProtected() {
    mockServer
        .expect(requestTo(API + "/repositories/octo/hello"))
        .andRespond(withSuccess(REPO_JSON, MediaType.APPLICATION_JSON));
    mockServer
        .expect(requestTo(startsWith(API + "/repositories/octo/hello/pullrequests")))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess("{\"values\":[]}", MediaType.APPLICATION_JSON));
    mockServer
        .expect(requestTo(API + "/repositories/octo/hello/pullrequests"))
        .andExpect(method(HttpMethod.POST))
        .andRespond(withStatus(HttpStatus.BAD_REQUEST));

    assertCategory(
        IntegrationFailureCategory.BITBUCKET_BRANCH_PROTECTED,
        () ->
            adapter.createPullRequest(
                RepositoryRef.of("octo/hello"), "feature/x", null, "title", "body"));
  }

  @Test
  void on5xxMapsToNetworkFailure() {
    expectRepoGet(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
    assertCategory(
        IntegrationFailureCategory.BITBUCKET_NETWORK_FAILURE,
        () -> adapter.getRepositoryByRef(RepositoryRef.of("octo/hello")));
  }

  @Test
  void onNetworkIoMapsToNetworkFailure() {
    expectRepoGet(withException(new SocketTimeoutException("read timed out")));
    assertCategory(
        IntegrationFailureCategory.BITBUCKET_NETWORK_FAILURE,
        () -> adapter.getRepositoryByRef(RepositoryRef.of("octo/hello")));
  }

  @Test
  void malformedRepoRefThrowsClassifiedWithoutNetwork() {
    assertCategory(
        IntegrationFailureCategory.BITBUCKET_REPO_NOT_FOUND,
        () -> adapter.getRepositoryByRef(RepositoryRef.of("not a valid ref!")));
    mockServer.verify();
  }

  // -------- Redaction-on-egress ---------------------------------------------------------------

  @Test
  void commentOnPullRequestRedactsSecretInOutboundBody() {
    String secret = "ghp_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    mockServer
        .expect(requestTo(API + "/repositories/octo/hello/pullrequests/7/comments"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(content().string(not(containsString(secret))))
        .andExpect(content().string(containsString("[REDACTED_GITHUB_TOKEN]")))
        .andRespond(withSuccess("{\"id\":123}", MediaType.APPLICATION_JSON));

    adapter.commentOnPullRequest(
        PullRequestRef.of("octo/hello#7"), "Run summary with token " + secret);

    mockServer.verify();
  }

  @Test
  void updatePullRequestRedactsSecretInBody() {
    String secret = "ghp_cccccccccccccccccccccccccccccccccccc";
    mockServer
        .expect(requestTo(API + "/repositories/octo/hello/pullrequests/7"))
        .andExpect(method(HttpMethod.PUT))
        .andExpect(content().string(not(containsString(secret))))
        .andExpect(content().string(containsString("[REDACTED_GITHUB_TOKEN]")))
        .andRespond(withSuccess(PR_JSON, MediaType.APPLICATION_JSON));

    adapter.updatePullRequest(PullRequestRef.of("octo/hello#7"), "new body " + secret);

    mockServer.verify();
  }

  // -------- Idempotent PR creation ------------------------------------------------------------

  @Test
  void createPullRequestReturnsExistingOpenPrWithoutPosting() {
    mockServer
        .expect(requestTo(API + "/repositories/octo/hello"))
        .andRespond(withSuccess(REPO_JSON, MediaType.APPLICATION_JSON));
    mockServer
        .expect(requestTo(startsWith(API + "/repositories/octo/hello/pullrequests")))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess("{\"values\":[" + PR_JSON + "]}", MediaType.APPLICATION_JSON));
    // No POST expectation — an unexpected POST would fail mockServer.verify().

    PullRequest pr =
        adapter.createPullRequest(
            RepositoryRef.of("octo/hello"), "feature/x", null, "title", "body");

    assertEquals("octo/hello#7", pr.prRef().value());
    assertEquals(7, pr.number());
    mockServer.verify();
  }

  @Test
  void createPullRequestPostsSourceAndDestinationWithoutDraftFlag() {
    String secret = "ghp_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    mockServer
        .expect(requestTo(API + "/repositories/octo/hello"))
        .andRespond(withSuccess(REPO_JSON, MediaType.APPLICATION_JSON));
    mockServer
        .expect(requestTo(startsWith(API + "/repositories/octo/hello/pullrequests")))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess("{\"values\":[]}", MediaType.APPLICATION_JSON));
    mockServer
        .expect(requestTo(API + "/repositories/octo/hello/pullrequests"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(
            content().string(containsString("\"source\":{\"branch\":{\"name\":\"feature/x\"}}")))
        .andExpect(
            content().string(containsString("\"destination\":{\"branch\":{\"name\":\"main\"}}")))
        // Bitbucket Cloud has no draft PR concept — no draft flag is ever sent.
        .andExpect(content().string(not(containsString("draft"))))
        .andExpect(content().string(not(containsString(secret))))
        .andRespond(withSuccess(PR_JSON, MediaType.APPLICATION_JSON));

    PullRequest pr =
        adapter.createPullRequest(
            RepositoryRef.of("octo/hello"), "feature/x", null, "title " + secret, "body " + secret);

    assertEquals("octo/hello#7", pr.prRef().value());
    mockServer.verify();
  }

  // -------- Update happy ----------------------------------------------------------------------

  @Test
  void updatePullRequestPutsAndMaps() {
    mockServer
        .expect(requestTo(API + "/repositories/octo/hello/pullrequests/7"))
        .andExpect(method(HttpMethod.PUT))
        .andRespond(withSuccess(PR_JSON, MediaType.APPLICATION_JSON));

    PullRequest pr = adapter.updatePullRequest(PullRequestRef.of("octo/hello#7"), "new body");

    assertEquals("octo/hello#7", pr.prRef().value());
    mockServer.verify();
  }

  // -------- Connectivity probe ----------------------------------------------------------------

  @Test
  void verifyConnectivityReachableAndAuthenticatedOn200() {
    mockServer
        .expect(requestTo(API + "/repositories/octo/hello"))
        .andRespond(withSuccess(REPO_JSON, MediaType.APPLICATION_JSON));

    ConnectivityResult result = adapter.verifyConnectivity(RepositoryRef.of("octo/hello"), null);

    assertTrue(result.reachable());
    assertTrue(result.authenticated());
    mockServer.verify();
  }

  @Test
  void verifyConnectivityUnauthenticatedOn403() {
    mockServer
        .expect(requestTo(API + "/repositories/octo/hello"))
        .andRespond(withStatus(HttpStatus.FORBIDDEN));

    ConnectivityResult result = adapter.verifyConnectivity(RepositoryRef.of("octo/hello"), null);

    assertTrue(result.reachable());
    assertFalse(result.authenticated());
    mockServer.verify();
  }

  @Test
  void verifyConnectivityRepoNotFoundOn404() {
    mockServer
        .expect(requestTo(API + "/repositories/octo/hello"))
        .andRespond(withStatus(HttpStatus.NOT_FOUND));

    ConnectivityResult result = adapter.verifyConnectivity(RepositoryRef.of("octo/hello"), null);

    // Host + credentials fine (authenticated), but the specific repository is absent.
    assertFalse(result.reachable());
    assertTrue(result.authenticated());
    mockServer.verify();
  }

  @Test
  void verifyConnectivityUnreachableOnNetworkFault() {
    mockServer
        .expect(requestTo(API + "/repositories/octo/hello"))
        .andRespond(withException(new SocketTimeoutException("read timed out")));

    ConnectivityResult result = adapter.verifyConnectivity(RepositoryRef.of("octo/hello"), null);

    assertFalse(result.reachable());
    assertFalse(result.authenticated());
    mockServer.verify();
  }

  @Test
  void verifyConnectivityWhoamiWhenRepoIsNull() {
    mockServer
        .expect(requestTo(API + "/user"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess("{\"account_id\":\"5b10\"}", MediaType.APPLICATION_JSON));

    ConnectivityResult result = adapter.verifyConnectivity(null, null);

    assertTrue(result.reachable());
    assertTrue(result.authenticated());
    mockServer.verify();
  }

  // -------- helpers ---------------------------------------------------------------------------

  private void expectRepoGet(org.springframework.test.web.client.ResponseCreator r) {
    mockServer
        .expect(requestTo(API + "/repositories/octo/hello"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(r);
  }

  private void assertCategory(IntegrationFailureCategory expected, Runnable call) {
    RepositoryHostAdapterException error =
        assertThrows(RepositoryHostAdapterException.class, call::run);
    assertEquals(expected, error.failureCategory());
    mockServer.verify();
  }
}
