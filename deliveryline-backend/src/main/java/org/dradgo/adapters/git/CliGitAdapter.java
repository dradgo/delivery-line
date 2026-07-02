package org.dradgo.adapters.git;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import org.dradgo.application.runner.workspace.spi.GitCommandException;
import org.dradgo.application.runner.workspace.spi.GitCommandPort;
import org.dradgo.application.runner.workspace.spi.RepoTreeEntry;
import org.dradgo.application.security.RedactionPolicyService;
import org.dradgo.domain.registry.DataClassification;
import org.dradgo.domain.registry.IntegrationFailureCategory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Story 3.9 (Decision D1) — {@link GitCommandPort} implementation wrapping the <strong>system
 * {@code git} binary</strong> via {@link ProcessBuilder}. Mirrors the timeout / {@code
 * destroyForcibly} / {@code redirectErrorStream} idiom of {@code
 * DoctorProbeAdapter.probeDockerAvailability}.
 *
 * <p><strong>Credential posture (Decision D2 / AC10).</strong> The GitHub PAT is injected into the
 * git child process only via a per-invocation transient {@code -c credential.helper='!f(){ echo
 * username=x-access-token; echo "password=$GIT_PAT"; };f'} with {@code GIT_PAT} set in the child
 * env — NEVER written to the remote URL, NEVER persisted to {@code .git/config}, NEVER logged. A
 * leading empty {@code -c credential.helper=} clears any inherited system/global helper. {@code
 * GIT_TERMINAL_PROMPT=0} guarantees git never blocks prompting for credentials (anti-hang). Every
 * child-process stdout/stderr line is routed through {@link RedactionPolicyService} before any log
 * write (Trap T5).
 */
@Component
public class CliGitAdapter implements GitCommandPort {

  private static final Logger log = LoggerFactory.getLogger(CliGitAdapter.class);

  private static final int DEFAULT_LOCAL_TIMEOUT_SECONDS = 30;
  private static final int DEFAULT_NETWORK_TIMEOUT_SECONDS = 180;
  private static final String SHAREABLE_REDACTED = DataClassification.SHAREABLE_REDACTED.value();
  // x-access-token credential helper: git invokes `credential.helper get` and reads username/
  // password from stdout. The `!`-prefix runs it as a shell command; $GIT_PAT comes from the child
  // env (never the URL, never .git/config). Token-less transports (file://) never invoke it.
  private static final String CREDENTIAL_HELPER_SCRIPT =
      "!f() { echo username=x-access-token; echo \"password=$GIT_PAT\"; }; f";

  private final RedactionPolicyService redaction;
  private final String gitExecutable;
  private final int localTimeoutSeconds;
  private final int networkTimeoutSeconds;

  @Autowired
  public CliGitAdapter(RedactionPolicyService redaction) {
    this(redaction, "git", DEFAULT_LOCAL_TIMEOUT_SECONDS, DEFAULT_NETWORK_TIMEOUT_SECONDS);
  }

  CliGitAdapter(
      RedactionPolicyService redaction,
      String gitExecutable,
      int localTimeoutSeconds,
      int networkTimeoutSeconds) {
    this.redaction = Objects.requireNonNull(redaction, "redaction");
    this.gitExecutable =
        gitExecutable == null || gitExecutable.isBlank() ? "git" : gitExecutable.trim();
    this.localTimeoutSeconds =
        localTimeoutSeconds <= 0 ? DEFAULT_LOCAL_TIMEOUT_SECONDS : localTimeoutSeconds;
    this.networkTimeoutSeconds =
        networkTimeoutSeconds <= 0 ? DEFAULT_NETWORK_TIMEOUT_SECONDS : networkTimeoutSeconds;
  }

