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
 *   <li>{@code supportsCiStatusReads} — the host exposes a readable CI build verdict for a pushed
 *       commit ({@code readCheckRuns}). Story 3h-5 (FR79): {@code true} for GitHub (Actions /
 *       check-runs), {@code false} for the GitLab stub and (until story 3i-3 AC2 lands) Bitbucket.
 *       The scheduled CI-investigation sweep gates every poll on this flag.
 * </ul>
 */
public record RepositoryHostCapabilities(
    boolean supportsDraftPullRequests,
    boolean supportsPullRequestComments,
    boolean supportsBranchProtection,
    boolean supportsForkPushes,
    boolean supportsRequiredStatusChecks,
    boolean supportsCiStatusReads) {

  /**
   * The GitHub capability set — GitHub supports draft PRs, PR comments, branch protection, fork
   * pushes, required status checks, and CI status reads (story 3h-5), so all six are {@code true}
   * today.
   */
  public static RepositoryHostCapabilities githubDefaults() {
    return new RepositoryHostCapabilities(true, true, true, true, true, true);
  }

  /**
   * The Bitbucket Cloud capability set (story 3i-3 / FR82). Declared honestly against Bitbucket
   * Cloud's real feature surface, which differs from GitHub: Bitbucket Cloud has <strong>no
   * draft-pull-request concept</strong> ({@code supportsDraftPullRequests == false}), so the
   * Bitbucket adapter — unlike the GitHub adapter — never sends a {@code draft} flag on PR create.
   * It does support PR comments, branch restrictions (branch protection), fork pushes, and
   * merge/required-status checks.
   *
   * <p>{@code supportsCiStatusReads} (the Pipelines CI read) is {@code false} — story 3h-5 landed
   * the CI-checks port and this 6th capability component, but the Bitbucket <em>Pipelines</em>
   * reader is story 3i-3 AC2, split forward until it merges (Dev Notes §0). When 3i-3 AC2 lands it
   * flips to {@code true} here.
   */
  public static RepositoryHostCapabilities bitbucketDefaults() {
    return new RepositoryHostCapabilities(false, true, true, true, true, false);
  }
}
