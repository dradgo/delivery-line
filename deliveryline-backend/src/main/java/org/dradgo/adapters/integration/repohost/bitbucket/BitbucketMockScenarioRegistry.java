package org.dradgo.adapters.integration.repohost.bitbucket;

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
 * In-memory deterministic registry of {@link BitbucketMockScenario} entries keyed by external
 * {@code ref} (story 3i-3 / FR82). Bitbucket twin of {@code GitHubMockScenarioRegistry}.
 * Production- classpath happy fixtures live under {@code src/main/resources/bitbucket-fixtures/};
 * tests may register adversarial / conflict scenarios via {@link #register(BitbucketMockScenario)}
 * (cleanup with {@link #clearTestScenarios()}).
 *
 * <p>The three happy fixtures are loaded eagerly at construction into per-entity indexes so the
 * adapter can resolve repositories by {@code repoRef}, PRs by {@code prRef}, and branches by {@code
 * (repoRef, name)}. Profile-activated by {@code bitbucket-mock} so the bean only loads when the
 * mock Bitbucket stack is wired.
 *
 * <p>Determinism note: fixture JSON files contain absolute {@code createdAt} instants and stable
 * SHAs/IDs — no wall-clock arithmetic happens inside the registry.
 */
@Component
@Profile("bitbucket-mock")
public class BitbucketMockScenarioRegistry {

  private static final Logger log = LoggerFactory.getLogger(BitbucketMockScenarioRegistry.class);

  private static final String CLASSPATH_PREFIX = "bitbucket-fixtures/";
  private static final String TEST_REF_PREFIX = "TEST-";
  private static final String BRANCH_KEY_SEPARATOR = "\u0000";

  /** Stable repository references for the three production fixtures. */
  public static final String REPO_FEATURE_LOW_RISK = "BB-101";

  public static final String REPO_BUG_FIX = "BB-102";
  public static final String REPO_DOCS = "BB-103";

  /** Stable PR references for the three production fixtures (one open PR per repo). */
  public static final String PR_FEATURE_LOW_RISK = "PR-101";

  public static final String PR_BUG_FIX = "PR-102";
  public static final String PR_DOCS = "PR-103";

  /** Stable source-branch names for the three production fixtures (the PR's source branch). */
  public static final String BRANCH_FEATURE_LOW_RISK = "feature/healthz-endpoint";

  public static final String BRANCH_BUG_FIX = "fix/pagination-off-by-one";
  public static final String BRANCH_DOCS = "docs/recovery-runbook";

  /**
   * Stable refs for the documented adversarial / conflict scenarios (registered by tests via {@link
   * #register(BitbucketMockScenario)} — never auto-loaded into a demo/local runtime).
   */
  public static final String REF_REPO_NOT_FOUND = "repo-not-found";

  public static final String REF_PR_PERMISSION_DENIED = "pr-403";
  public static final String REF_PR_RATE_LIMITED = "pr-rate-limited";
  public static final String REF_BRANCH_PROTECTED = "protected-branch";
  public static final String REF_PR_CONFLICT = "PR-conflict";

  /**
   * The repository a {@link BitbucketMockScenario.Behaviour#CONFLICT} PR deliberately points at —
   * it differs from any seeded happy repo so callers detect a {@code PR_REF_CONTEXT_MISMATCH}.
   */
  public static final String CONFLICT_PR_REPO_REF = "BB-999-unrelated";

  private final ConcurrentMap<String, BitbucketMockScenario> scenarios = new ConcurrentHashMap<>();
  private final ConcurrentMap<String, Repository> repositoriesByRef = new ConcurrentHashMap<>();
  private final ConcurrentMap<String, PullRequest> pullRequestsByRef = new ConcurrentHashMap<>();
  private final ConcurrentMap<String, Branch> branchesByKey = new ConcurrentHashMap<>();
  private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

  public BitbucketMockScenarioRegistry() {
    registerHappyDefault(REPO_FEATURE_LOW_RISK, "bitbucket-feature-low-risk.json");
    registerHappyDefault(REPO_BUG_FIX, "bitbucket-bug-fix.json");
    registerHappyDefault(REPO_DOCS, "bitbucket-docs.json");
    registerDefault(
        REF_REPO_NOT_FOUND,
        BitbucketMockScenario.Behaviour.REPO_NOT_FOUND,
        null,
        IntegrationFailureCategory.BITBUCKET_REPO_NOT_FOUND);
    registerDefault(
        REF_PR_PERMISSION_DENIED,
        BitbucketMockScenario.Behaviour.PERMISSION_DENIED,
        null,
        IntegrationFailureCategory.BITBUCKET_PERMISSION_DENIED);
    registerDefault(
        REF_PR_RATE_LIMITED,
        BitbucketMockScenario.Behaviour.RATE_LIMITED,
        null,
        IntegrationFailureCategory.BITBUCKET_RATE_LIMITED);
    registerDefault(
        REF_BRANCH_PROTECTED,
        BitbucketMockScenario.Behaviour.BRANCH_PROTECTED,
        null,
        IntegrationFailureCategory.BITBUCKET_BRANCH_PROTECTED);
    registerDefault(REF_PR_CONFLICT, BitbucketMockScenario.Behaviour.CONFLICT, null, null);
  }

  private void registerHappyDefault(String repoRef, String fixtureFilename) {
    String resource = CLASSPATH_PREFIX + fixtureFilename;
    BitbucketMockScenario scenario =
        registerDefault(repoRef, BitbucketMockScenario.Behaviour.HAPPY, resource, null);
    BitbucketFixture fixture = loadHappyFixture(scenario);
    repositoriesByRef.put(fixture.repository().repoRef().value(), fixture.repository());
    pullRequestsByRef.put(fixture.pullRequest().prRef().value(), fixture.pullRequest());
    branchesByKey.put(
        branchKey(fixture.branch().repoRef().value(), fixture.branch().name()), fixture.branch());
    log.debug(
        "bitbucket_mock fixture_loaded repoRef={} prRef={} branch={}",
        fixture.repository().repoRef().value(),
        fixture.pullRequest().prRef().value(),
        fixture.branch().name());
  }

  private BitbucketMockScenario registerDefault(
      String ref,
      BitbucketMockScenario.Behaviour behaviour,
      String fixtureResource,
      IntegrationFailureCategory expectedFailureCategory) {
    BitbucketMockScenario scenario =
        new BitbucketMockScenario(ref, behaviour, fixtureResource, expectedFailureCategory);
    scenarios.put(ref, scenario);
    return scenario;
  }

  public void register(BitbucketMockScenario scenario) {
    Objects.requireNonNull(scenario, "scenario");
    scenarios.put(scenario.ref(), scenario);
  }

  /**
   * Test-only: seed (or replace) a PR at a specific external ref. Purged by {@link
   * #clearTestScenarios} when the prRef begins with {@code TEST-}.
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

  public Optional<BitbucketMockScenario> find(String ref) {
    return Optional.ofNullable(scenarios.get(ref));
  }

  public Map<String, BitbucketMockScenario> all() {
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
   * Loads the fixture JSON for a {@link BitbucketMockScenario.Behaviour#HAPPY} scenario and returns
   * the deserialized {@link BitbucketFixture}. Throws {@link IllegalStateException} for scenarios
   * without a fixture (callers must check the behaviour first).
   */
  public BitbucketFixture loadHappyFixture(BitbucketMockScenario scenario) {
    Objects.requireNonNull(scenario, "scenario");
    if (scenario.behaviour() != BitbucketMockScenario.Behaviour.HAPPY) {
      throw new IllegalStateException(
          "loadHappyFixture only supports HAPPY scenarios (ref=" + scenario.ref() + ")");
    }
    String resource = scenario.fixtureResource();
    try (InputStream stream =
        Thread.currentThread().getContextClassLoader().getResourceAsStream(resource)) {
      if (stream == null) {
        log.warn("bitbucket_mock fixture_missing ref={} resource={}", scenario.ref(), resource);
        throw new IllegalStateException("Missing Bitbucket mock fixture: " + resource);
      }
      BitbucketFixtureDocument document =
          objectMapper.readValue(stream, BitbucketFixtureDocument.class);
      return document.toDomain();
    } catch (IOException error) {
      throw new IllegalStateException("Failed to parse Bitbucket mock fixture " + resource, error);
    }
  }

  /**
   * Drops any scenario whose ref begins with {@code TEST-} — the conventional prefix for
   * test-registered scenarios. Built-in fixtures (BB-101/102/103) are preserved.
   */
  public void clearTestScenarios() {
    int before = scenarios.size();
    scenarios.entrySet().removeIf(entry -> entry.getKey().startsWith(TEST_REF_PREFIX));
    pullRequestsByRef.keySet().removeIf(prRef -> prRef.startsWith(TEST_REF_PREFIX));
    int removed = before - scenarios.size();
    if (removed > 0) {
      log.debug("bitbucket_mock cleared {} test scenarios", removed);
    }
  }

  private static String branchKey(String repoRef, String branchName) {
    return repoRef + BRANCH_KEY_SEPARATOR + branchName;
  }
}