  @Override
  public void cloneRepository(CloneSpec spec, String githubToken) {
    Objects.requireNonNull(spec, "spec");
    Path target = spec.targetDir();
    List<String> args = new ArrayList<>();
    args.add("clone");
    args.add("--depth");
    args.add(Integer.toString(spec.depth()));
    if (spec.sparseEnabled()) {
      // Cone-mode sparse checkout: fetch metadata + the configured paths only (AC5, OQ-5).
      args.add("--filter=blob:none");
      args.add("--sparse");
    }
    args.add(spec.remoteUrl());
    args.add(target.toString());
    // Clone runs with the PARENT dir as working dir (the target must not yet exist for git clone).
    Path workingDir = target.getParent() == null ? target : target.getParent();
    GitResult result = runGit(workingDir, githubToken, networkTimeoutSeconds, args);
    if (!result.succeeded()) {
      throw classifyNetworkFailure("clone", result, IntegrationFailureCategory.GIT_NETWORK_FAILURE);
    }
    if (spec.sparseEnabled()) {
      List<String> sparseArgs = new ArrayList<>();
      sparseArgs.add("sparse-checkout");
      sparseArgs.add("set");
      sparseArgs.addAll(spec.sparsePaths());
      GitResult sparse = runGit(target, githubToken, localTimeoutSeconds, sparseArgs);
      if (!sparse.succeeded()) {
        throw new GitCommandException(
            IntegrationFailureCategory.GIT_NETWORK_FAILURE,
            "git sparse-checkout set failed: " + sparse.redactedOutput());
      }
    }
    log.info(
        "git clone ok targetDir={} depth={} sparse={}", target, spec.depth(), spec.sparseEnabled());
  }

  @Override
  public BranchOutcome checkoutOrReuseBranch(Path repoDir, String branch, String githubToken) {
    Objects.requireNonNull(repoDir, "repoDir");
    requireBranch(branch);
    // (1) Does the branch exist on the remote with prior commits? (AC3.a)
    GitResult lsRemote =
        runGit(
            repoDir,
            githubToken,
            networkTimeoutSeconds,
            List.of("ls-remote", "--heads", "origin", branch));
    if (!lsRemote.succeeded()) {
      throw classifyNetworkFailure(
          "ls-remote", lsRemote, IntegrationFailureCategory.GIT_NETWORK_FAILURE);
    }
    boolean remoteExists =
        lsRemote.redactedOutput() != null && lsRemote.redactedOutput().contains(branch);
    if (remoteExists) {
      GitResult fetch =
          runGit(repoDir, githubToken, networkTimeoutSeconds, List.of("fetch", "origin", branch));
      if (!fetch.succeeded()) {
        throw classifyNetworkFailure(
            "fetch", fetch, IntegrationFailureCategory.GIT_NETWORK_FAILURE);
      }
      // Hard-reset the local branch to the remote tip — preserves prior runner work, never a
      // half-merged tree (T7).
      GitResult checkout =
          runGit(
              repoDir,
              githubToken,
              localTimeoutSeconds,
              List.of("checkout", "-B", branch, "FETCH_HEAD"));
      if (!checkout.succeeded()) {
        throw new GitCommandException(
            IntegrationFailureCategory.GIT_PUSH_REJECTED,
            "git checkout (reset to remote) failed: " + checkout.redactedOutput());
      }
      cleanWorktree(repoDir);
      log.warn("git branch reset to remote tip branch={} repoDir={}", branch, repoDir);
      return BranchOutcome.RESET_TO_REMOTE;
    }
    // (2) Does it exist only locally from a partial prior attempt? (AC3.b)
    GitResult localVerify =
        runGit(
            repoDir,
            null,
            localTimeoutSeconds,
            List.of("rev-parse", "--verify", "--quiet", "refs/heads/" + branch));
    if (localVerify.succeeded()
        && localVerify.redactedOutput() != null
        && !localVerify.redactedOutput().isBlank()) {
      GitResult checkout = runGit(repoDir, null, localTimeoutSeconds, List.of("checkout", branch));
      if (!checkout.succeeded()) {
        throw new GitCommandException(
            IntegrationFailureCategory.GIT_PUSH_REJECTED,
            "git checkout (reuse local) failed: " + checkout.redactedOutput());
      }
      cleanWorktree(repoDir);
      log.warn("git branch reused from local ref branch={} repoDir={}", branch, repoDir);
      return BranchOutcome.REUSED_LOCAL;
    }
    // (3) Brand-new branch created from current HEAD. (AC3 default)
    GitResult create =
        runGit(repoDir, null, localTimeoutSeconds, List.of("checkout", "-b", branch));
    if (!create.succeeded()) {
      throw new GitCommandException(
          IntegrationFailureCategory.GIT_PUSH_REJECTED,
          "git checkout -b failed: " + create.redactedOutput());
    }
    cleanWorktree(repoDir);
    log.info("git branch created branch={} repoDir={}", branch, repoDir);
    return BranchOutcome.CREATED;
  }

