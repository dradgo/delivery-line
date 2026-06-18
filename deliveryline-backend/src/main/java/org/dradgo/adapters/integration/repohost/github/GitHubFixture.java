package org.dradgo.adapters.integration.repohost.github;

import java.util.Objects;
import org.dradgo.domain.integration.repohost.Branch;
import org.dradgo.domain.integration.repohost.PullRequest;
import org.dradgo.domain.integration.repohost.Repository;

/**
 * Adapter-internal bundle of the three domain records seeded by a single happy GitHub fixture file
 * (one repository, its single open PR, and the PR's source branch — story 3.13 AC3). Not exposed
 * through the {@link org.dradgo.application.integration.repohost.RepositoryHostAdapter} port
 * surface.
 */
record GitHubFixture(Repository repository, PullRequest pullRequest, Branch branch) {

  GitHubFixture {
    Objects.requireNonNull(repository, "repository");
    Objects.requireNonNull(pullRequest, "pullRequest");
    Objects.requireNonNull(branch, "branch");
  }
}
