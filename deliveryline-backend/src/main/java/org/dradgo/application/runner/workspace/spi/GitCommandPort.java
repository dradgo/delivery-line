package org.dradgo.application.runner.workspace.spi;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Application-owned SPI for the host-side {@code git} operations that back {@code
 * RepositoryWorkspaceService} (story 3.9 Decision D1). Implemented by {@code
 * adapters.git.CliGitAdapter}, which wraps the system {@code git} binary via {@code
 * ProcessBuilder}.
 *
 * <p>The port speaks only repo paths + refs + domain-shaped records — it NEVER exposes {@code
 * Process}, exit codes, or any git-library type to the application layer (Trap T1, mirrors the
 * {@code RunnerWorkspaceStore} SPI shape). Failures surface as {@link GitCommandException} carrying
 * a domain {@code IntegrationFailureCategory}.
 *
 * <p><strong>Credential posture (Decision D2 / AC10).</strong> The {@code githubToken} passed to
 * the network operations ({@link #cloneRepository}, {@link #checkoutOrReuseBranch}, {@link #push})
 * is injected transiently into the git child process via a per-invocation {@code -c
 * credential.helper=…} reading an env var — NEVER written to the remote URL, NEVER persisted to
 * {@code .git/config}, NEVER logged. Pass {@code null}/blank for token-less transports (e.g. {@code
 * file://} test remotes).
 */
public interface GitCommandPort {

  /**
   * Clone {@code spec.remoteUrl()} into {@code spec.targetDir()} (shallow per {@code depth},
   * optional sparse paths), authenticating transiently with {@code githubToken}.
   * Idempotent-friendly: callers guard against an already-cloned dir (the service only clones into
   * a fresh {@code repo/}).
   */
  void cloneRepository(CloneSpec spec, String githubToken);

  /**
   * AC3 idempotent branch handling. When {@code branch} already exists on the remote with prior
   * commits → fetch + hard-reset the local branch to the remote tip (preserving prior runner work);
   * when it exists only locally from a partial prior attempt → reuse; otherwise create it from the
   * current HEAD. Always leaves a clean working tree — never a half-merged/conflicting state (T7).
   */
  BranchOutcome checkoutOrReuseBranch(Path repoDir, String branch, String githubToken);

  /** Configure {@code user.name} / {@code user.email} on the repo's LOCAL config (Decision D8). */
  void configureIdentity(Path repoDir, String botName, String botEmail);

  /**
   * Persist a non-secret value into the repo's LOCAL git config (Decision D6). Used by {@code
   * prepareWorkspace} to stamp the {@code repoRef}/{@code ticketRef}/{@code workflowRunId} so
   * {@code captureAndPush(rex)} can re-derive them from the self-describing on-disk repo
   * (restart-robust, no in-memory map). NEVER store a secret here — the cloned repo's {@code
   * .git/config} is visible to the runner mount (AC10).
   */
  void setLocalConfig(Path repoDir, String key, String value);

  /** Read a value previously stored via {@link #setLocalConfig} (Decision D6). Empty when unset. */
  java.util.Optional<String> getLocalConfig(Path repoDir, String key);

  /** The currently checked-out branch read from the on-disk {@code HEAD} (Decision D6). */
  String currentBranch(Path repoDir);

  /** The {@code origin} remote URL read from the on-disk repo config (Decision D6). */
  String originRemoteUrl(Path repoDir);

  /** True when the working tree has staged or unstaged changes (AC6). */
  boolean hasUncommittedChanges(Path repoDir);

  /**
   * Story 3h-1 (build-artifact hygiene) — the repo-relative untracked, non-ignored paths ({@code
   * git ls-files --others --exclude-standard}). Used by the build-validation stage to snapshot the
   * worktree BEFORE a validation build so any files the build creates (and which are NOT already
   * {@code .gitignore}d) can be discarded via {@link #removeUntrackedPaths} before {@code
   * captureAndPush}'s {@code git add -A} — a validation-only BUILD must never push its own outputs.
   * Empty when the tree has no untracked non-ignored files. Failures surface as {@link
   * GitCommandException}.
   */
  List<String> listUntrackedFiles(Path repoDir);

  /**
   * Story 3h-1 (build-artifact hygiene) — remove the given repo-relative untracked paths ({@code
   * git clean -f -d -- <paths>}). No-op for an empty/blank collection. Restricted to the EXPLICIT
   * pathspecs — never a blanket clean — and, like {@code git clean} without {@code -x}, it only
   * ever removes untracked, non-ignored files (never tracked/committed content). Callers pass ONLY
   * the build-created delta (post-build untracked minus the pre-build snapshot) so runner-authored
   * files are never touched. Failures surface as {@link GitCommandException}.
   */
  void removeUntrackedPaths(Path repoDir, java.util.Collection<String> relativePaths);

  /**
   * Stage all changes + commit with {@code spec.message()} (trailers included) under the bot
   * identity. Returns the new commit SHA.
   */
  String commitAll(CommitSpec spec);

  /** Resolve the current {@code HEAD} commit SHA. */
  String headCommitSha(Path repoDir);

  /**
   * Push {@code branch} to {@code origin}. On rejection/failure raises {@link GitCommandException}
   * classified to the matching git {@code IntegrationFailureCategory} (push-rejected /
   * branch-protection / network / auth) — NEVER auto-rebases, force-pushes, or retries (AC7/T8).
   */
  PushResult push(Path repoDir, String branch, String githubToken);

  /**
   * Story 3g follow-up (FR69/pr-output review) — the unified diff of {@code HEAD} against {@code
   * baseRef} (typically the repo's default branch), i.e. {@code git diff baseRef...HEAD}. Used by
   * {@code captureAndPush} to materialize the pushed changes so the OFFLINE pr-output advisory
   * reviewer can inspect them (its container has no network + no repo checkout). Returns the raw
   * unified diff text (possibly empty when there are no changes vs {@code baseRef}); failures
   * surface as {@link GitCommandException} and every line is routed through the adapter's redaction
   * pass like all other git output.
   */
  String diff(Path repoDir, String baseRef);

  /**
   * Story 3a-2 (AC6) — deterministic, {@code .gitignore}-respecting, depth-bounded top-level tree
   * listing of the cloned working tree at {@code HEAD}, for the spec-stage repo-context summary.
   *
   * <p>Reads committed content via {@code git ls-tree} (never a bespoke filesystem walk that would
   * sweep {@code node_modules}/{@code target} — AC6/Trap T4). Returns workspace-relative entries
   * (each tracked file plus its ancestor directories) truncated to {@code maxDepth} path segments,
   * sorted ascending so the embedded summary is byte-stable across runs. {@code maxDepth <= 0} is
   * normalized to {@code 1}. Failures surface as {@link GitCommandException}; the returned paths
   * are routed through the adapter's redaction pass like every other git line.
   */
  List<RepoTreeEntry> listTopLevelTree(Path repoDir, int maxDepth);

  /** Outcome of {@link #checkoutOrReuseBranch} (AC3). */
  enum BranchOutcome {
    /** Branch did not exist anywhere → created fresh from HEAD. */
    CREATED,
    /** Branch existed only locally (partial prior attempt) → reused. */
    REUSED_LOCAL,
    /** Branch existed on the remote with prior commits → fetched + reset local to remote tip. */
    RESET_TO_REMOTE
  }

  /** Clone parameters (Decision D2/D3, AC5). {@code sparsePaths} empty ⇒ full-tree checkout. */
  record CloneSpec(String remoteUrl, Path targetDir, int depth, List<String> sparsePaths) {

    public CloneSpec {
      if (remoteUrl == null || remoteUrl.isBlank()) {
        throw new IllegalArgumentException("remoteUrl must be non-blank");
      }
      Objects.requireNonNull(targetDir, "targetDir");
      depth = depth <= 0 ? 1 : depth;
      sparsePaths = sparsePaths == null ? List.of() : List.copyOf(sparsePaths);
    }

    public boolean sparseEnabled() {
      return !sparsePaths.isEmpty();
    }
  }

  /** Commit parameters (AC6/AC12). {@code message} already carries the governance trailers. */
  record CommitSpec(Path repoDir, String message, String botName, String botEmail) {

    public CommitSpec {
      Objects.requireNonNull(repoDir, "repoDir");
      if (message == null || message.isBlank()) {
        throw new IllegalArgumentException("commit message must be non-blank");
      }
      if (botName == null || botName.isBlank()) {
        throw new IllegalArgumentException("botName must be non-blank");
      }
      if (botEmail == null || botEmail.isBlank()) {
        throw new IllegalArgumentException("botEmail must be non-blank");
      }
    }
  }

  /** Successful push result (AC6) — the pushed commit SHA + branch + (token-free) origin URL. */
  record PushResult(String commitSha, String branch, String remoteUrl) {

    public PushResult {
      if (commitSha == null || commitSha.isBlank()) {
        throw new IllegalArgumentException("commitSha must be non-blank");
      }
      if (branch == null || branch.isBlank()) {
        throw new IllegalArgumentException("branch must be non-blank");
      }
    }
  }
}
