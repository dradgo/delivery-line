package org.dradgo.application.integration.github;

import java.util.Objects;

/**
 * Domain-shaped projection of a GitHub branch. Adapters translate GitHub's REST response shape
 * (story 3.14) to this record — no GitHub-specific types may surface here. Verified by the {@code
 * GITHUB_TYPES_MUST_NOT_LEAK_THROUGH_PORT} ArchUnit rule (story 3.13 AC1/AC11).
 *
 * <p>{@code headSha} is the commit SHA at the branch tip; in the deterministic mock it is a stable
 * fixture value, never a live commit hash.
 */
public record GitHubBranch(String repoRef, String name, String headSha) {

  public GitHubBranch {
    if (repoRef == null || repoRef.isBlank()) {
      throw new IllegalArgumentException("repoRef must be non-blank");
    }
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("name must be non-blank");
    }
    Objects.requireNonNull(headSha, "headSha");
  }
}
