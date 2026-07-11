package org.dradgo.adapters.integration.repohost.github;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.dradgo.domain.integration.repohost.Branch;
import org.dradgo.domain.integration.repohost.PullRequest;
import org.dradgo.domain.integration.repohost.Repository;
import org.dradgo.domain.registry.IntegrationFailureCategory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * In-memory deterministic registry of {@link GitHubMockScenario} entries keyed by external {@code
 * ref}. Production-classpath happy fixtures live under {@code src/main/resources/github-fixtures/};
 * tests may register adversarial / conflict scenarios via {@link #register(GitHubMockScenario)}
 * (cleanup with {@link #clearTestScenarios()}). Mirrors {@code LinearMockScenarioRegistry}.
 *
 * <p>The three happy fixtures (story 3.13 AC3) are loaded eagerly at construction into per-entity
 * indexes so the adapter can resolve repositories by {@code repoRef}, PRs by {@code prRef}, and
 * branches by {@code (repoRef, name)}. Profile-activated by {@code github-mock} so the bean only
 * loads when the mock GitHub stack is wired.
 *
 * <p>Determinism note: fixture JSON files contain absolute {@code createdAt} instants and stable
 * SHAs/IDs — no wall-clock arithmetic happens inside the registry.
 */
@Component
@Profile("github-mock")
public class GitHubMockScenarioRegistry {

  private static final Logger log = LoggerFactory.getLogger(GitHubMockScenarioRegistry.class);

  private static final String CLASSPATH_PREFIX = "github-fixtures/";
  private static final String TEST_REF_PREFIX = "TEST-";
  private static final String BRANCH_KEY_SEPARATOR = "\u0000";

  /**
   * Stable repository references for the three production fixtures (story 3.13 AC3, aligned to
   * LIN-101/102/103).
   */
  public static final String REPO_FEATURE_LOW_RISK = "GH-101";

  public static final String REPO_BUG_FIX = "GH-102";
  public static final String REPO_DOCS = "GH-103";

  /** Stable PR references for the three production fixtures (one open PR per repo, AC3). */
  public static final String PR_FEATURE_LOW_RISK = "PR-101";

  public static final String PR_BUG_FIX = "PR-102";
  public static final String PR_DOCS = "PR-103";

  /** Stable source-branch names for the three production fixtures (the PR's source branch, AC3). */
  public static final String BRANCH_FEATURE_LOW_RISK = "feature/healthz-endpoint";

  public static final String BRANCH_BUG_FIX = "fix/pagination-off-by-one";
  public static final String BRANCH_DOCS = "docs/recovery-runbook";

  /**
   * Stable refs for the documented adversarial / conflict scenarios (registered by tests via {@link
   * #register(GitHubMockScenario)} — never auto-loaded into a demo/local runtime; AC4/AC5).
   */
  public static final String REF_REPO_NOT_FOUND = "repo-not-found";

  public static final String REF_PR_PERMISSION_DENIED = "pr-403";
  public static final String REF_PR_RATE_LIMITED = "pr-rate-limited";
  public static final String REF_BRANCH_PROTECTED = "protected-branch";
  public static final String REF_PR_CONFLICT = "PR-conflict";

  /**
   * Story 3h-5 (AC1) — the deterministic commit-SHA sentinel that makes {@link
   * GitHubMockAdapter#readCheckRuns} return a red CI verdict (one failing check with one fixture
   * failure annotation). Any other ref returns a green {@code SUCCESS}. Tests seed a run with this
   * as {@code ci_head_sha} to exercise the red path without touching the Behaviour enum.
   */
  public static final String CI_RED_HEAD_SHA = "ci-red";

  /**
   * Story 3h-5 (AC5) — a red-CI sentinel whose failing check-run body carries a planted secret (a
   * GitHub PAT). Tests seed a run with this {@code ci_head_sha} to assert that the composed CI
   * failure log is redaction-policed on the way into the persisted CI {@code runner_executions} raw
   * output ({@code [REDACTED_GITHUB_TOKEN]}, never the token). [redaction-fixture-two-gates]
   */
  public static final String CI_RED_WITH_SECRET_HEAD_SHA = "ci-red-secret";

  /**
   * The repository a {@link GitHubMockScenario.Behaviour#CONFLICT} PR deliberately points at — it
   * differs from any seeded happy repo so callers detect a {@code PR_REF_CONTEXT_MISMATCH} (AC4).
   */
  public static final String CONFLICT_PR_REPO_REF = "GH-999-unrelated";

  private final ConcurrentMap<String, GitHubMockScenario> scenarios = new ConcurrentHashMap<>();
  private final ConcurrentMap<String, Repository> repositoriesByRef = new ConcurrentHashMap<>();
  private final ConcurrentMap<String, PullRequest> pullRequestsByRef = new ConcurrentHashMap<>();
  private final ConcurrentMap<String, Branch> branchesByKey = new ConcurrentHashMap<>();
  private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

  public GitHubMockScenarioRegistry() {
    registerHappyDefault(REPO_FEATURE_LOW_RISK, "github-feature-low-risk.json");
    registerHappyDefault(REPO_BUG_FIX, "github-bug-fix.json");
    registerHappyDefault(REPO_DOCS, "github-docs.json");
    registerDefault(
        REF_REPO_NOT_FOUND,
        GitHubMockScenario.Behaviour.REPO_NOT_FOUND,
        null,
        IntegrationFailureCategory.GITHUB_REPO_NOT_FOUND);
    registerDefault(
        REF_PR_PERMISSION_DENIED,
        GitHubMockScenario.Behaviour.PERMISSION_DENIED,
        null,
        IntegrationFailureCategory.GITHUB_PERMISSION_DENIED);
    registerDefault(
        REF_PR_RATE_LIMITED,
        GitHubMockScenario.Behaviour.RATE_LIMITED,
        null,
        IntegrationFailureCategory.GITHUB_RATE_LIMITED);
    registerDefault(
        REF_BRANCH_PROTECTED,
        GitHubMockScenario.Behaviour.BRANCH_PROTECTED,
        null,
        IntegrationFailureCategory.GITHUB_BRANCH_PROTECTED);
    registerDefault(REF_PR_CONFLICT, GitHubMockScenario.Behaviour.CONFLICT, null, null);
  }

  private void registerHappyDefault(String repoRef, String fixtureFilename) {
    String resource = CLASSPATH_PREFIX + fixtureFilename;
    GitHubMockScenario scenario =
        registerDefault(repoRef, GitHubMockScenario.Behaviour.HAPPY, resource, null);
    GitHubFixture fixture = loadHappyFixture(scenario);
    repositoriesByRef.put(fixture.repository().repoRef().value(), fixture.repository());
    pullRequestsByRef.put(fixture.pullRequest().prRef().value(), fixture.pullRequest());
    branchesByKey.put(
        branchKey(fixture.branch().repoRef().value(), fixture.branch().name()), fixture.branch());
    log.debug(
        "github_mock fixture_loaded repoRef={} prRef={} branch={}",
        fixture.repository().repoRef().value(),
        fixture.pullRequest().prRef().value(),
        fixture.branch().name());
  }

  private GitHubMockScenario registerDefault(
      String ref,
      GitHubMockScenario.Behaviour behaviour,
      String fixtureResource,
      IntegrationFailureCategory expectedFailureCategory) {
    GitHubMockScenario scenario =
        new GitHubMockScenario(ref, behaviour, fixtureResource, expectedFailureCategory);
    scenarios.put(ref, scenario);
    return scenario;
  }

  public void register(GitHubMockScenario scenario) {
    Objects.requireNonNull(scenario, "scenario");
    scenarios.put(scenario.ref(), scenario);
  }

  /**
   * Test-only: seed (or replace) a PR at a specific external ref with a caller-controlled lifecycle
   * state / merged flag / source branch. Story 4.17 uses this to inject the conflict-detection
   * scenarios the built-in happy fixtures can't express — a PR merged-while-open (→
   * external_state_advanced), reopened-after-closed (→ external_state_reverted), or with a drifted
   * source branch (→ metadata_drift). The deleted/removed case is {@link #removePullRequest} (fresh
   * fetch → {@code Optional.empty()}); the link-broken case is a {@code PERMISSION_DENIED} scenario
   * registered via {@link #register}. Purged by {@link #clearTestScenarios} when the prRef begins
   * with {@code TEST-}.
   */
  public void seedPullRequest(PullRequest pullRequest) {
    Objects.requireNonNull(pullRequest, "pullRequest");
    pullRequestsByRef.put(pullRequest.prRef().value(), pullRequest);
  }

  /** Test-only: remove a seeded PR so a fresh {@code getPullRequestByRef} resolves to empty. */
  public void removePullRequest(String prRef) {
    Objects.requireNonNull(prRef, "prRef");
    pullRequestsByRef.remove(prRef);
  }

  public Optional<GitHubMockScenario> find(String ref) {
    return Optional.ofNullable(scenarios.get(ref));
  }

  public Map<String, GitHubMockScenario> all() {
    return Collections.unmodifiableMap(Map.copyOf(scenarios));
  }

  public Optional<Repository> findRepository(String repoRef) {
    return Optional.ofNullable(repositoriesByRef.get(repoRef));
  }

  public Optional<PullRequest> findPullRequest(String prRef) {
    return Optional.ofNullable(pullRequestsByRef.get(prRef));
  }

  public Optional<Branch> findBranch(String repoRef, String branchName) {
    return Optional.ofNullable(branchesByKey.get(branchKey(repoRef, branchName)));
  }

  /**
   * Loads the fixture JSON for a {@link GitHubMockScenario.Behaviour#HAPPY} scenario and returns
   * the deserialized {@link GitHubFixture}. Throws {@link IllegalStateException} for scenarios
   * without a fixture (callers must check the behaviour first).
   */
  public GitHubFixture loadHappyFixture(GitHubMockScenario scenario) {
    Objects.requireNonNull(scenario, "scenario");
    if (scenario.behaviour() != GitHubMockScenario.Behaviour.HAPPY) {
      throw new IllegalStateException(
          "loadHappyFixture only supports HAPPY scenarios (ref=" + scenario.ref() + ")");
    }
    String resource = scenario.fixtureResource();
    try (InputStream stream =
        Thread.currentThread().getContextClassLoader().getResourceAsStream(resource)) {
      if (stream == null) {
        log.warn("github_mock fixture_missing ref={} resource={}", scenario.ref(), resource);
        throw new IllegalStateException("Missing GitHub mock fixture: " + resource);
      }
      GitHubFixtureDocument document = objectMapper.readValue(stream, GitHubFixtureDocument.class);
      return document.toDomain();
    } catch (IOException error) {
      throw new IllegalStateException("Failed to parse GitHub mock fixture " + resource, error);
    }
  }

  /**
   * Drops any scenario whose ref begins with {@code TEST-} — the conventional prefix for
   * test-registered scenarios. Built-in fixtures (GH-101/102/103) are preserved.
   */
  public void clearTestScenarios() {
    int before = scenarios.size();
    scenarios.entrySet().removeIf(entry -> entry.getKey().startsWith(TEST_REF_PREFIX));
    // Also purge any test-seeded PRs (story 4.17 conflict-injection) keyed under the TEST- prefix
    // so
    // built-in fixtures (PR-101/102/103) survive but per-test PR state never leaks across
    // scenarios.
    pullRequestsByRef.keySet().removeIf(prRef -> prRef.startsWith(TEST_REF_PREFIX));
    int removed = before - scenarios.size();
    if (removed > 0) {
      log.debug("github_mock cleared {} test scenarios", removed);
    }
  }

  private static String branchKey(String repoRef, String branchName) {
    return repoRef + BRANCH_KEY_SEPARATOR + branchName;
  }
}
