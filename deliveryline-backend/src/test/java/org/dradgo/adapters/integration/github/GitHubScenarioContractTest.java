package org.dradgo.adapters.integration.github;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Deterministic GitHub fixture library exit-gate (story 3.13 Task 7, mirrors {@code
 * LinearScenarioContractTest}). Confirms every production-classpath {@code github-fixtures/*.json}
 * loads and parses cleanly to a repository / PR / branch bundle, and that the three AC3 fixtures
 * use stable deterministic refs.
 */
class GitHubScenarioContractTest {

  @Test
  void everyDefaultScenarioHasAClasspathFixtureResource() {
    GitHubMockScenarioRegistry registry = new GitHubMockScenarioRegistry();
    Map<String, GitHubMockScenario> all = registry.all();
    assertTrue(
        all.keySet()
            .containsAll(
                Set.of(
                    GitHubMockScenarioRegistry.REPO_FEATURE_LOW_RISK,
                    GitHubMockScenarioRegistry.REPO_BUG_FIX,
                    GitHubMockScenarioRegistry.REPO_DOCS,
                    GitHubMockScenarioRegistry.REF_REPO_NOT_FOUND,
                    GitHubMockScenarioRegistry.REF_PR_PERMISSION_DENIED,
                    GitHubMockScenarioRegistry.REF_PR_RATE_LIMITED,
                    GitHubMockScenarioRegistry.REF_BRANCH_PROTECTED,
                    GitHubMockScenarioRegistry.REF_PR_CONFLICT)),
        "AC3/AC4/AC5: defaults must include three happy fixtures plus canonical adversarial refs");
    for (GitHubMockScenario scenario :
        all.values().stream()
            .filter(scenario -> scenario.behaviour() == GitHubMockScenario.Behaviour.HAPPY)
            .toList()) {
      assertEquals(
          GitHubMockScenario.Behaviour.HAPPY,
          scenario.behaviour(),
          () -> "production scenario " + scenario.ref() + " must be HAPPY");
      assertNotNull(
          scenario.fixtureResource(),
          () -> "HAPPY scenario " + scenario.ref() + " must declare a fixtureResource");
      try (InputStream stream =
          Thread.currentThread()
              .getContextClassLoader()
              .getResourceAsStream(scenario.fixtureResource())) {
        assertNotNull(
            stream,
            () ->
                "missing classpath fixture for "
                    + scenario.ref()
                    + " at "
                    + scenario.fixtureResource());
      } catch (Exception error) {
        throw new AssertionError("failed to read fixture for " + scenario.ref(), error);
      }
    }
  }

  @Test
  void everyFixtureParsesToValidDomainBundle() {
    GitHubMockScenarioRegistry registry = new GitHubMockScenarioRegistry();
    for (GitHubMockScenario scenario :
        registry.all().values().stream()
            .filter(scenario -> scenario.behaviour() == GitHubMockScenario.Behaviour.HAPPY)
            .toList()) {
      GitHubFixture fixture = registry.loadHappyFixture(scenario);
      assertEquals(
          scenario.ref(),
          fixture.repository().repoRef(),
          () -> "fixture repoRef must match registry key for " + scenario.ref());
      assertFalse(fixture.repository().fullName().isBlank());
      assertFalse(fixture.repository().defaultBranch().isBlank());

      // The single open PR ties back to the repo and carries the source branch (AC3).
      assertEquals(fixture.repository().repoRef(), fixture.pullRequest().repoRef());
      assertEquals("open", fixture.pullRequest().state());
      assertNotNull(fixture.pullRequest().createdAt());

      // The branch is the PR's source branch under the same repo (AC3).
      assertEquals(fixture.repository().repoRef(), fixture.branch().repoRef());
      assertEquals(fixture.pullRequest().sourceBranch(), fixture.branch().name());
      assertFalse(fixture.branch().headSha().isBlank());
    }
  }

  @Test
  void repoRefsUseStableDeterministicReferences() {
    GitHubMockScenarioRegistry registry = new GitHubMockScenarioRegistry();
    Set<String> refs = registry.all().keySet();
    assertTrue(refs.contains(GitHubMockScenarioRegistry.REPO_FEATURE_LOW_RISK));
    assertTrue(refs.contains(GitHubMockScenarioRegistry.REPO_BUG_FIX));
    assertTrue(refs.contains(GitHubMockScenarioRegistry.REPO_DOCS));
  }

  @Test
  void canonicalAdversarialRefsAreDefaultScenarios() {
    GitHubMockScenarioRegistry registry = new GitHubMockScenarioRegistry();
    assertEquals(
        GitHubMockScenario.Behaviour.REPO_NOT_FOUND,
        registry.find(GitHubMockScenarioRegistry.REF_REPO_NOT_FOUND).orElseThrow().behaviour());
    assertEquals(
        GitHubMockScenario.Behaviour.PERMISSION_DENIED,
        registry
            .find(GitHubMockScenarioRegistry.REF_PR_PERMISSION_DENIED)
            .orElseThrow()
            .behaviour());
    assertEquals(
        GitHubMockScenario.Behaviour.RATE_LIMITED,
        registry.find(GitHubMockScenarioRegistry.REF_PR_RATE_LIMITED).orElseThrow().behaviour());
    assertEquals(
        GitHubMockScenario.Behaviour.BRANCH_PROTECTED,
        registry.find(GitHubMockScenarioRegistry.REF_BRANCH_PROTECTED).orElseThrow().behaviour());
    assertEquals(
        GitHubMockScenario.Behaviour.CONFLICT,
        registry.find(GitHubMockScenarioRegistry.REF_PR_CONFLICT).orElseThrow().behaviour());
  }
}
