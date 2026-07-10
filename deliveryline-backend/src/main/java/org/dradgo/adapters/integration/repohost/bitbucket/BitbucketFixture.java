package org.dradgo.adapters.integration.repohost.bitbucket;

import java.util.Objects;
import org.dradgo.domain.integration.repohost.Branch;
import org.dradgo.domain.integration.repohost.PullRequest;
import org.dradgo.domain.integration.repohost.Repository;

/**
 * Adapter-internal bundle of the three domain records seeded by a single happy Bitbucket fixture
 * file (one repository, its single open PR, and the PR's source branch — story 3i-3 / FR82). Not
 * exposed through the {@link org.dradgo.application.integration.repohost.RepositoryHostAdapter}
 * port surface. Bitbucket twin of {@code GitHubFixture}.
 */
record BitbucketFixture(Repository repository, PullRequest pullRequest, Branch branch) {

  BitbucketFixture {
    Objects.requireNonNull(repository, "repository");
    Objects.requireNonNull(pullRequest, "pullRequest");
    Objects.requireNonNull(branch, "branch");
  }
}
