package org.dradgo.domain.integration.repohost;

/**
 * Vendor-neutral capability declaration for a {@code RepositoryHostAdapter} (story 3.33 AC3). Not
 * every repository host supports every optional feature — a host may not expose draft PRs, PR
 * comments, branch protection, fork pushes, or required status checks. Consuming services check the
 * relevant flag before invoking an optional operation and gracefully degrade when it is
 * unsupported. Mirrors {@code TicketSourceCapabilities} (story 3.32).
 *
 * <ul>
 *   <li>{@code supportsDraftPullRequests} — the host can open a PR in draft state. Declared for
 *       future consumers; the current {@code createPullRequest} has no draft parameter and no
 *       production caller asks for one (story 3.33 R5 / OQ-5 — vacuously satisfied today).
 *   <li>{@code supportsPullRequestComments} — the host can post a comment on a PR ({@code
 *       commentOnPullRequest}). The single optional feature a consumer could gate today.
 *   <li>{@code supportsBranchProtection} — the host enforces branch-protection rules.
 *   <li>{@code supportsForkPushes} — the host accepts pushes from forks.
 *   <li>{@code supportsRequiredStatusChecks} — the host enforces required status checks before
 *       merge.
 * </ul>
 */
public record RepositoryHostCapabilities(
    boolean supportsDraftPullRequests,
    boolean supportsPullRequestComments,
    boolean supportsBranchProtection,
    boolean supportsForkPushes,
    boolean supportsRequiredStatusChecks) {

  /**
   * The GitHub capability set — GitHub supports draft PRs, PR comments, branch protection, fork
   * pushes, and required status checks, so all five are {@code true} today.
   */
  public static RepositoryHostCapabilities githubDefaults() {
    return new RepositoryHostCapabilities(true, true, true, true, true);
  }
}
