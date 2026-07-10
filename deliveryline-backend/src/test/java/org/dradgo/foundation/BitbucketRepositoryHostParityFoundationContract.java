package org.dradgo.foundation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.dradgo.adapters.integration.repohost.bitbucket.BitbucketMockAdapter;
import org.dradgo.adapters.integration.repohost.bitbucket.BitbucketMockScenarioRegistry;
import org.dradgo.adapters.integration.repohost.bitbucket.BitbucketRealAdapter;
import org.dradgo.application.integration.ConnectivityResult;
import org.dradgo.application.integration.bitbucket.BitbucketProperties;
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
 * Foundation contract #28 (story 3i-3 / FR82 AC7) — the second real {@code RepositoryHostAdapter}
 * kind: Bitbucket. Mirrors {@code RepositoryHostAbstractionFoundationContract} (Contract #15, the
 * GitHub twin). Asserts (a) both Bitbucket implementations satisfy the vendor-neutral {@link
 * RepositoryHostAdapter} port, (b) a happy read returns a neutral {@link Repository}/{@link
 * PullRequest} in both (the real one against {@link MockRestServiceServer}-stubbed HTTP), (c) a
 * classified failure surfaces the same {@link IntegrationFailureCategory} in both, and (d) {@code
 * getCapabilities()} returns the declared Bitbucket set in both.
 *
 * <p>Registered in {@code FoundationGateVerificationTest} as Contract #28 — a {@code
 * *FoundationContract} class name matches no Surefire/Failsafe include pattern, so the delegate
 * entry there is its only execution path (the "inert unless registered" trap).
 */
@Tag("foundation-gate")
class BitbucketRepositoryHostParityFoundationContract {

  private static final String BASE_URL = "https://api.bitbucket.org";

  /** The versioned API root — the host base URL plus the Bitbucket Cloud REST v2 segment. */
  private static final String API = BASE_URL + "/2.0";

  private static final String REPO_JSON =
      """
      {"full_name":"octo/hello","mainbranch":{"name":"main"},\
      "links":{"html":{"href":"https://bitbucket.org/octo/hello"}}}
      """;

  private final RepositoryHostAdapter mock =
      new BitbucketMockAdapter(new BitbucketMockScenarioRegistry());

  @Test
  void bothAdaptersImplementTheSamePort() {
    assertTrue(
        RepositoryHostAdapter.class.isAssignableFrom(BitbucketMockAdapter.class),
        tag("BitbucketMockAdapter must implement RepositoryHostAdapter"));
    assertTrue(
        RepositoryHostAdapter.class.isAssignableFrom(BitbucketRealAdapter.class),
        tag("BitbucketRealAdapter must implement RepositoryHostAdapter"));
  }

  @Test
  void happyReadReturnsNeutralTypeInBoth() {
    var mockRepo =
        mock.getRepositoryByRef(
            RepositoryRef.of(BitbucketMockScenarioRegistry.REPO_FEATURE_LOW_RISK));
    assertTrue(mockRepo.isPresent(), tag("mock happy repo read present"));
    assertInstanceOf(Repository.class, mockRepo.get());

    RealHarness harness = realHarness();
    harness.server.expect(requestTo(API + "/repositories/octo/hello")).andRespond(repoOk());
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
                        PullRequestRef.of(BitbucketMockScenarioRegistry.REF_PR_PERMISSION_DENIED)))
            .failureCategory();

    RealHarness harness = realHarness();
    harness
        .server
        .expect(requestTo(API + "/repositories/octo/hello/pullrequests/7"))
        .andRespond(withStatus(HttpStatus.FORBIDDEN));
    IntegrationFailureCategory realCategory =
        assertThrows(
                RepositoryHostAdapterException.class,
                () -> harness.adapter.getPullRequestByRef(PullRequestRef.of("octo/hello#7")))
            .failureCategory();

    assertEquals(
        IntegrationFailureCategory.BITBUCKET_PERMISSION_DENIED, mockCategory, tag("mock category"));
    assertEquals(
        mockCategory, realCategory, tag("mock and real classify the same scenario identically"));
    harness.server.verify();
  }

  @Test
  void capabilitiesDeclaredIdenticallyInBoth() {
    RepositoryHostCapabilities expected = RepositoryHostCapabilities.bitbucketDefaults();
    assertEquals(
        expected, mock.getCapabilities(), tag("mock declares the Bitbucket capability set"));
    assertEquals(
        expected,
        realHarness().adapter.getCapabilities(),
        tag("real declares the Bitbucket capability set"));
  }

  @Test
  void verifyConnectivityProbeIsReachableAndAuthenticatedInBoth() {
    ConnectivityResult mockResult = mock.verifyConnectivity(RepositoryRef.of("octo/hello"), null);
    assertTrue(mockResult.reachable() && mockResult.authenticated(), tag("mock probe ok"));

    RealHarness harness = realHarness();
    harness.server.expect(requestTo(API + "/repositories/octo/hello")).andRespond(repoOk());
    ConnectivityResult realResult =
        harness.adapter.verifyConnectivity(RepositoryRef.of("octo/hello"), null);
    assertTrue(realResult.reachable() && realResult.authenticated(), tag("real probe ok"));
    harness.server.verify();
  }

  // -------- helpers ----------------------------------------------------------------------------

  private static String tag(String detail) {
    return FoundationGateAssertions.tagged("3i-3", "Bitbucket RepositoryHost parity: " + detail);
  }

  private RealHarness realHarness() {
    RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    BitbucketProperties properties =
        new BitbucketProperties(
            "workspace:app_password_parity",
            BASE_URL,
            new BitbucketProperties.Timeout(5_000L, 30_000L));
    BitbucketRealAdapter adapter =
        new BitbucketRealAdapter(
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
    private final BitbucketRealAdapter adapter;

    private RealHarness(MockRestServiceServer server, BitbucketRealAdapter adapter) {
      this.server = server;
      this.adapter = adapter;
    }
  }
}
