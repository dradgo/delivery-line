package org.dradgo.domain.integration.repohost;

import java.util.Objects;

/**
 * Vendor-neutral, domain-shaped projection of a branch (renamed from story 3.13's {@code
 * GitHubBranch} and moved into the domain layer by story 3.33). Adapters translate a host's
 * REST/SDK response shape to this record — no host-specific types may surface here. Verified by the
 * {@code REPOSITORY_HOST_TYPES_MUST_NOT_LEAK_THROUGH_PORT} ArchUnit rule (story 3.33 AC7).
 *
 * <p>{@code headSha} is the commit SHA at the branch tip; in the deterministic mock it is a stable
 * fixture value, never a live commit hash.
 */
public record Branch(RepositoryRef repoRef, String name, String headSha) {

  public Branch {
    Objects.requireNonNull(repoRef, "repoRef");
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("name must be non-blank");
    }
    Objects.requireNonNull(headSha, "headSha");
  }
}
