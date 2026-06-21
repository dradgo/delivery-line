package org.dradgo.application.integration.repohost;

import java.util.Optional;
import org.dradgo.application.integration.ConnectivityResult;
import org.dradgo.domain.integration.repohost.Branch;
import org.dradgo.domain.integration.repohost.CommentResult;
import org.dradgo.domain.integration.repohost.PullRequest;
import org.dradgo.domain.integration.repohost.PullRequestRef;
import org.dradgo.domain.integration.repohost.Repository;
import org.dradgo.domain.integration.repohost.RepositoryHostCapabilities;
import org.dradgo.domain.integration.repohost.RepositoryRef;
import org.dradgo.domain.registry.ConnectorKind;

/**
 * Application-owned, vendor-neutral port for a repository-host integration (story 3.33 — extracted
 * from the GitHub-shaped {@code GitHubAdapter} of story 3.13). The port carries only domain-shaped
 * methods — host-specific transport (GitHub REST DTOs, the {@code org.kohsuke.github} SDK, PAT auth
 * tokens, HTTP clients) must not leak through. Verified by the {@code
 * REPOSITORY_HOST_TYPES_MUST_NOT_LEAK_THROUGH_PORT} ArchUnit rule.
 *
 * <p>Concrete implementations are host-specific and profile-activated and live under {@code
 * org.dradgo.adapters.integration.repohost.{kind}} (today: {@code ...repohost.github} with {@code
 * GitHubMockAdapter} / {@code GitHubRealAdapter}). A future repository host (Bitbucket, GitLab,
 * Gitea, Azure DevOps Repos) is a one-interface, one-contract add — see {@code
 * docs/integrations/repository-host-extension-contract.md}.
 *
 * <p>Switching the active profile from {@code github-mock} to {@code github-real} activates the
 * real implementation against this same interface with zero orchestration-code change.
 *
 * <p>Refs are carried as opaque {@link RepositoryRef} / {@link PullRequestRef} value records (story
 * 3.33 R2/R4 / OQ-1): a repo ref is the GitHub-shaped {@code "owner/repo"} and a PR ref is {@code
 * "owner/repo#number"}, but the port treats them as vendor-uninterpreted tokens — only the GitHub
 * implementation parses their internal shape.
 */
public interface RepositoryHostAdapter {

  /**
   * Declare which {@link ConnectorKind} this adapter serves — the key {@code
   * ProjectConnectorResolver} selects on (story 3c-3). {@link ConnectorKind} is a {@code
   * domain.registry} type, so returning it through the port introduces no vendor leak (satisfies
   * {@code REPOSITORY_HOST_TYPES_MUST_NOT_LEAK_THROUGH_PORT}).
   */
  ConnectorKind connectorKind();

  /**
   * Look up a repository by its external reference. Returns empty when the repository is not seeded
   * at the host — ordinary absence is signalled by {@link Optional#empty()}, not an exception.
   * Deliberate failure-injection refs (story 3.13 AC5) instead throw a {@link
   * RepositoryHostAdapterException} carrying the matching {@code IntegrationFailureCategory}.
   */
  Optional<Repository> getRepositoryByRef(RepositoryRef ref);

  /**
   * Look up a pull request by its external reference. Returns empty for ordinary absence; throws a
   * classified {@link RepositoryHostAdapterException} for failure-injection refs and returns a
   * deliberately-conflicting PR for the conflict ref (story 3.13 AC4).
   */
  Optional<PullRequest> getPullRequestByRef(PullRequestRef ref);

  /** Look up a branch within a repository. Returns empty when the branch is not seeded. */
  Optional<Branch> getBranchByRef(RepositoryRef repo, String branchName);

  /**
   * Create a pull request for {@code sourceBranch} in {@code repo}, targeting {@code targetBranch}.
   * Idempotent on {@code (repo, sourceBranch, targetBranch)} — re-creating a PR for the same source
   * branch returns the already-created record rather than stacking a duplicate (story 3.13 AC8 /
   * story 3.14 AC4). Returns the affected {@link PullRequest}.
   *
   * <p>{@code targetBranch} is the base branch the PR merges into. A {@code null}/blank value means
   * "use the repository's default branch" — the back-compat path that preserves the legacy
   * default-branch-resolved behavior for callers (e.g. the prepare-time-stamped default branch is
   * unavailable). When non-blank it is honored verbatim, making the port honest for hosts where the
   * base differs from the default branch (story 3.33 R1 / OQ-2).
   */
  PullRequest createPullRequest(
      RepositoryRef repo, String sourceBranch, String targetBranch, String title, String body);

  /** Update an existing pull request's body, returning the affected {@link PullRequest}. */
  PullRequest updatePullRequest(PullRequestRef ref, String body);

  /**
   * Post a comment on a pull request. The mock is idempotent on {@code (prRef, fingerprint(body))}
   * — re-posting the same content on the same PR is a no-op returning {@link
   * CommentResult#SKIPPED_DUPLICATE}; a fresh write returns {@link CommentResult#POSTED}. The real
   * GitHub adapter has no server-side comment dedup and always returns {@link CommentResult#POSTED}
   * (documented asymmetry). The {@code body} is expected to have already been through the redaction
   * policy on hosts that redact on egress; the adapter applies redaction where required (story 3.14
   * AC3/AC6).
   *
   * <p>Optional operation: consumers should check {@link
   * RepositoryHostCapabilities#supportsPullRequestComments()} before invoking and gracefully
   * degrade when the host does not support comment posting.
   */
  CommentResult commentOnPullRequest(PullRequestRef ref, String body);

  /**
   * Declare which optional operations this repository host supports (story 3.33 AC3). Consuming
   * services gate optional calls (e.g. comment posting) on the relevant capability flag.
   */
  RepositoryHostCapabilities getCapabilities();

  /**
   * Story 3c-8 (AC3 / R1) — a lightweight authenticated reachability probe for the project
   * test-connection surface. Performs at most one cheap authenticated call: when {@code repo} is
   * non-null it probes that repository's metadata (so {@link ConnectivityResult#reachable()}
   * answers "is this repo reachable"); when {@code repo} is null it falls back to an authenticated
   * "whoami" call (host reachability + credential validity only). Network/auth failures are
   * classified into the returned secret-free {@link ConnectivityResult} — never thrown as a vendor
   * exception across the port. Mock / stub adapters return a deterministic reachable +
   * authenticated result.
   *
   * @param credentialOverride the project-scoped stored credential to authenticate the probe with;
   *     when {@code null}/blank the adapter falls back to its host-env credential (AC3). The value
   *     is a secret — it is used only for this one call and MUST NOT be logged, returned, or
   *     persisted.
   */
  ConnectivityResult verifyConnectivity(RepositoryRef repo, String credentialOverride);
}
