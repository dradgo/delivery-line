package org.dradgo.foundation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.dradgo.adapters.integration.repohost.github.GitHubMockAdapter;
import org.dradgo.adapters.integration.repohost.github.GitHubMockScenarioRegistry;
import org.dradgo.adapters.integration.repohost.github.GitHubRealAdapter;
import org.dradgo.application.integration.ConnectivityResult;
import org.dradgo.application.integration.github.GitHubProperties;
import org.dradgo.application.integration.repohost.RepositoryHostAdapter;
import org.dradgo.application.integration.repohost.RepositoryHostAdapterException;
import org.dradgo.application.security.DataClassificationService;
import org.dradgo.application.security.RedactionPolicyService;
import org.dradgo.domain.integration.repohost.PullRequest;
import org.dradgo.domain.integration.repohost.PullRequestRef;
import org.dradgo.domain.integration.repohost.Repository;
import org.dradgo.domain.integration.repohost.RepositoryHostCapabilities;
import org.dradgo.domain.integration.repohost.RepositoryRef;
import org.dradgo.domain.registry.IntegrationFailureCategory;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Foundation contract #15 (story 3.33 AC10) — the {@code RepositoryHostAdapter} abstraction. The
 * GitHub mock + real adapters implement the same vendor-neutral {@link RepositoryHostAdapter} port;
 * this contract asserts (a) both implementations satisfy the port, (b) a happy read returns a
 * neutral {@link Repository}/{@link PullRequest} in both (the real one against {@link
 * MockRestServiceServer}-stubbed HTTP), (c) a classified failure surfaces the same {@link
 * IntegrationFailureCategory} in both, and (d) {@code getCapabilities()} returns the declared
 * GitHub set in both. Mirrors {@code GitHubMockVsRealParityFoundationContract} (Contract #11) and
 * {@code TicketSourceAbstractionFoundationContract} (Contract #14).
 *
 * <p>The draft-PR clause of AC10 is vacuously satisfied — {@code createPullRequest} has no draft
 * parameter and no production caller asks for a draft PR (story 3.33 R5/OQ-5) — so this contract
 * leans on parity + capability declaration, not behavioral degradation.
 */
@Tag("foundation-gate")
class RepositoryHostAbstractionFoundationContract {

  private static final String BASE_URL = "https://api.github.com";
  private static final String REPO_JSON =
      """
      {"full_name":"octo/hello","default_branch":"main","html_url":"https://github.com/octo/hello"}
      """;

  private final RepositoryHostAdapter mock =
      new GitHubMockAdapter(new GitHubMockScenarioRegistry());

  @Test
  void bothAdaptersImplementTheSamePort() {
    assertTrue(
        RepositoryHostAdapter.class.isAssignableFrom(GitHubMockAdapter.class),
        tag("GitHubMockAdapter must implement RepositoryHostAdapter"));
    assertTrue(
        RepositoryHostAdapter.class.isAssignableFrom(GitHubRealAdapter.class),
        tag("GitHubRealAdapter must implement RepositoryHostAdapter"));
  }

  @Test
  void happyReadReturnsNeutralTypeInBoth() {
    var mockRepo =
        mock.getRepositoryByRef(RepositoryRef.of(GitHubMockScenarioRegistry.REPO_FEATURE_LOW_RISK));
    assertTrue(mockRepo.isPresent(), tag("mock happy repo read present"));
    assertInstanceOf(Repository.class, mockRepo.get());

    RealHarness harness = realHarness();
    harness.server.expect(requestTo(BASE_URL + "/repos/octo/hello")).andRespond(repoOk());
    var realRepo = harness.adapter.getRepositoryByRef(RepositoryRef.of("octo/hello"));
    assertTrue(realRepo.isPresent(), tag("real happy repo read present"));
    assertInstanceOf(Repository.class, realRepo.get());
    harness.server.verify();
  }

  @Test
  void classifiedFailureSurfacesSameCategoryInBoth() {
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

    assertEquals(
        IntegrationFailureCategory.GITHUB_PERMISSION_DENIED, mockCategory, tag("mock category"));
    assertEquals(
        mockCategory, realCategory, tag("mock and real classify the same scenario identically"));
    harness.server.verify();
  }

  @Test
  void capabilitiesDeclaredIdenticallyInBoth() {
    RepositoryHostCapabilities expected = RepositoryHostCapabilities.githubDefaults();
    assertEquals(expected, mock.getCapabilities(), tag("mock declares the GitHub capability set"));
    assertEquals(
        expected,
        realHarness().adapter.getCapabilities(),
        tag("real declares the GitHub capability set"));
  }

  @Test
  void verifyConnectivityProbeIsReachableAndAuthenticatedInBoth() {
    // Story 3c-8 (R1) — the net-new probe is present + deterministic on the mock and authenticates
    // cleanly on the real adapter against a stubbed 200 repository response.
    ConnectivityResult mockResult = mock.verifyConnectivity(RepositoryRef.of("octo/hello"), null);
    assertTrue(mockResult.reachable() && mockResult.authenticated(), tag("mock probe ok"));

    RealHarness harness = realHarness();
    harness.server.expect(requestTo(BASE_URL + "/repos/octo/hello")).andRespond(repoOk());
    ConnectivityResult realResult =
        harness.adapter.verifyConnectivity(RepositoryRef.of("octo/hello"), null);
    assertTrue(realResult.reachable() && realResult.authenticated(), tag("real probe ok"));
    harness.server.verify();
  }

  // -------- helpers ----------------------------------------------------------------------------

  private static String tag(String detail) {
    return FoundationGateAssertions.tagged("3.33", "RepositoryHost abstraction: " + detail);
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
