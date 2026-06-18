package org.dradgo.domain.integration.repohost;

/**
 * Vendor-neutral value wrapper for an external pull-request reference (the GitHub-shaped {@code
 * "owner/repo#number"}, or the mock's {@code "PR-…"} form). Replaces the bare {@code String prRef}
 * that story 3.13 threaded through the {@code GitHubAdapter} signatures (story 3.33 R2).
 *
 * <p>The wrapped {@link #value()} is the host-opaque reference token — only the implementing
 * adapter parses its internal shape (the GitHub adapter regex-parses {@code "owner/repo#number"}).
 * Neutral consumers treat it as an opaque non-blank key; {@code integration_links.external_ref}
 * persists {@link #value()} as a {@code String} (story 3.33 R2/R4). The opaque-token reading is the
 * GitHub analog of {@code TicketRef} (story 3.32).
 */
public record PullRequestRef(String value) {

  public PullRequestRef {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("PullRequestRef value must be non-blank");
    }
  }

  /** Factory mirroring the {@code SomeType.of(...)} idiom used across the domain value records. */
  public static PullRequestRef of(String value) {
    return new PullRequestRef(value);
  }
}