  @Override
  public void configureIdentity(Path repoDir, String botName, String botEmail) {
    Objects.requireNonNull(repoDir, "repoDir");
    GitResult name =
        runGit(repoDir, null, localTimeoutSeconds, List.of("config", "user.name", botName));
    GitResult email =
        runGit(repoDir, null, localTimeoutSeconds, List.of("config", "user.email", botEmail));
    if (!name.succeeded() || !email.succeeded()) {
      throw new GitCommandException(
          IntegrationFailureCategory.GIT_PUSH_REJECTED,
          "git config identity failed: "
              + (name.succeeded() ? email.redactedOutput() : name.redactedOutput()));
    }
    log.info("git identity configured repoDir={} botName={}", repoDir, botName);
  }

  @Override
  public void setLocalConfig(Path repoDir, String key, String value) {
    Objects.requireNonNull(repoDir, "repoDir");
    GitResult result =
        runGit(repoDir, null, localTimeoutSeconds, List.of("config", "--local", key, value));
    if (!result.succeeded()) {
      throw new GitCommandException(
          IntegrationFailureCategory.GIT_PUSH_REJECTED,
          "git config --local " + key + " failed: " + result.redactedOutput());
    }
  }

  @Override
  public java.util.Optional<String> getLocalConfig(Path repoDir, String key) {
    Objects.requireNonNull(repoDir, "repoDir");
    GitResult result =
        runGit(repoDir, null, localTimeoutSeconds, List.of("config", "--local", "--get", key));
    // `git config --get` exits non-zero (1) when the key is unset — that's "empty", not a failure.
    if (!result.succeeded()) {
      if (result.exitCode() == 1) {
        return java.util.Optional.empty();
      }
      throw new GitCommandException(
          IntegrationFailureCategory.GIT_NETWORK_FAILURE,
          "git config --local --get " + key + " failed: " + result.redactedOutput());
    }
    String value = firstLine(result.redactedOutput());
    return value.isBlank() ? java.util.Optional.empty() : java.util.Optional.of(value);
  }

  @Override
  public String currentBranch(Path repoDir) {
    GitResult result =
        runGit(repoDir, null, localTimeoutSeconds, List.of("rev-parse", "--abbrev-ref", "HEAD"));
    if (!result.succeeded()) {
      throw new GitCommandException(
          IntegrationFailureCategory.GIT_NETWORK_FAILURE,
          "git rev-parse --abbrev-ref HEAD failed: " + result.redactedOutput());
    }
    return firstLine(result.redactedOutput());
  }

  @Override
  public String originRemoteUrl(Path repoDir) {
    GitResult result =
        runGit(repoDir, null, localTimeoutSeconds, List.of("remote", "get-url", "origin"));
    if (!result.succeeded()) {
      throw new GitCommandException(
          IntegrationFailureCategory.GIT_NETWORK_FAILURE,
          "git remote get-url origin failed: " + result.redactedOutput());
    }
    return firstLine(result.redactedOutput());
  }

  @Override
  public boolean hasUncommittedChanges(Path repoDir) {
    GitResult result = runGit(repoDir, null, localTimeoutSeconds, List.of("status", "--porcelain"));
    if (!result.succeeded()) {
      throw new GitCommandException(
          IntegrationFailureCategory.GIT_NETWORK_FAILURE,
          "git status --porcelain failed: " + result.redactedOutput());
    }
    String out = result.redactedOutput();
    return out != null && !out.isBlank();
  }

