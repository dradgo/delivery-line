package org.dradgo.domain.integration.repohost;

/**
 * Vendor-neutral outcome of {@code RepositoryHostAdapter.commentOnPullRequest} (story 3.33, AC1 /
 * OQ-3). Replaces the {@code void} return on the legacy {@code GitHubAdapter} comment method so
 * callers can observe the idempotency-replay no-op instead of a silent void. Mirrors {@code
 * CommentResult} on the ticket-source side (story 3.32).
 *
 * <p>The mock dedups on {@code (prRef, fingerprint(body))} and surfaces a replay as {@link
 * #SKIPPED_DUPLICATE}. The real GitHub adapter has <strong>no server-side comment dedup</strong> —
 * GitHub stacks duplicate issue comments — so it always returns {@link #POSTED} (documented
 * asymmetry, repository-host-extension-contract.md).
 */
public enum CommentResult {

  /** The comment was written to the pull request. */
  POSTED,

  /**
   * The same {@code (prRef, fingerprint)} was already recorded — the post was a no-op replay
   * (idempotency contract honored). Surfaced by the mock; never by the real GitHub adapter.
   */
  SKIPPED_DUPLICATE
}
