package org.dradgo.domain.integration.repohost;

import java.util.Objects;

/**
 * Vendor-neutral, domain-shaped projection of a repository (renamed from story 3.13's {@code
 * GitHubRepository} and moved into the domain layer by story 3.33). Adapters translate a host's
 * REST/SDK response shape to this record — no host-specific types ({@code org.kohsuke.github} SDK
 * types, REST DTOs, HTTP-client surface) may surface here. Verified by the {@code
 * REPOSITORY_HOST_TYPES_MUST_NOT_LEAK_THROUGH_PORT} ArchUnit rule (story 3.33 AC7).
 *
 * <p>{@code repoRef} is the stable external reference callers pass to the {@code
 * RepositoryHostAdapter}; {@code fullName} is the {@code owner/name} slug; {@code defaultBranch} is
 * the repository's default branch (typically {@code main}); {@code url} is the clone URL.
 */
public record Repository(RepositoryRef repoRef, String fullName, String defaultBranch, String url) {

  public Repository {
    Objects.requireNonNull(repoRef, "repoRef");
    Objects.requireNonNull(fullName, "fullName");
    Objects.requireNonNull(defaultBranch, "defaultBranch");
    Objects.requireNonNull(url, "url");
  }
}
