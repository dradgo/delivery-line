package org.dradgo.adapters.integration.repohost.gitlab;

import java.util.Objects;
import java.util.Optional;
import org.dradgo.application.integration.repohost.RepositoryHostAdapter;
import org.dradgo.application.integration.repohost.RepositoryHostAdapterException;
import org.dradgo.domain.integration.repohost.Branch;
import org.dradgo.domain.integration.repohost.CommentResult;
import org.dradgo.domain.integration.repohost.PullRequest;
import org.dradgo.domain.integration.repohost.PullRequestRef;
import org.dradgo.domain.integration.repohost.Repository;
import org.dradgo.domain.integration.repohost.RepositoryHostCapabilities;
import org.dradgo.domain.integration.repohost.RepositoryRef;
import org.dradgo.domain.registry.ConnectorKind;
import org.dradgo.domain.registry.IntegrationFailureCategory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Documented stub proving the per-project connector seam (story 3c-3 AC8) — the repository-host
 * twin of {@code GitLabTicketSourceStubAdapter}. Declares {@code connectorKind() == GITLAB} so the
 * {@code ProjectConnectorResolver} repository-host map genuinely contains a second entry beside the
 * active vendor, making per-kind resolution a real lookup.
 *
 * <p>Always-on ({@code @Component}, no {@code @Profile}); single-injection consumers stay
 * unambiguous because the active vendor adapter carries {@code @Primary}. Read methods return safe
 * empties; the two write methods that must return a non-{@code Optional} {@link PullRequest} take a
 * <em>typed</em> not-implemented path ({@link RepositoryHostAdapterException}) rather than
 * fabricating a misleading PR. It reports a <strong>deliberately degraded</strong> capability set
 * ({@code supportsPullRequestComments=false}) so AC4's degradation path is exercised by a real
 * registered kind. A real GitLab repository-host implementation is post-pilot — this class imports
 * no vendor SDK (nothing to leak).
 */
@Component
public class GitLabRepositoryHostStubAdapter implements RepositoryHostAdapter {

  private static final Logger log = LoggerFactory.getLogger(GitLabRepositoryHostStubAdapter.class);

  @Override
  public ConnectorKind connectorKind() {
    return ConnectorKind.GITLAB;
  }

  @Override
  public Optional<Repository> getRepositoryByRef(RepositoryRef ref) {
    Objects.requireNonNull(ref, "ref");
    log.info("gitlab stub: getRepositoryByRef is a documented no-op repoRef={}", ref.value());
    return Optional.empty();
  }

  @Override
  public Optional<PullRequest> getPullRequestByRef(PullRequestRef ref) {
    Objects.requireNonNull(ref, "ref");
    log.info("gitlab stub: getPullRequestByRef is a documented no-op prRef={}", ref.value());
    return Optional.empty();
  }

  @Override
  public Optional<Branch> getBranchByRef(RepositoryRef repo, String branchName) {
    Objects.requireNonNull(repo, "repo");
    Objects.requireNonNull(branchName, "branchName");
    log.info(
        "gitlab stub: getBranchByRef is a documented no-op repoRef={} branch={}",
        repo.value(),
        branchName);
    return Optional.empty();
  }

  @Override
  public PullRequest createPullRequest(
      RepositoryRef repo, String sourceBranch, String targetBranch, String title, String body) {
    Objects.requireNonNull(repo, "repo");
    throw notImplemented("createPullRequest", repo.value());
  }

  @Override
  public PullRequest updatePullRequest(PullRequestRef ref, String body) {
    Objects.requireNonNull(ref, "ref");
    throw notImplemented("updatePullRequest", ref.value());
  }

  @Override
  public CommentResult commentOnPullRequest(PullRequestRef ref, String body) {
    Objects.requireNonNull(ref, "ref");
    // supportsPullRequestComments=false, so capability-gating consumers never reach here; a
    // non-gating caller gets a no-op SKIPPED_DUPLICATE (never writes).
    log.info("gitlab stub: commentOnPullRequest is a documented no-op prRef={}", ref.value());
    return CommentResult.SKIPPED_DUPLICATE;
  }

  @Override
  public RepositoryHostCapabilities getCapabilities() {
    // Deliberately degraded — all optional features unsupported (exercises AC4 degradation).
    return new RepositoryHostCapabilities(false, false, false, false, false);
  }

  private RepositoryHostAdapterException notImplemented(String operation, String ref) {
    log.warn(
        "gitlab stub: {} is not implemented (documented stub, 3c-3 AC8) ref={}", operation, ref);
    return new RepositoryHostAdapterException(
        IntegrationFailureCategory.SYNC_FAILURE,
        "gitlab stub: " + operation + " is not implemented (documented stub, 3c-3 AC8)");
  }
}
