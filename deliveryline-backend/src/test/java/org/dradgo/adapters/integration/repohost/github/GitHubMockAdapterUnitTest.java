package org.dradgo.adapters.integration.repohost.github;

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

class GitHubMockAdapterUnitTest {

  private GitHubMockScenarioRegistry registry;
  private GitHubMockAdapter adapter;

  @BeforeEach
  void setUp() {
    registry = new GitHubMockScenarioRegistry();
    adapter = new GitHubMockAdapter(registry);
  }

  @Test
  void getRepositoryReturnsAllThreeProductionFixtures() {
    assertRepoPresent(GitHubMockScenarioRegistry.REPO_FEATURE_LOW_RISK, "deliveryline/worker-pool");
    assertRepoPresent(GitHubMockScenarioRegistry.REPO_BUG_FIX, "deliveryline/artifact-pagination");
    assertRepoPresent(GitHubMockScenarioRegistry.REPO_DOCS, "deliveryline/recovery-runbook");
  }

  @Test
  void getPullRequestReturnsAllThreeProductionFixtures() {
    assertPrPresent(GitHubMockScenarioRegistry.PR_FEATURE_LOW_RISK, 101);
    assertPrPresent(GitHubMockScenarioRegistry.PR_BUG_FIX, 102);
    assertPrPresent(GitHubMockScenarioRegistry.PR_DOCS, 103);
  }

  @Test
  void getBranchReturnsTheSourceBranchPerRepo() {
    Optional<Branch> branch =
        adapter.getBranchByRef(
            RepositoryRef.of(GitHubMockScenarioRegistry.REPO_FEATURE_LOW_RISK),
            GitHubMockScenarioRegistry.BRANCH_FEATURE_LOW_RISK);
    assertTrue(branch.isPresent(), "GH-101's source branch must resolve from the fixture");
    assertEquals(GitHubMockScenarioRegistry.BRANCH_FEATURE_LOW_RISK, branch.get().name());
    assertFalse(branch.get().headSha().isBlank());

    assertTrue(
        adapter
            .getBranchByRef(
                RepositoryRef.of(GitHubMockScenarioRegistry.REPO_BUG_FIX),
                GitHubMockScenarioRegistry.BRANCH_BUG_FIX)
            .isPresent());
    assertTrue(
        adapter
            .getBranchByRef(
                RepositoryRef.of(GitHubMockScenarioRegistry.REPO_DOCS),
                GitHubMockScenarioRegistry.BRANCH_DOCS)
            .isPresent());
  }

  @Test
  void lookupsReturnEmptyForUnregisteredRefs() {
    assertTrue(adapter.getRepositoryByRef(RepositoryRef.of("GH-UNKNOWN-999")).isEmpty());
    assertTrue(adapter.getPullRequestByRef(PullRequestRef.of("PR-UNKNOWN-999")).isEmpty());
    assertTrue(
        adapter
            .getBranchByRef(
                RepositoryRef.of(GitHubMockScenarioRegistry.REPO_FEATURE_LOW_RISK),
                "no/such-branch")
            .isEmpty());
  }

  @Test
  void getBranchReturnsEmptyWhenBranchBelongsToAnotherRepo() {
    // The branch is keyed by (repoRef, name) — the right branch name under the wrong repo is empty.
    assertTrue(
        adapter
            .getBranchByRef(
                RepositoryRef.of(GitHubMockScenarioRegistry.REPO_BUG_FIX),
                GitHubMockScenarioRegistry.BRANCH_FEATURE_LOW_RISK)
            .isEmpty());
  }

  @Test
  void repoNotFoundRefThrowsClassifiedException() {
    RepositoryHostAdapterException error =
        assertThrows(
            RepositoryHostAdapterException.class,
            () ->
                adapter.getRepositoryByRef(
                    RepositoryRef.of(GitHubMockScenarioRegistry.REF_REPO_NOT_FOUND)));
    assertEquals(IntegrationFailureCategory.GITHUB_REPO_NOT_FOUND, error.failureCategory());
  }

  @Test
  void permissionDeniedRefThrowsClassifiedException() {
    RepositoryHostAdapterException error =
        assertThrows(
            RepositoryHostAdapterException.class,
            () ->
                adapter.getPullRequestByRef(
                    PullRequestRef.of(GitHubMockScenarioRegistry.REF_PR_PERMISSION_DENIED)));
    assertEquals(IntegrationFailureCategory.GITHUB_PERMISSION_DENIED, error.failureCategory());
  }

  @Test
  void rateLimitedRefThrowsClassifiedExceptionOnComment() {
    RepositoryHostAdapterException error =
        assertThrows(
            RepositoryHostAdapterException.class,
            () ->
                adapter.commentOnPullRequest(
                    PullRequestRef.of(GitHubMockScenarioRegistry.REF_PR_RATE_LIMITED), "any body"));
    assertEquals(IntegrationFailureCategory.GITHUB_RATE_LIMITED, error.failureCategory());
  }

  @Test
  void protectedBranchRefThrowsClassifiedExceptionOnCreate() {
    RepositoryHostAdapterException error =
        assertThrows(
            RepositoryHostAdapterException.class,
            () ->
                adapter.createPullRequest(
                    RepositoryRef.of(GitHubMockScenarioRegistry.REPO_FEATURE_LOW_RISK),
                    GitHubMockScenarioRegistry.REF_BRANCH_PROTECTED,
                    null,
                    "title",
                    "body"));
    assertEquals(IntegrationFailureCategory.GITHUB_BRANCH_PROTECTED, error.failureCategory());
  }

