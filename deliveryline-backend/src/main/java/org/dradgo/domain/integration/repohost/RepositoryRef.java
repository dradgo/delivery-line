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

  /**
   * Normalize an {@code owner/repo} slug, an HTTPS clone URL ({@code
   * https://host/owner/repo(.git)}), or an SSH SCP URL ({@code git@host:owner/repo(.git)}) to the
   * bare {@code owner/repo} reference, or {@code null} when {@code url} is null/blank or normalizes
   * to blank. Shared by {@code WorkflowProperties.RepoConfig.repositoryRef()} (the global
   * single-repo config) and {@code ProjectConnectorResolver.assertRepositoryRefMatchesProject} (the
   * per-project binding, story 3c-3 AC5) so both derive an identical ref from the same input shapes
   * rather than duplicating the normalization.
   *
   * <p>The result is idempotent: a value already in bare {@code owner/repo} form normalizes to
   * itself. Trailing slashes are trimmed (so {@code owner/repo/} and {@code owner/repo} agree); the
   * AC5 guard pairs this with a case-insensitive compare since host repo identity is case-folding.
   */
  public static String normalizeRepositoryUrl(String url) {
    if (url == null || url.isBlank()) {
      return null;
    }
    String ref = url.trim();
    if (ref.endsWith(".git")) {
      ref = ref.substring(0, ref.length() - ".git".length());
    }
    int scheme = ref.indexOf("://");
    if (scheme >= 0) {
      // https://host/owner/repo or ssh://git@host/owner/repo — strip scheme + host.
      String afterHost = ref.substring(scheme + 3);
      int slash = afterHost.indexOf('/');
      ref = slash >= 0 ? afterHost.substring(slash + 1) : afterHost;
    } else {
      int colon = ref.indexOf(':');
      if (colon >= 0) {
        // scp-like git@host:owner/repo — strip user@host.
        ref = ref.substring(colon + 1);
      }
    }
    while (ref.startsWith("/")) {
      ref = ref.substring(1);
    }
    while (ref.endsWith("/")) {
      ref = ref.substring(0, ref.length() - 1);
    }
    return ref.isBlank() ? null : ref;
  }
}
