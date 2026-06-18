package org.dradgo.domain.integration.repohost;

import java.time.Instant;
import java.util.Objects;

/**
 * Vendor-neutral, domain-shaped projection of a pull request (renamed from story 3.13's {@code
 * GitHubPullRequest} and moved into the domain layer by story 3.33). Adapters translate a host's
 * REST/SDK response shape to this record — no host-specific types may surface here. Verified by the
 * {@code REPOSITORY_HOST_TYPES_MUST_NOT_LEAK_THROUGH_PORT} ArchUnit rule (story 3.33 AC7).
 *
 * <p>{@code prRef} is the stable external reference; {@code repoRef} ties the PR back to its {@link
 * Repository}; {@code number} is the generic PR/MR number (Bitbucket/GitLab also number them — not
 * GitHub-specific); {@code sourceBranch} is the head branch the PR proposes to merge; {@code state}
 * is the lifecycle state (e.g. {@code "open"}); {@code createdAt} is a host-provided /
 * deterministically-synthesized instant — never a wall-clock read inside the mock.
 */
public record PullRequest(
    PullRequestRef prRef,
    RepositoryRef repoRef,
    int number,
    String sourceBranch,
    String state,
    String url,
    Instant createdAt) {

  public PullRequest {
    Objects.requireNonNull(prRef, "prRef");
    Objects.requireNonNull(repoRef, "repoRef");
    if (sourceBranch == null || sourceBranch.isBlank()) {
      throw new IllegalArgumentException("sourceBranch must be non-blank");
    }
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(url, "url");
    Objects.requireNonNull(createdAt, "createdAt");
  }
}
