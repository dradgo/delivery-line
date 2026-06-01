package org.dradgo.application.integration.github;

import java.util.Optional;

/**
 * Application-owned port for the GitHub PR/branch integration. The port carries only domain-shaped
 * methods — GitHub-specific transport (REST DTOs, {@code org.kohsuke.github} SDK types, PAT auth
 * tokens, HTTP clients) must not leak through. Verified by the {@code
 * GITHUB_TYPES_MUST_NOT_LEAK_THROUGH_PORT} ArchUnit rule (story 3.13 Task 6), mirroring the Linear
 * precedent ({@code LINEAR_TYPES_MUST_NOT_LEAK_THROUGH_PORT}).
 *
 * <p>Two implementations exist, profile-activated:
 *
 * <ul>
 *   <li>{@code GitHubMockAdapter} ({@code @Profile("github-mock")}) — deterministic,
 *       fixture-backed, zero network calls. Default in {@code test}; opt-in for {@code local} /
 *       {@code demo}.
 *   <li>{@code GitHubRealAdapter} ({@code @Profile("github-real")}) — GitHub REST v3 + PAT auth
 *       (story 3.14, not yet implemented). Opt-in only; never in any default profile group.
 * </ul>
 *
 * <p>Switching the active profile from {@code github-mock} to {@code github-real} activates the
 * real implementation against this same interface with zero orchestration-code change (AC7).
 *
 * <p>Refs are plain {@link String}s (mirrors Linear's {@code String ticketRef} house style and the
 * string-literal error-injection refs in AC5) — no {@code RepositoryRef}/{@code PullRequestRef}
 * wrapper records.
 */
public interface GitHubAdapter {

  /**
   * Look up a repository by its external reference. Returns empty when the repository is not seeded
   * at the source — ordinary absence is signalled by {@link Optional#empty()}, not an exception
   * (mirrors Linear's "not found returns empty"). Deliberate failure-injection refs (story 3.13
   * AC5) instead throw a {@link GitHubAdapterException} carrying the matching {@code
   * IntegrationFailureCategory}.
   */
  Optional<GitHubRepository> getRepositoryByRef(String repoRef);

  /**
   * Look up a pull request by its external reference. Returns empty for ordinary absence; throws a
   * classified {@link GitHubAdapterException} for failure-injection refs (AC5) and returns a
   * deliberately-conflicting PR for the conflict ref (AC4).
   */
  Optional<GitHubPullRequest> getPullRequestByRef(String prRef);

  /** Look up a branch within a repository. Returns empty when the branch is not seeded. */
  Optional<GitHubBranch> getBranchByRef(String repoRef, String branchName);

  /**
   * Create a pull request for {@code branch} in {@code repoRef}. Idempotent on {@code (repoRef,
   * branch)} — re-creating a PR for the same source branch returns the already-created record
   * rather than stacking a duplicate (AC8). Returns the affected {@link GitHubPullRequest}.
   */
  GitHubPullRequest createPullRequest(String repoRef, String branch, String title, String body);

  /** Update an existing pull request's body, returning the affected {@link GitHubPullRequest}. */
  GitHubPullRequest updatePullRequest(String prRef, String body);

  /**
   * Post a comment on a pull request. Idempotent on {@code (prRef, fingerprint(body))} — re-posting
   * the same content on the same PR is a no-op rather than stacking a duplicate (AC8). The {@code
   * body} is expected to have already been through the redaction policy; the adapter does not
   * redact (and the mock never sends it anywhere — redaction-on-egress is story 3.14's concern).
   */
  void commentOnPullRequest(String prRef, String body);
}
