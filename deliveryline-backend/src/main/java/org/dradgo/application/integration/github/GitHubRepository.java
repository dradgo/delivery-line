package org.dradgo.application.integration.github;

import java.util.Objects;

/**
 * Domain-shaped projection of a GitHub repository. Adapters translate GitHub's REST response shape
 * (story 3.14) to this record — no GitHub-specific types ({@code org.kohsuke.github} SDK types,
 * REST DTOs, HTTP-client surface) may surface here. Verified by the {@code
 * GITHUB_TYPES_MUST_NOT_LEAK_THROUGH_PORT} ArchUnit rule (story 3.13 AC1/AC11).
 *
 * <p>{@code repoRef} is the stable external reference callers pass to {@link GitHubAdapter} (e.g.
 * {@code "GH-101"}); {@code fullName} is the {@code owner/name} slug; {@code defaultBranch} is the
 * repository's default branch (typically {@code main}).
 */
public record GitHubRepository(String repoRef, String fullName, String defaultBranch, String url) {

  public GitHubRepository {
    if (repoRef == null || repoRef.isBlank()) {
      throw new IllegalArgumentException("repoRef must be non-blank");
    }
    Objects.requireNonNull(fullName, "fullName");
    Objects.requireNonNull(defaultBranch, "defaultBranch");
    Objects.requireNonNull(url, "url");
  }
}
