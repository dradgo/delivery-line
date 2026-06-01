package org.dradgo.adapters.integration.github;

import java.util.Objects;
import org.dradgo.application.integration.github.GitHubBranch;
import org.dradgo.application.integration.github.GitHubPullRequest;
import org.dradgo.application.integration.github.GitHubRepository;

/**
 * Adapter-internal bundle of the three domain records seeded by a single happy GitHub fixture file
 * (one repository, its single open PR, and the PR's source branch — AC3). Not exposed through the
 * {@link org.dradgo.application.integration.github.GitHubAdapter} port surface.
 */
record GitHubFixture(
    GitHubRepository repository, GitHubPullRequest pullRequest, GitHubBranch branch) {

  GitHubFixture {
    Objects.requireNonNull(repository, "repository");
    Objects.requireNonNull(pullRequest, "pullRequest");
    Objects.requireNonNull(branch, "branch");
  }
}
