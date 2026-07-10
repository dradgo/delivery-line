package org.dradgo.adapters.integration.repohost.bitbucket;

import java.util.Objects;
import org.dradgo.domain.registry.IntegrationFailureCategory;

/**
 * Single deterministic behaviour for a Bitbucket mock entity, keyed by an external {@code ref} in
 * {@link BitbucketMockScenarioRegistry} (story 3i-3 / FR82). The Bitbucket twin of {@code
 * GitHubMockScenario}: the keyed ref is whichever ref the operation receives — a {@code repoRef}
 * for repository ops, a {@code prRef} for PR ops, a branch name for the branch-protected injection.
 *
 * <p>Only {@link Behaviour#HAPPY} scenarios load a fixture body; the {@link Behaviour#NOT_FOUND}
 * and the adversarial-failure branches simulate paths without loading a fixture. {@link
 * Behaviour#CONFLICT} returns a deterministically-synthesized conflicting PR and likewise needs no
 * fixture file.
 *
 * <p>{@code fixtureResource} is a classpath path (e.g. {@code
 * "bitbucket-fixtures/bitbucket-feature-low-risk.json"}) — production happy fixtures live under
 * {@code src/main/resources/bitbucket-fixtures/}; adversarial markers live under {@code
 * src/test/resources/bitbucket-fixtures/} so the latter never leak into the {@code demo}/{@code
 * local} runtime classpath.
 *
 * <p>{@code expectedFailureCategory} is the {@link IntegrationFailureCategory} the adapter/contract
 * tests expect to surface when the scenario fires — {@code null} for happy/not-found/conflict
 * scenarios.
 */
public record BitbucketMockScenario(
    String ref,
    Behaviour behaviour,
    String fixtureResource,
    IntegrationFailureCategory expectedFailureCategory) {

  public BitbucketMockScenario {
    Objects.requireNonNull(ref, "ref");
    Objects.requireNonNull(behaviour, "behaviour");
    if (behaviour == Behaviour.HAPPY) {
      Objects.requireNonNull(
          fixtureResource, "HAPPY scenarios must reference a fixture resource (ref=" + ref + ")");
    }
  }

  public enum Behaviour {
    /** Loads the fixture JSON and returns the seeded repository / PR / branch. */
    HAPPY,

    /** Lookups return {@link java.util.Optional#empty()} (ordinary absence). */
    NOT_FOUND,

    /** {@code getRepositoryByRef} throws {@code BITBUCKET_REPO_NOT_FOUND} (simulates HTTP 404). */
    REPO_NOT_FOUND,

    /** Throws {@code BITBUCKET_PERMISSION_DENIED} (simulates HTTP 403). */
    PERMISSION_DENIED,

    /** Throws {@code BITBUCKET_RATE_LIMITED} (simulates HTTP 429). */
    RATE_LIMITED,

    /**
     * {@code createPullRequest} against a protected branch throws {@code
     * BITBUCKET_BRANCH_PROTECTED}.
     */
    BRANCH_PROTECTED,

    /**
     * {@code getPullRequestByRef} returns a PR whose repository deliberately conflicts with the
     * repo the workflow linked — the {@code PR_REF_CONTEXT_MISMATCH} scenario.
     */
    CONFLICT
  }
}
