package org.dradgo.domain.integration.repohost;

/**
 * Vendor-neutral value wrapper for an external repository reference (e.g. the GitHub-shaped {@code
 * "owner/repo"}). Replaces the bare {@code String repoRef} that story 3.13 threaded through every
 * {@code GitHubAdapter} signature so a future repository host (Bitbucket, GitLab, Gitea, Azure
 * DevOps Repos) speaks the same domain vocabulary (story 3.33 R2).
 *
 * <p>The wrapped {@link #value()} is the host-opaque reference token — the implementing adapter
 * (and only the adapter) interprets its internal shape (the GitHub adapter parses {@code
 * "owner/repo"}). Neutral consumers treat it as an opaque non-blank string and map {@link #value()}
 * at the persistence boundary ({@code integration_links.external_ref} stays a {@code String}). This
 * is the exact analog of {@code TicketRef}'s opaque-token resolution (story 3.32).
 */
public record RepositoryRef(String value) {

  public RepositoryRef {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("RepositoryRef value must be non-blank");
    }
  }

  /** Factory mirroring the {@code SomeType.of(...)} idiom used across the domain value records. */
  public static RepositoryRef of(String value) {
    return new RepositoryRef(value);
  }
}
