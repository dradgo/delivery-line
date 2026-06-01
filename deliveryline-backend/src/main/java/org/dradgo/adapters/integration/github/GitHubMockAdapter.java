package org.dradgo.adapters.integration.github;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.dradgo.application.integration.github.GitHubAdapter;
import org.dradgo.application.integration.github.GitHubAdapterException;
import org.dradgo.application.integration.github.GitHubBranch;
import org.dradgo.application.integration.github.GitHubPullRequest;
import org.dradgo.application.integration.github.GitHubRepository;
import org.dradgo.domain.registry.IntegrationFailureCategory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Deterministic, fixture-backed {@link GitHubAdapter} implementation. Activated under Spring
 * profile {@code github-mock} — the default profile in {@code test}; opt-in for {@code
 * local}/{@code demo}. GitHub twin of {@code LinearMockAdapter} (story 1.14).
 *
 * <p>Determinism contract (AC2, AC8):
 *
 * <ul>
 *   <li>No randomness — every lookup is keyed to a ref via {@link GitHubMockScenarioRegistry};
 *       synthesized created-PR ids/numbers derive from a stable hash of their inputs.
 *   <li>No wall-clock dependence — fixture timestamps come from JSON; synthesized PRs carry a fixed
 *       {@link #SYNTHETIC_CREATED_AT}.
 *   <li>No network I/O — no {@code RestClient}, no {@code HttpClient}. Pinned by {@code
 *       GitHubProfileWiringContractTest} (AC6).
 * </ul>
 *
 * <p>Idempotency (AC8, Decision D3 — unlike the Linear mock which appended every call): {@link
 * #createPullRequest} dedupes on {@code (repoRef, sourceBranch)}; {@link #commentOnPullRequest}
 * dedupes on {@code (prRef, fingerprint(body))}. Re-calls return the existing record / are a no-op.
 * The {@link #createdPullRequests()} / {@link #postedComments()} accessors are test-only and NOT on
 * the {@link GitHubAdapter} port.
 */
@Component
@Profile("github-mock")
public class GitHubMockAdapter implements GitHubAdapter {

  private static final Logger log = LoggerFactory.getLogger(GitHubMockAdapter.class);

  /** Deterministic creation instant stamped on synthesized PRs (no wall-clock reads). */
  private static final Instant SYNTHETIC_CREATED_AT = Instant.parse("2026-01-01T00:00:00Z");

  private final GitHubMockScenarioRegistry registry;
  private final ConcurrentMap<CreatePullRequestKey, GitHubPullRequest> createdPullRequests =
      new ConcurrentHashMap<>();
  private final ConcurrentMap<CommentKey, PostedComment> postedComments = new ConcurrentHashMap<>();

  public GitHubMockAdapter(GitHubMockScenarioRegistry registry) {
    this.registry = Objects.requireNonNull(registry, "registry");
  }

  @Override
  public Optional<GitHubRepository> getRepositoryByRef(String repoRef) {
    Objects.requireNonNull(repoRef, "repoRef");
    throwIfAdversarial(repoRef, "getRepositoryByRef");
    Optional<GitHubRepository> repository = registry.findRepository(repoRef);
    log.info(
        "github_mock get_repository repoRef={} resolution={}",
        repoRef,
        repository.isPresent() ? "hit" : "empty");
    return repository;
  }

  @Override
  public Optional<GitHubPullRequest> getPullRequestByRef(String prRef) {
    Objects.requireNonNull(prRef, "prRef");
    Optional<GitHubMockScenario> scenario = registry.find(prRef);
    if (scenario.isPresent()
        && scenario.get().behaviour() == GitHubMockScenario.Behaviour.CONFLICT) {
      GitHubPullRequest conflicting = conflictPullRequest(prRef);
      log.warn(
          "github_mock get_pull_request prRef={} resolution=conflict conflictRepoRef={}",
          prRef,
          conflicting.repoRef());
      return Optional.of(conflicting);
    }
    throwIfAdversarial(prRef, "getPullRequestByRef");
    Optional<GitHubPullRequest> pullRequest = findAnyPullRequest(prRef);
    log.info(
        "github_mock get_pull_request prRef={} resolution={}",
        prRef,
        pullRequest.isPresent() ? "hit" : "empty");
    return pullRequest;
  }

  @Override
  public Optional<GitHubBranch> getBranchByRef(String repoRef, String branchName) {
    Objects.requireNonNull(repoRef, "repoRef");
    Objects.requireNonNull(branchName, "branchName");
    Optional<GitHubBranch> branch = registry.findBranch(repoRef, branchName);
    log.info(
        "github_mock get_branch repoRef={} branch={} resolution={}",
        repoRef,
        branchName,
        branch.isPresent() ? "hit" : "empty");
    return branch;
  }

  @Override
  public GitHubPullRequest createPullRequest(
      String repoRef, String branch, String title, String body) {
    Objects.requireNonNull(repoRef, "repoRef");
    Objects.requireNonNull(branch, "branch");
    // AC5: a protected source branch is rejected — the special branch ref is the injection key.
    throwIfAdversarial(branch, "createPullRequest");
    CreatePullRequestKey key = new CreatePullRequestKey(repoRef, branch);
    GitHubPullRequest existing = createdPullRequests.get(key);
    if (existing != null) {
      // AC8: idempotent replay — same (repoRef, branch) returns the already-created record.
      log.warn(
          "github_mock create_pull_request repoRef={} branch={} resolution=idempotent_replay prRef={}",
          repoRef,
          branch,
          existing.prRef());
      return existing;
    }
    GitHubPullRequest created = synthesizePullRequest(repoRef, branch);
    GitHubPullRequest raced = createdPullRequests.putIfAbsent(key, created);
    if (raced != null) {
      log.warn(
          "github_mock create_pull_request repoRef={} branch={} resolution=idempotent_replay prRef={}",
          repoRef,
          branch,
          raced.prRef());
      return raced;
    }
    log.info(
        "github_mock create_pull_request repoRef={} branch={} resolution=created prRef={} number={}",
        repoRef,
        branch,
        created.prRef(),
        created.number());
    return created;
  }

  @Override
  public GitHubPullRequest updatePullRequest(String prRef, String body) {
    Objects.requireNonNull(prRef, "prRef");
    throwIfAdversarial(prRef, "updatePullRequest");
    GitHubPullRequest pullRequest =
        findAnyPullRequest(prRef)
            .orElseThrow(
                () -> {
                  GitHubAdapterException failure =
                      new GitHubAdapterException(
                          IntegrationFailureCategory.GITHUB_PR_NOT_FOUND,
                          "github_mock updatePullRequest: no PR seeded for prRef=" + prRef);
                  log.warn(
                      "github_mock update_pull_request prRef={} resolution=simulated_failure category={}",
                      prRef,
                      failure.failureCategory().value());
                  return failure;
                });
    log.info("github_mock update_pull_request prRef={} resolution=hit", prRef);
    return pullRequest;
  }

  @Override
  public void commentOnPullRequest(String prRef, String body) {
    Objects.requireNonNull(prRef, "prRef");
    Objects.requireNonNull(body, "body");
    // AC5: pr-rate-limited / pr-403 injection keyed by prRef.
    throwIfAdversarial(prRef, "commentOnPullRequest");
    String fingerprint = fingerprint(body);
    CommentKey key = new CommentKey(prRef, fingerprint);
    if (postedComments.containsKey(key)) {
      // AC8: idempotent replay — same (prRef, fingerprint) is a no-op.
      log.warn(
          "github_mock comment_on_pull_request prRef={} fingerprint={} resolution=idempotent_replay",
          prRef,
          fingerprint);
      return;
    }
    PostedComment raced = postedComments.putIfAbsent(key, new PostedComment(prRef, fingerprint));
    if (raced != null) {
      log.warn(
          "github_mock comment_on_pull_request prRef={} fingerprint={} resolution=idempotent_replay",
          prRef,
          fingerprint);
      return;
    }
    log.info(
        "github_mock comment_on_pull_request prRef={} fingerprint={} resolution=recorded",
        prRef,
        fingerprint);
  }

  /**
   * Test-only accessor returning the deduped created-PR records. Returns an immutable snapshot. Not
   * part of the {@link GitHubAdapter} port — only adapter-scope tests should depend on it.
   */
  public List<GitHubPullRequest> createdPullRequests() {
    return List.copyOf(createdPullRequests.values());
  }

  /** Test-only accessor returning the deduped posted-comment records (immutable snapshot). */
  public List<PostedComment> postedComments() {
    return List.copyOf(postedComments.values());
  }

  /** Test-only utility — clear the recorded created PRs between scenarios. */
  public void clearCreatedPullRequests() {
    createdPullRequests.clear();
  }

  /** Test-only utility — clear the recorded comments between scenarios. */
  public void clearPostedComments() {
    postedComments.clear();
  }

  private Optional<GitHubPullRequest> findAnyPullRequest(String prRef) {
    Optional<GitHubPullRequest> seeded = registry.findPullRequest(prRef);
    if (seeded.isPresent()) {
      return seeded;
    }
    return createdPullRequests.values().stream()
        .filter(pullRequest -> pullRequest.prRef().equals(prRef))
        .findFirst();
  }

  private void throwIfAdversarial(String ref, String operation) {
    Optional<GitHubMockScenario> scenario = registry.find(ref);
    if (scenario.isEmpty()) {
      return;
    }
    switch (scenario.get().behaviour()) {
      case REPO_NOT_FOUND, PERMISSION_DENIED, RATE_LIMITED, BRANCH_PROTECTED -> {
        GitHubAdapterException failure = failure(scenario.get(), operation);
        log.warn(
            "github_mock {} ref={} resolution=simulated_failure category={}",
            operation,
            ref,
            failure.failureCategory().value());
        throw failure;
      }
      default -> {
        // HAPPY / NOT_FOUND / CONFLICT are not adversarial-throw behaviours here.
      }
    }
  }

  private GitHubPullRequest conflictPullRequest(String prRef) {
    return new GitHubPullRequest(
        prRef,
        GitHubMockScenarioRegistry.CONFLICT_PR_REPO_REF,
        999,
        "conflict/source-branch",
        "open",
        "https://github.com/" + GitHubMockScenarioRegistry.CONFLICT_PR_REPO_REF + "/pull/999",
        SYNTHETIC_CREATED_AT);
  }

  private GitHubPullRequest synthesizePullRequest(String repoRef, String branch) {
    int stable = (repoRef + "/" + branch).hashCode() & 0x7fffffff;
    int number = 1000 + (stable % 9000);
    String prRef = "PR-NEW-" + Integer.toHexString(stable);
    return new GitHubPullRequest(
        prRef,
        repoRef,
        number,
        branch,
        "open",
        "https://github.com/" + repoRef + "/pull/" + number,
        SYNTHETIC_CREATED_AT);
  }

  private static GitHubAdapterException failure(GitHubMockScenario scenario, String operation) {
    IntegrationFailureCategory category = scenario.expectedFailureCategory();
    if (category == null) {
      category =
          switch (scenario.behaviour()) {
            case REPO_NOT_FOUND -> IntegrationFailureCategory.GITHUB_REPO_NOT_FOUND;
            case PERMISSION_DENIED -> IntegrationFailureCategory.GITHUB_PERMISSION_DENIED;
            case RATE_LIMITED -> IntegrationFailureCategory.GITHUB_RATE_LIMITED;
            case BRANCH_PROTECTED -> IntegrationFailureCategory.GITHUB_BRANCH_PROTECTED;
            default -> IntegrationFailureCategory.SYNC_FAILURE;
          };
    }
    return new GitHubAdapterException(
        category,
        "github_mock "
            + operation
            + " simulated "
            + scenario.behaviour().name()
            + " for ref="
            + scenario.ref());
  }

  /**
   * Deterministic, body-content-free idempotency fingerprint. Uses the trimmed body's {@link
   * String#hashCode()} (specified, JVM-stable) rendered as hex — never the body bytes themselves,
   * so no PR content is logged or stored.
   */
  private static String fingerprint(String body) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(body.trim().getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hash);
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException("SHA-256 digest is unavailable", error);
    }
  }

  /**
   * In-memory record of a deduped {@link #commentOnPullRequest} call (content fingerprint only).
   */
  public record PostedComment(String prRef, String fingerprint) {}

  private record CreatePullRequestKey(String repoRef, String branch) {}

  private record CommentKey(String prRef, String fingerprint) {}
}