  @Override
  public String commitAll(CommitSpec spec) {
    Objects.requireNonNull(spec, "spec");
    GitResult add = runGit(spec.repoDir(), null, localTimeoutSeconds, List.of("add", "-A"));
    if (!add.succeeded()) {
      throw new GitCommandException(
          IntegrationFailureCategory.GIT_PUSH_REJECTED,
          "git add -A failed: " + add.redactedOutput());
    }
    // Identity set inline via -c so the commit carries the bot identity even when the local config
    // was not set, and so the host's global git identity is never mutated (Decision D8).
    List<String> commit = new ArrayList<>();
    commit.add("-c");
    commit.add("user.name=" + spec.botName());
    commit.add("-c");
    commit.add("user.email=" + spec.botEmail());
    commit.add("commit");
    commit.add("-m");
    commit.add(spec.message());
    GitResult result = runGit(spec.repoDir(), null, localTimeoutSeconds, commit);
    if (!result.succeeded()) {
      throw new GitCommandException(
          IntegrationFailureCategory.GIT_PUSH_REJECTED,
          "git commit failed: " + result.redactedOutput());
    }
    String sha = headCommitSha(spec.repoDir());
    log.info("git commit ok repoDir={} commitSha={}", spec.repoDir(), sha);
    return sha;
  }

  @Override
  public String headCommitSha(Path repoDir) {
    GitResult result = runGit(repoDir, null, localTimeoutSeconds, List.of("rev-parse", "HEAD"));
    if (!result.succeeded()) {
      throw new GitCommandException(
          IntegrationFailureCategory.GIT_NETWORK_FAILURE,
          "git rev-parse HEAD failed: " + result.redactedOutput());
    }
    return firstLine(result.redactedOutput());
  }

  @Override
  public PushResult push(Path repoDir, String branch, String githubToken) {
    Objects.requireNonNull(repoDir, "repoDir");
    requireBranch(branch);
    GitResult result =
        runGit(repoDir, githubToken, networkTimeoutSeconds, List.of("push", "origin", branch));
    if (!result.succeeded()) {
      IntegrationFailureCategory category = classifyPushFailure(result.redactedOutput());
      log.warn(
          "git push rejected repoDir={} branch={} category={}", repoDir, branch, category.value());
      throw new GitCommandException(
          category, "git push rejected (" + category.value() + "): " + result.redactedOutput());
    }
    String sha = headCommitSha(repoDir);
    String origin = originRemoteUrl(repoDir);
    log.info("git push ok repoDir={} branch={} commitSha={}", repoDir, branch, sha);
    return new PushResult(sha, branch, origin);
  }

