package org.dradgo.adapters.integration.repohost.bitbucket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.dradgo.application.integration.repohost.RepositoryHostAdapterException;
import org.dradgo.domain.integration.repohost.Branch;
import org.dradgo.domain.integration.repohost.PullRequest;
import org.dradgo.domain.integration.repohost.PullRequestRef;
import org.dradgo.domain.integration.repohost.Repository;
import org.dradgo.domain.integration.repohost.RepositoryRef;
import org.dradgo.domain.registry.IntegrationFailureCategory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Deterministic behaviour of {@link BitbucketMockAdapter} (story 3i-3 / FR82). */
class BitbucketMockAdapterUnitTest {

  private BitbucketMockScenarioRegistry registry;
  private BitbucketMockAdapter adapter;

  @BeforeEach
  void setUp() {
    registry = new BitbucketMockScenarioRegistry();
    adapter = new BitbucketMockAdapter(registry);
  }

  @Test
  void getRepositoryReturnsAllThreeProductionFixtures() {
    assertRepoPresent(
        BitbucketMockScenarioRegistry.REPO_FEATURE_LOW_RISK, "deliveryline/worker-pool");
    assertRepoPresent(BitbucketMockScenarioRegistry.REPO_BUG_FIX, "deliveryline/api-gateway");
    assertRepoPresent(BitbucketMockScenarioRegistry.REPO_DOCS, "deliveryline/runbooks");
  }

  @Test
  void getPullRequestReturnsAllThreeProductionFixtures() {
    assertPrPresent(BitbucketMockScenarioRegistry.PR_FEATURE_LOW_RISK, 101);
    assertPrPresent(BitbucketMockScenarioRegistry.PR_BUG_FIX, 102);
    assertPrPresent(BitbucketMockScenarioRegistry.PR_DOCS, 103);
  }

  @Test
  void getBranchReturnsTheSourceBranchPerRepo() {
    Optional<Branch> branch =
        adapter.getBranchByRef(
            RepositoryRef.of(BitbucketMockScenarioRegistry.REPO_FEATURE_LOW_RISK),
            BitbucketMockScenarioRegistry.BRANCH_FEATURE_LOW_RISK);
    assertTrue(branch.isPresent(), "BB-101's source branch must resolve from the fixture");
    assertEquals(BitbucketMockScenarioRegistry.BRANCH_FEATURE_LOW_RISK, branch.get().name());
    assertFalse(branch.get().headSha().isBlank());
  }

  @Test
  void getBranchReturnsEmptyWhenBranchBelongsToAnotherRepo() {
    assertTrue(
        adapter
            .getBranchByRef(
                RepositoryRef.of(BitbucketMockScenarioRegistry.REPO_BUG_FIX),
                BitbucketMockScenarioRegistry.BRANCH_FEATURE_LOW_RISK)
            .isEmpty());
  }

  @Test
  void lookupsReturnEmptyForUnregisteredRefs() {
    assertTrue(adapter.getRepositoryByRef(RepositoryRef.of("BB-UNKNOWN-999")).isEmpty());
    assertTrue(adapter.getPullRequestByRef(PullRequestRef.of("PR-UNKNOWN-999")).isEmpty());
  }

  @Test
  void repoNotFoundRefThrowsClassifiedException() {
    RepositoryHostAdapterException error =
        assertThrows(
            RepositoryHostAdapterException.class,
            () ->
                adapter.getRepositoryByRef(
                    RepositoryRef.of(BitbucketMockScenarioRegistry.REF_REPO_NOT_FOUND)));
    assertEquals(IntegrationFailureCategory.BITBUCKET_REPO_NOT_FOUND, error.failureCategory());
  }

  @Test
  void permissionDeniedRefThrowsClassifiedException() {
    RepositoryHostAdapterException error =
        assertThrows(
            RepositoryHostAdapterException.class,
            () ->
                adapter.getPullRequestByRef(
                    PullRequestRef.of(BitbucketMockScenarioRegistry.REF_PR_PERMISSION_DENIED)));
    assertEquals(IntegrationFailureCategory.BITBUCKET_PERMISSION_DENIED, error.failureCategory());
  }

  @Test
  void rateLimitedRefThrowsClassifiedExceptionOnComment() {
    RepositoryHostAdapterException error =
        assertThrows(
            RepositoryHostAdapterException.class,
            () ->
                adapter.commentOnPullRequest(
                    PullRequestRef.of(BitbucketMockScenarioRegistry.REF_PR_RATE_LIMITED),
                    "any body"));
    assertEquals(IntegrationFailureCategory.BITBUCKET_RATE_LIMITED, error.failureCategory());
  }

  @Test
  void protectedBranchRefThrowsClassifiedExceptionOnCreate() {
    RepositoryHostAdapterException error =
        assertThrows(
            RepositoryHostAdapterException.class,
            () ->
                adapter.createPullRequest(
                    RepositoryRef.of(BitbucketMockScenarioRegistry.REPO_FEATURE_LOW_RISK),
                    BitbucketMockScenarioRegistry.REF_BRANCH_PROTECTED,
                    null,
                    "title",
                    "body"));
    assertEquals(IntegrationFailureCategory.BITBUCKET_BRANCH_PROTECTED, error.failureCategory());
  }

