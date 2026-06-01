package org.dradgo.application.integration.github;

import java.time.Instant;
import java.util.Objects;

/**
 * Domain-shaped projection of a GitHub pull request. Adapters translate GitHub's REST response
 * shape (story 3.14) to this record — no GitHub-specific types may surface here. Verified by the
 * {@code GITHUB_TYPES_MUST_NOT_LEAK_THROUGH_PORT} ArchUnit rule (story 3.13 AC1/AC11).
 *
 * <p>{@code prRef} is the stable external reference (e.g. {@code "PR-101"}); {@code repoRef} ties
 * the PR back to its {@link GitHubRepository}; {@code number} is the GitHub PR number; {@code
 * sourceBranch} is the head branch the PR proposes to merge; {@code state} is the lifecycle state
 * (e.g. {@code "open"}). {@code createdAt} is a fixture-provided / deterministically-synthesized
 * instant — never a wall-clock read inside the mock.
 */
public record GitHubPullRequest(
    String prRef,
    String repoRef,
    int number,
    String sourceBranch,
    String state,
    String url,
    Instant createdAt) {

  public GitHubPullRequest {
    if (prRef == null || prRef.isBlank()) {
      throw new IllegalArgumentException("prRef must be non-blank");
    }
    if (repoRef == null || repoRef.isBlank()) {
      throw new IllegalArgumentException("repoRef must be non-blank");
    }
    if (sourceBranch == null || sourceBranch.isBlank()) {
      throw new IllegalArgumentException("sourceBranch must be non-blank");
    }
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(url, "url");
    Objects.requireNonNull(createdAt, "createdAt");
  }
}