  @Override
  public List<RepoTreeEntry> listTopLevelTree(Path repoDir, int maxDepth) {
    Objects.requireNonNull(repoDir, "repoDir");
    int depthLimit = maxDepth <= 0 ? 1 : maxDepth;
    // ls-tree reads committed content at HEAD (deterministic, .gitignore-respecting — ignored or
    // untracked files are never committed so never listed). No bespoke filesystem walk (Trap T4).
    // core.quotePath=false + -z (NUL-delimited) so non-ASCII / space / quote filenames arrive raw
    // and unescaped instead of git's default octal-quoted "\303\251.txt" form, keeping README /
    // manifest detection and the embedded tree correct for such repos.
    GitResult result =
        runGit(
            repoDir,
            null,
            localTimeoutSeconds,
            List.of("-c", "core.quotePath=false", "ls-tree", "-r", "--name-only", "-z", "HEAD"));
    if (!result.succeeded()) {
      throw new GitCommandException(
          IntegrationFailureCategory.GIT_NETWORK_FAILURE,
          "git ls-tree -r --name-only -z HEAD failed: " + result.redactedOutput());
    }
    String out = result.redactedOutput();
    if (out == null || out.isBlank()) {
      return List.of();
    }
    // TreeMap → ascending lexicographic path order (deterministic). A given path is a file XOR a
    // directory in git, so a depth-truncated ancestor never collides with a real file leaf; if a
    // collision ever did surface, the directory interpretation wins (the safer shape).
    TreeMap<String, RepoTreeEntry.Type> entries = new TreeMap<>();
    for (String line : out.split("\0")) {
      String filePath = line.strip();
      if (filePath.isEmpty()) {
        continue;
      }
      String[] segments = filePath.split("/");
      int limit = Math.min(segments.length, depthLimit);
      StringBuilder prefix = new StringBuilder();
      for (int d = 1; d <= limit; d++) {
        if (d > 1) {
          prefix.append('/');
        }
        prefix.append(segments[d - 1]);
        boolean isLeafFile = d == segments.length;
        RepoTreeEntry.Type type = isLeafFile ? RepoTreeEntry.Type.FILE : RepoTreeEntry.Type.DIR;
        entries.merge(
            prefix.toString(),
            type,
            (existing, incoming) ->
                existing == RepoTreeEntry.Type.DIR ? RepoTreeEntry.Type.DIR : incoming);
      }
    }
    List<RepoTreeEntry> tree = new ArrayList<>(entries.size());
    entries.forEach((path, type) -> tree.add(new RepoTreeEntry(path, type)));
    log.debug(
        "git ls-tree summary repoDir={} entryCount={} depthLimit={}",
        repoDir,
        tree.size(),
        depthLimit);
    return List.copyOf(tree);
  }

  private void cleanWorktree(Path repoDir) {
    GitResult reset =
        runGit(repoDir, null, localTimeoutSeconds, List.of("reset", "--hard", "HEAD"));
    if (!reset.succeeded()) {
      throw new GitCommandException(
          IntegrationFailureCategory.GIT_PUSH_REJECTED,
          "git reset --hard failed: " + reset.redactedOutput());
    }
    GitResult clean = runGit(repoDir, null, localTimeoutSeconds, List.of("clean", "-fdx"));
    if (!clean.succeeded()) {
      throw new GitCommandException(
          IntegrationFailureCategory.GIT_PUSH_REJECTED,
          "git clean -fdx failed: " + clean.redactedOutput());
    }
  }

  // =====================================================================
  // git child-process execution + classification
  // =====================================================================

  private GitResult runGit(
      Path workingDir, String githubToken, int timeoutSeconds, List<String> subcommand) {
    List<String> command = new ArrayList<>();
    command.add(gitExecutable);
    boolean withAuth = githubToken != null && !githubToken.isBlank();
    if (withAuth) {
      // Clear any inherited helper FIRST, then install the transient x-access-token helper. The
      // config is command-scoped — never written to the cloned repo's .git/config (AC10/D2).
      command.add("-c");
      command.add("credential.helper=");
      command.add("-c");
      command.add("credential.helper=" + CREDENTIAL_HELPER_SCRIPT);
    }
    command.addAll(subcommand);

    ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
    if (workingDir != null) {
      builder.directory(workingDir.toFile());
    }
    Map<String, String> env = builder.environment();
    // Anti-hang: never prompt for credentials on a missing/invalid PAT (D2). Always set so a
    // token-less local op can't block either.
    env.put("GIT_TERMINAL_PROMPT", "0");
    if (withAuth) {
      env.put("GIT_PAT", githubToken);
    }

    Process process = null;
    try {
      process = builder.start();
      InputStream processOutput = process.getInputStream();
      CompletableFuture<String> outputFuture =
          CompletableFuture.supplyAsync(
              () -> {
                try {
                  return readAll(processOutput);
                } catch (IOException error) {
                  throw new CompletionException(error);
                }
              });
      boolean exited = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
      if (!exited) {
        process.destroyForcibly();
        awaitOutput(outputFuture);
        return new GitResult(-1, redact("git " + subcommand.get(0) + " timed out"));
      }
      int exit = process.exitValue();
      // Route EVERY git stdout/stderr line through redaction before it can reach a log/exception
      // (Trap T5 — a token could in theory echo in an error). Refs/counts/SHAs survive redaction.
      return new GitResult(exit, redact(awaitOutput(outputFuture)));
    } catch (IOException io) {
      // IOException starting the process == git binary missing on PATH (mirrors the docker probe).
      return new GitResult(-1, redact("git binary unavailable: " + io.getMessage()));
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return new GitResult(-1, redact("git " + subcommand.get(0) + " interrupted"));
    } finally {
      if (process != null && process.isAlive()) {
        process.destroyForcibly();
      }
    }
  }