  @Test
  void conflictRefReturnsPullRequestWithDeliberatelyConflictingRepo() {
    Optional<PullRequest> conflicting =
        adapter.getPullRequestByRef(
            PullRequestRef.of(BitbucketMockScenarioRegistry.REF_PR_CONFLICT));
    assertTrue(conflicting.isPresent(), "the conflict ref returns a PR (it does not throw)");
    assertEquals(
        BitbucketMockScenarioRegistry.CONFLICT_PR_REPO_REF, conflicting.get().repoRef().value());
    assertNotEquals(
        BitbucketMockScenarioRegistry.REPO_FEATURE_LOW_RISK, conflicting.get().repoRef().value());
  }

  @Test
  void createPullRequestIsIdempotentOnRepoAndBranch() {
    assertTrue(adapter.createdPullRequests().isEmpty());
    PullRequest first =
        adapter.createPullRequest(
            RepositoryRef.of(BitbucketMockScenarioRegistry.REPO_FEATURE_LOW_RISK),
            "feature/new-thing",
            null,
            "t",
            "b");
    PullRequest second =
        adapter.createPullRequest(
            RepositoryRef.of(BitbucketMockScenarioRegistry.REPO_FEATURE_LOW_RISK),
            "feature/new-thing",
            null,
            "different title",
            "different body");
    assertEquals(first, second, "re-creating the same (repoRef, branch) returns the same PR");
    assertEquals(1, adapter.createdPullRequests().size(), "no duplicate record stacked");
    assertEquals(
        Optional.of(first),
        adapter.getPullRequestByRef(first.prRef()),
        "a PR created by the mock must be readable back through the same port");

    adapter.clearCreatedPullRequests();
    assertTrue(adapter.createdPullRequests().isEmpty());
  }

  @Test
  void commentOnPullRequestIsIdempotentOnPrAndContent() {
    assertTrue(adapter.postedComments().isEmpty());
    adapter.commentOnPullRequest(
        PullRequestRef.of(BitbucketMockScenarioRegistry.PR_FEATURE_LOW_RISK), "Run summary v1");
    adapter.commentOnPullRequest(
        PullRequestRef.of(BitbucketMockScenarioRegistry.PR_FEATURE_LOW_RISK), "Run summary v1");
    assertEquals(1, adapter.postedComments().size(), "re-posting the same content is a no-op");

    adapter.commentOnPullRequest(
        PullRequestRef.of(BitbucketMockScenarioRegistry.PR_FEATURE_LOW_RISK), "Run summary v2");
    assertEquals(2, adapter.postedComments().size());

    adapter.clearPostedComments();
    assertTrue(adapter.postedComments().isEmpty());
  }

  @Test
  void updatePullRequestReturnsSeededRecordAndThrowsForUnknownRef() {
    PullRequest updated =
        adapter.updatePullRequest(
            PullRequestRef.of(BitbucketMockScenarioRegistry.PR_FEATURE_LOW_RISK), "new body");
    assertEquals(BitbucketMockScenarioRegistry.PR_FEATURE_LOW_RISK, updated.prRef().value());

    RepositoryHostAdapterException error =
        assertThrows(
            RepositoryHostAdapterException.class,
            () -> adapter.updatePullRequest(PullRequestRef.of("PR-NEVER-SEEDED"), "body"));
    assertEquals(IntegrationFailureCategory.BITBUCKET_PR_NOT_FOUND, error.failureCategory());
  }

  @Test
  void clearTestScenariosRemovesOnlyTestPrefixedEntries() {
    registry.register(
        new BitbucketMockScenario(
            "TEST-EXAMPLE", BitbucketMockScenario.Behaviour.NOT_FOUND, null, null));
    assertTrue(registry.find("TEST-EXAMPLE").isPresent());

    registry.clearTestScenarios();
    assertFalse(registry.find("TEST-EXAMPLE").isPresent());
    assertTrue(registry.find(BitbucketMockScenarioRegistry.REPO_FEATURE_LOW_RISK).isPresent());
  }

  private void assertRepoPresent(String repoRef, String expectedFullName) {
    Optional<Repository> repository = adapter.getRepositoryByRef(RepositoryRef.of(repoRef));
    assertTrue(
        repository.isPresent(), () -> repoRef + " must resolve from the production fixtures");
    assertEquals(repoRef, repository.get().repoRef().value());
    assertEquals(expectedFullName, repository.get().fullName());
    assertEquals("main", repository.get().defaultBranch());
  }

  private void assertPrPresent(String prRef, int expectedNumber) {
    Optional<PullRequest> pullRequest = adapter.getPullRequestByRef(PullRequestRef.of(prRef));
    assertTrue(pullRequest.isPresent(), () -> prRef + " must resolve from the production fixtures");
    assertEquals(prRef, pullRequest.get().prRef().value());
    assertEquals(expectedNumber, pullRequest.get().number());
    assertEquals("open", pullRequest.get().state());
  }
}