  @Test
  void failureCategoryDerivesFromBehaviourWhenScenarioLeavesItNull() {
    // A scenario may omit expectedFailureCategory — the adapter derives it from the behaviour.
    registry.register(
        new GitHubMockScenario(
            "TEST-REPO-404", GitHubMockScenario.Behaviour.REPO_NOT_FOUND, null, null));
    RepositoryHostAdapterException error =
        assertThrows(
            RepositoryHostAdapterException.class,
            () -> adapter.getRepositoryByRef(RepositoryRef.of("TEST-REPO-404")));
    assertEquals(IntegrationFailureCategory.GITHUB_REPO_NOT_FOUND, error.failureCategory());
  }

  @Test
  void conflictRefReturnsPullRequestWithDeliberatelyConflictingRepo() {
    Optional<PullRequest> conflicting =
        adapter.getPullRequestByRef(PullRequestRef.of(GitHubMockScenarioRegistry.REF_PR_CONFLICT));
    assertTrue(conflicting.isPresent(), "AC4: the conflict ref returns a PR (it does not throw)");
    assertEquals(
        GitHubMockScenarioRegistry.CONFLICT_PR_REPO_REF, conflicting.get().repoRef().value());
    assertNotEquals(
        GitHubMockScenarioRegistry.REPO_FEATURE_LOW_RISK,
        conflicting.get().repoRef().value(),
        "the conflict PR's repo must differ from any seeded happy repo");
  }

  @Test
  void createPullRequestIsIdempotentOnRepoAndBranch() {
    assertTrue(adapter.createdPullRequests().isEmpty());
    PullRequest first =
        adapter.createPullRequest(
            RepositoryRef.of(GitHubMockScenarioRegistry.REPO_FEATURE_LOW_RISK),
            "feature/new-thing",
            null,
            "t",
            "b");
    PullRequest second =
        adapter.createPullRequest(
            RepositoryRef.of(GitHubMockScenarioRegistry.REPO_FEATURE_LOW_RISK),
            "feature/new-thing",
            null,
            "different title",
            "different body");
    assertEquals(first, second, "AC8: re-creating the same (repoRef, branch) returns the same PR");
    assertEquals(1, adapter.createdPullRequests().size(), "no duplicate fixture record stacked");
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
        PullRequestRef.of(GitHubMockScenarioRegistry.PR_FEATURE_LOW_RISK), "Run summary v1");
    adapter.commentOnPullRequest(
        PullRequestRef.of(GitHubMockScenarioRegistry.PR_FEATURE_LOW_RISK), "Run summary v1");
    assertEquals(1, adapter.postedComments().size(), "AC8: re-posting the same content is a no-op");

    // A different body on the same PR records a distinct comment.
    adapter.commentOnPullRequest(
        PullRequestRef.of(GitHubMockScenarioRegistry.PR_FEATURE_LOW_RISK), "Run summary v2");
    assertEquals(2, adapter.postedComments().size());

    adapter.clearPostedComments();
    assertTrue(adapter.postedComments().isEmpty());
  }

  @Test
  void commentOnPullRequestDoesNotDedupeDifferentBodiesWithSameJavaHashCode() {
    assertEquals("Aa".hashCode(), "BB".hashCode(), "test precondition: Java hash collision");

    adapter.commentOnPullRequest(
        PullRequestRef.of(GitHubMockScenarioRegistry.PR_FEATURE_LOW_RISK), "Aa");
    adapter.commentOnPullRequest(
        PullRequestRef.of(GitHubMockScenarioRegistry.PR_FEATURE_LOW_RISK), "BB");

    assertEquals(
        2,
        adapter.postedComments().size(),
        "distinct comment bodies must not collapse just because their 32-bit hash collides");
  }

  @Test
  void updatePullRequestReturnsSeededRecordAndThrowsForUnknownRef() {
    PullRequest updated =
        adapter.updatePullRequest(
            PullRequestRef.of(GitHubMockScenarioRegistry.PR_FEATURE_LOW_RISK), "new body");
    assertEquals(GitHubMockScenarioRegistry.PR_FEATURE_LOW_RISK, updated.prRef().value());

    RepositoryHostAdapterException error =
        assertThrows(
            RepositoryHostAdapterException.class,
            () -> adapter.updatePullRequest(PullRequestRef.of("PR-NEVER-SEEDED"), "body"));
    assertEquals(IntegrationFailureCategory.GITHUB_PR_NOT_FOUND, error.failureCategory());
  }

  @Test
  void clearTestScenariosRemovesOnlyTestPrefixedEntries() {
    registry.register(
        new GitHubMockScenario("TEST-EXAMPLE", GitHubMockScenario.Behaviour.NOT_FOUND, null, null));
    assertTrue(registry.find("TEST-EXAMPLE").isPresent());
    assertTrue(registry.find(GitHubMockScenarioRegistry.REPO_FEATURE_LOW_RISK).isPresent());

    registry.clearTestScenarios();
    assertFalse(
        registry.find("TEST-EXAMPLE").isPresent(),
        "clearTestScenarios must drop TEST-prefixed entries");
    assertTrue(
        registry.find(GitHubMockScenarioRegistry.REPO_FEATURE_LOW_RISK).isPresent(),
        "clearTestScenarios must preserve the built-in production fixtures");
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