  private static String readAll(InputStream in) throws IOException {
    return new String(in.readAllBytes(), StandardCharsets.UTF_8);
  }

  private static String awaitOutput(CompletableFuture<String> outputFuture) {
    try {
      return outputFuture.join();
    } catch (CompletionException error) {
      return "git output unavailable: " + error.getCause();
    }
  }

  private String redact(String text) {
    if (text == null || text.isEmpty()) {
      return text;
    }
    return redaction.redact(text, SHAREABLE_REDACTED).sanitizedText();
  }

  /**
   * AC7 — map a {@code git push} stderr signal to the matching git {@code
   * IntegrationFailureCategory} by parsing the (already-redacted) output. Order matters:
   * branch-protection and auth signals are checked before the generic non-fast-forward rejection.
   */
  static IntegrationFailureCategory classifyPushFailure(String redactedOutput) {
    String lower = redactedOutput == null ? "" : redactedOutput.toLowerCase(Locale.ROOT);
    if (lower.contains("protected branch")
        || lower.contains("branch protection")
        || lower.contains("protected-branch")
        || lower.contains("required status check")) {
      return IntegrationFailureCategory.GIT_BRANCH_PROTECTION_VIOLATION;
    }
    if (lower.contains("authentication failed")
        || lower.contains("could not read username")
        || lower.contains("permission denied")
        // Match git's HTTP auth signals by phrase, not a bare "403"/"401" substring, which
        // otherwise collides with incidental digits in echoed temp paths / object SHAs.
        || lower.contains("error: 403")
        || lower.contains("error: 401")
        || lower.contains("403 forbidden")
        || lower.contains("401 unauthorized")
        || lower.contains("access denied")) {
      return IntegrationFailureCategory.GIT_AUTH_FAILED;
    }
    if (lower.contains("could not resolve host")
        || lower.contains("connection timed out")
        || lower.contains("connection refused")
        || lower.contains("network is unreachable")
        || lower.contains("failed to connect")
        || lower.contains("unable to access")) {
      return IntegrationFailureCategory.GIT_NETWORK_FAILURE;
    }
    // non-fast-forward / [rejected] / fetch first / force-push policy → push rejected (AC7
    // default).
    return IntegrationFailureCategory.GIT_PUSH_REJECTED;
  }

  private GitCommandException classifyNetworkFailure(
      String op, GitResult result, IntegrationFailureCategory fallback) {
    IntegrationFailureCategory category = classifyPushFailure(result.redactedOutput());
    // For a non-push network op, an auth/network/branch signal is still informative; otherwise use
    // the fallback (the generic push-rejected default does not fit a clone/fetch).
    if (category == IntegrationFailureCategory.GIT_PUSH_REJECTED) {
      category = fallback;
    }
    return new GitCommandException(
        category, "git " + op + " failed (" + category.value() + "): " + result.redactedOutput());
  }

  private static String firstLine(String text) {
    if (text == null) {
      return "";
    }
    String trimmed = text.strip();
    int newline = trimmed.indexOf('\n');
    return newline < 0 ? trimmed : trimmed.substring(0, newline).strip();
  }

  private static void requireBranch(String branch) {
    if (branch == null || branch.isBlank()) {
      throw new IllegalArgumentException("branch must be non-blank");
    }
  }

  /** Exit code + redacted combined output of a single git invocation. */
  private record GitResult(int exitCode, String redactedOutput) {
    boolean succeeded() {
      return exitCode == 0;
    }
  }
}
