package org.dradgo.adapters.integration.repohost.github;

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
import org.dradgo.application.integration.ConnectivityResult;
import org.dradgo.application.integration.repohost.RepositoryHostAdapter;
import org.dradgo.application.integration.repohost.RepositoryHostAdapterException;
import org.dradgo.domain.integration.repohost.Branch;
import org.dradgo.domain.integration.repohost.CiCheck;
import org.dradgo.domain.integration.repohost.CiConclusion;
import org.dradgo.domain.integration.repohost.CiStatus;
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
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Deterministic, fixture-backed {@link RepositoryHostAdapter} implementation (the GitHub kind).
 * Activated under Spring profile {@code github-mock} — the default profile in {@code test}; opt-in
 * for {@code local}/{@code demo}. GitHub twin of {@code LinearMockAdapter} (story 1.14); extracted
 * to the vendor-neutral port by story 3.33.
 *
 * <p>Determinism contract (story 3.13 AC2, AC8):
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
 * <p>Idempotency (AC8): {@link #createPullRequest} dedupes on {@code (repoRef, sourceBranch,
 * targetBranch)}; {@link #commentOnPullRequest} dedupes on {@code (prRef, fingerprint(body))} and
 * surfaces a replay as {@link CommentResult#SKIPPED_DUPLICATE}. The {@link #createdPullRequests()}
 * / {@link #postedComments()} accessors are test-only and NOT on the {@link RepositoryHostAdapter}
 * port.
 */
@Component
@Primary
@Profile("github-mock")
public class GitHubMockAdapter implements RepositoryHostAdapter {

  private static final Logger log = LoggerFactory.getLogger(GitHubMockAdapter.class);

  /** Deterministic creation instant stamped on synthesized PRs (no wall-clock reads). */
  private static final Instant SYNTHETIC_CREATED_AT = Instant.parse("2026-01-01T00:00:00Z");

  private final GitHubMockScenarioRegistry registry;
  private final ConcurrentMap<CreatePullRequestKey, PullRequest> createdPullRequests =
      new ConcurrentHashMap<>();
  private final ConcurrentMap<CommentKey, PostedComment> postedComments = new ConcurrentHashMap<>();

  public GitHubMockAdapter(GitHubMockScenarioRegistry registry) {
    this.registry = Objects.requireNonNull(registry, "registry");
  }

  @Override
  public Optional<Repository> getRepositoryByRef(RepositoryRef ref) {
    Objects.requireNonNull(ref, "ref");
    String repoRef = ref.value();
    throwIfAdversarial(repoRef, "getRepositoryByRef");
    Optional<Repository> repository = registry.findRepository(repoRef);
    log.info(
        "github_mock get_repository repoRef={} resolution={}",
        repoRef,
        repository.isPresent() ? "hit" : "empty");
    return repository;
  }

  @Override
  public Optional<PullRequest> getPullRequestByRef(PullRequestRef ref) {
    Objects.requireNonNull(ref, "ref");
    String prRef = ref.value();
    Optional<GitHubMockScenario> scenario = registry.find(prRef);
    if (scenario.isPresent()
        && scenario.get().behaviour() == GitHubMockScenario.Behaviour.CONFLICT) {
      PullRequest conflicting = conflictPullRequest(prRef);
      log.warn(
          "github_mock get_pull_request prRef={} resolution=conflict conflictRepoRef={}",
          prRef,
          conflicting.repoRef().value());
      return Optional.of(conflicting);
    }
    throwIfAdversarial(prRef, "getPullRequestByRef");
    Optional<PullRequest> pullRequest = findAnyPullRequest(prRef);
    log.info(
        "github_mock get_pull_request prRef={} resolution={}",
        prRef,
        pullRequest.isPresent() ? "hit" : "empty");
    return pullRequest;
  }

  @Override
  public Optional<Branch> getBranchByRef(RepositoryRef repo, String branchName) {
    Objects.requireNonNull(repo, "repo");
    Objects.requireNonNull(branchName, "branchName");
    String repoRef = repo.value();
    Optional<Branch> branch = registry.findBranch(repoRef, branchName);
    log.info(
        "github_mock get_branch repoRef={} branch={} resolution={}",
        repoRef,
        branchName,
        branch.isPresent() ? "hit" : "empty");
    return branch;
  }

  @Override
  public PullRequest createPullRequest(
      RepositoryRef repo, String sourceBranch, String targetBranch, String title, String body) {
    Objects.requireNonNull(repo, "repo");
    Objects.requireNonNull(sourceBranch, "sourceBranch");
    String repoRef = repo.value();
    // AC5: a protected source branch is rejected — the special branch ref is the injection key.
    throwIfAdversarial(sourceBranch, "createPullRequest");
    // Normalize blank/null to "" so the idempotency key matches the real adapter's
    // isBlank()->default-branch fallback posture (parity), and so the synthesized PR identity
    // below stays consistent with the dedup key.
    String normalizedTarget = (targetBranch == null || targetBranch.isBlank()) ? "" : targetBranch;
    CreatePullRequestKey key = new CreatePullRequestKey(repoRef, sourceBranch, normalizedTarget);
    PullRequest existing = createdPullRequests.get(key);
    if (existing != null) {
      // AC8: idempotent replay — same (repoRef, sourceBranch, targetBranch) returns the record.
      log.warn(
          "github_mock create_pull_request repoRef={} branch={} resolution=idempotent_replay prRef={}",
          repoRef,
          sourceBranch,
          existing.prRef().value());
      return existing;
    }
    PullRequest created = synthesizePullRequest(repoRef, sourceBranch, normalizedTarget);
    PullRequest raced = createdPullRequests.putIfAbsent(key, created);
    if (raced != null) {
      log.warn(
          "github_mock create_pull_request repoRef={} branch={} resolution=idempotent_replay prRef={}",
          repoRef,
          sourceBranch,
          raced.prRef().value());
      return raced;
    }
    log.info(
        "github_mock create_pull_request repoRef={} branch={} resolution=created prRef={} number={}",
        repoRef,
        sourceBranch,
        created.prRef().value(),
        created.number());
    return created;
  }

  @Override
  public PullRequest updatePullRequest(PullRequestRef ref, String body) {
    Objects.requireNonNull(ref, "ref");
    String prRef = ref.value();
    throwIfAdversarial(prRef, "updatePullRequest");
    PullRequest pullRequest =
        findAnyPullRequest(prRef)
            .orElseThrow(
                () -> {
                  RepositoryHostAdapterException failure =
                      new RepositoryHostAdapterException(
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
  public CommentResult commentOnPullRequest(PullRequestRef ref, String body) {
    Objects.requireNonNull(ref, "ref");
    Objects.requireNonNull(body, "body");
    String prRef = ref.value();
    // AC5: pr-rate-limited / pr-403 injection keyed by prRef.
    throwIfAdversarial(prRef, "commentOnPullRequest");
    String fingerprint = fingerprint(body);
    CommentKey key = new CommentKey(prRef, fingerprint);
    if (postedComments.containsKey(key)) {
      // AC8: idempotent replay — same (prRef, fingerprint) is a no-op (SKIPPED_DUPLICATE).
      log.warn(
          "github_mock comment_on_pull_request prRef={} fingerprint={} resolution=idempotent_replay",
          prRef,
          fingerprint);
      return CommentResult.SKIPPED_DUPLICATE;
    }
    PostedComment raced = postedComments.putIfAbsent(key, new PostedComment(prRef, fingerprint));
    if (raced != null) {
      log.warn(
          "github_mock comment_on_pull_request prRef={} fingerprint={} resolution=idempotent_replay",
          prRef,
          fingerprint);
      return CommentResult.SKIPPED_DUPLICATE;
    }
    log.info(
        "github_mock comment_on_pull_request prRef={} fingerprint={} resolution=recorded",
        prRef,
        fingerprint);
    return CommentResult.POSTED;
  }

  @Override
  public CiStatus readCheckRuns(RepositoryRef repo, String ref) {
    Objects.requireNonNull(repo, "repo");
    Objects.requireNonNull(ref, "ref");
    if (ref.isBlank()) {
      // Parity with GitHubRealAdapter.readCheckRuns — a blank commit SHA is a caller bug, not a
      // green build; the mock must surface it identically instead of returning SUCCESS.
      throw new RepositoryHostAdapterException(
          IntegrationFailureCategory.GITHUB_NETWORK_FAILURE,
          "GitHub readCheckRuns: ref (commit SHA) must be non-blank");
    }
    // Story 3h-5 (AC5) — deterministic red build whose body carries a planted GitHub PAT, so the
    // capture path's redaction can be asserted end-to-end on the persisted CI raw output. Kept
    // separate from the plain red sentinel so the happy red-loop fixtures stay secret-free.
    if (GitHubMockScenarioRegistry.CI_RED_WITH_SECRET_HEAD_SHA.equals(ref)) {
      CiCheck failed =
          new CiCheck(
              "ci-build",
              "failure",
              "https://github.com/" + repo.value() + "/runs/2",
              "1 failing check",
              "check: ci-build\n"
                  + "conclusion: failure\n"
                  + "title: Build failed\n"
                  + "text: build log leaked ghp_1234567890abcdef1234567890abcdef1234\n");
      log.warn(
          "github_mock read_check_runs repoRef={} ref={} conclusion=failure", repo.value(), ref);
      return new CiStatus(CiConclusion.FAILURE, ref, List.of(failed));
    }
    // Story 3h-5 (AC1) — deterministic verdict: the CI_RED_HEAD_SHA sentinel returns a red build
    // (one failing check + one fixture failure annotation); every other ref is green. No
    // wall-clock,
    // no network, no randomness — parity with the real adapter's shape.
    if (GitHubMockScenarioRegistry.CI_RED_HEAD_SHA.equals(ref)) {
      CiCheck failed =
          new CiCheck(
              "ci-build",
              "failure",
              "https://github.com/" + repo.value() + "/runs/1",
              "1 failing check",
              "check: ci-build\n"
                  + "conclusion: failure\n"
                  + "title: Build failed\n"
                  + "annotations:\n"
                  + "  src/main/java/Example.java:12 — cannot find symbol");
      log.warn(
          "github_mock read_check_runs repoRef={} ref={} conclusion=failure", repo.value(), ref);
      return new CiStatus(CiConclusion.FAILURE, ref, List.of(failed));
    }
    log.info("github_mock read_check_runs repoRef={} ref={} conclusion=success", repo.value(), ref);
    return new CiStatus(CiConclusion.SUCCESS, ref, List.of());
  }

  @Override
  public ConnectorKind connectorKind() {
    return ConnectorKind.GITHUB;
  }

  @Override
  public RepositoryHostCapabilities getCapabilities() {
    return RepositoryHostCapabilities.githubDefaults();
  }

  @Override
  public ConnectivityResult verifyConnectivity(RepositoryRef repo, String credentialOverride) {
    // Deterministic, network-free probe (story 3c-8): the mock is always reachable + authenticated.
    // The credential override is irrelevant to the mock (no real auth) and is never logged.
    log.info(
        "github_mock verify_connectivity repoRef={} resolution=ok",
        repo == null ? "<none>" : repo.value());
    return ConnectivityResult.ok("github-mock: deterministic reachable + authenticated");
  }

  /**
   * Test-only accessor returning the deduped created-PR records. Returns an immutable snapshot. Not
   * part of the {@link RepositoryHostAdapter} port — only adapter-scope tests should depend on it.
   */
  public List<PullRequest> createdPullRequests() {
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

  private Optional<PullRequest> findAnyPullRequest(String prRef) {
    Optional<PullRequest> seeded = registry.findPullRequest(prRef);
    if (seeded.isPresent()) {
      return seeded;
    }
    return createdPullRequests.values().stream()
        .filter(pullRequest -> pullRequest.prRef().value().equals(prRef))
        .findFirst();
  }

  private void throwIfAdversarial(String ref, String operation) {
    Optional<GitHubMockScenario> scenario = registry.find(ref);
    if (scenario.isEmpty()) {
      return;
    }
    switch (scenario.get().behaviour()) {
      case REPO_NOT_FOUND, PERMISSION_DENIED, RATE_LIMITED, BRANCH_PROTECTED -> {
        RepositoryHostAdapterException failure = failure(scenario.get(), operation);
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

  private PullRequest conflictPullRequest(String prRef) {
    return new PullRequest(
        PullRequestRef.of(prRef),
        RepositoryRef.of(GitHubMockScenarioRegistry.CONFLICT_PR_REPO_REF),
        999,
        "conflict/source-branch",
        "open",
        false,
        "https://github.com/" + GitHubMockScenarioRegistry.CONFLICT_PR_REPO_REF + "/pull/999",
        SYNTHETIC_CREATED_AT);
  }

  private PullRequest synthesizePullRequest(String repoRef, String branch, String targetBranch) {
    // Identity derives from (repoRef, sourceBranch, targetBranch) — the same tuple as the
    // CreatePullRequestKey — so two distinct dedup keys can never collapse onto a byte-identical
    // synthesized prRef/number (a PR into a different base branch is a different PR).
    int stable = (repoRef + "/" + branch + "->" + targetBranch).hashCode() & 0x7fffffff;
    int number = 1000 + (stable % 9000);
    String prRef = "PR-NEW-" + Integer.toHexString(stable);
    return new PullRequest(
        PullRequestRef.of(prRef),
        RepositoryRef.of(repoRef),
        number,
        branch,
        "open",
        false,
        "https://github.com/" + repoRef + "/pull/" + number,
        SYNTHETIC_CREATED_AT);
  }

  private static RepositoryHostAdapterException failure(
      GitHubMockScenario scenario, String operation) {
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
    return new RepositoryHostAdapterException(
        category,
        "github_mock "
            + operation
            + " simulated "
            + scenario.behaviour().name()
            + " for ref="
            + scenario.ref());
  }

  /**
   * Deterministic, body-content-free idempotency fingerprint. Uses the trimmed body's SHA-256
   * rendered as hex — never the body bytes themselves, so no PR content is logged or stored.
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

  private record CreatePullRequestKey(String repoRef, String sourceBranch, String targetBranch) {}

  private record CommentKey(String prRef, String fingerprint) {}
}
