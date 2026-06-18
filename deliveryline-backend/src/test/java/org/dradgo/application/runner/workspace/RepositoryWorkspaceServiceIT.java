package org.dradgo.application.runner.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.dradgo.adapters.files.LocalRunnerWorkspaceStore;
import org.dradgo.adapters.git.CliGitAdapter;
import org.dradgo.application.integration.repohost.RepositoryHostAdapter;
import org.dradgo.application.integration.spi.IntegrationLinkRecordPort;
import org.dradgo.application.runner.RunnerSecretsService;
import org.dradgo.application.runner.spi.RunnerExecutionRecordPort;
import org.dradgo.application.runner.spi.RunnerExecutionSnapshot;
import org.dradgo.application.runner.workspace.spi.GitCommandException;
import org.dradgo.application.security.DataClassificationService;
import org.dradgo.application.security.RedactionPolicyService;
import org.dradgo.application.workflow.WorkflowProperties;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.integration.repohost.PullRequest;
import org.dradgo.domain.integration.repohost.PullRequestRef;
import org.dradgo.domain.integration.repohost.Repository;
import org.dradgo.domain.integration.repohost.RepositoryRef;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.IntegrationFailureCategory;
import org.dradgo.domain.registry.RunnerExecutionStatus;
import org.dradgo.domain.registry.RunnerStage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;

/**
 * Story 3.9 AC14 — end-to-end {@link RepositoryWorkspaceService} integration test against a LOCAL
 * bare git repo ({@code git init --bare}, local-path remote; no gitea/Docker/network,
 * cross-platform). Uses the REAL {@link CliGitAdapter} + {@link LocalRunnerWorkspaceStore}; the
 * GitHub adapter + secrets + ports are mocked. Requires {@code git} on PATH (CI runners have it;
 * the suite is skipped via {@code assumeTrue} otherwise).
 */
class RepositoryWorkspaceServiceIT {

  private static final String REX = "rex_repoit00000001";
  private static final String RUN_ID = "run_repoit12345678";
  private static final String TICKET = "DEL-9001";
  private static final String REPO_REF = "GH-101";
  private static final String TOKEN = "ghp_FAKEtoken0000000000000000000000000000";

  private static boolean gitAvailable;

  @TempDir Path tmp;
  private Path remoteBare;
  private Path home;
  private String remoteUrl;

  private LocalRunnerWorkspaceStore store;
  private CliGitAdapter git;
  private RepositoryHostAdapter gitHubAdapter;
  private RunnerSecretsService secrets;
  private RunnerExecutionRecordPort recordPort;
  private IntegrationLinkRecordPort links;
  private RepositoryWorkspaceService service;

  private ListAppender<ILoggingEvent> serviceAppender;
  private ListAppender<ILoggingEvent> gitAppender;

  @BeforeAll
  static void detectGit() {
    gitAvailable = runQuiet("git", "--version") && runCloneSmoke();
  }

  @BeforeEach
  void setUp() throws Exception {
    assumeTrue(gitAvailable, "git not on PATH — skipping local-bare-repo IT");

    home = Files.createDirectories(tmp.resolve("home"));
    remoteBare = tmp.resolve("remote.git");
    seedBareRemote();
    remoteUrl = remoteBare.toAbsolutePath().toString();

    store = new LocalRunnerWorkspaceStore(home.toString());
    git = new CliGitAdapter(new RedactionPolicyService(new DataClassificationService()));
    gitHubAdapter = Mockito.mock(RepositoryHostAdapter.class);
    secrets = Mockito.mock(RunnerSecretsService.class);
    recordPort = Mockito.mock(RunnerExecutionRecordPort.class);
    links = Mockito.mock(IntegrationLinkRecordPort.class);

    when(gitHubAdapter.getRepositoryByRef(RepositoryRef.of(REPO_REF)))
        .thenReturn(
            Optional.of(
                new Repository(RepositoryRef.of(REPO_REF), "owner/repo", "main", remoteUrl)));
    lenient()
        .when(gitHubAdapter.createPullRequest(any(), anyString(), any(), anyString(), anyString()))
        .thenReturn(
            new PullRequest(
                PullRequestRef.of("PR-1"),
                RepositoryRef.of(REPO_REF),
                1,
                "b",
                "open",
                "u",
                Instant.now()));
    when(secrets.resolveHostSecret("GITHUB_TOKEN")).thenReturn(Optional.of(TOKEN));
    when(links.findActiveByWorkflowRun(anyString())).thenReturn(Optional.empty());
    lenient().when(recordPort.findByPublicId(REX)).thenReturn(Optional.of(snapshot()));

    service =
        new RepositoryWorkspaceService(
            git, gitHubAdapter, secrets, store, recordPort, links, WorkflowProperties.defaults());

    serviceAppender = attach(RepositoryWorkspaceService.class);
    gitAppender = attach(CliGitAdapter.class);
  }

  @AfterEach
  void tearDown() {
    if (serviceAppender != null) {
      detach(RepositoryWorkspaceService.class, serviceAppender);
    }
    if (gitAppender != null) {
      detach(CliGitAdapter.class, gitAppender);
    }
  }

  @Test
  void prepareWorkspaceClonesAndChecksOutDeterministicBranch() throws Exception {
    RepositoryWorkspaceService.RepositoryMount mount =
        service.prepareWorkspace(RUN_ID, RunnerStage.INVESTIGATION, REX, TICKET, REPO_REF);

    assertThat(mount.defaultBranch()).isEqualTo("main");
    assertThat(mount.branch()).isEqualTo(RepositoryWorkspaceService.branchName(TICKET, RUN_ID));
    Path repo = mount.repoHostPath();
    assertThat(Files.isDirectory(repo.resolve(".git"))).isTrue();
    assertThat(Files.exists(repo.resolve("README.md"))).isTrue();
    assertThat(runCapture(repo, "rev-parse", "--abbrev-ref", "HEAD")).isEqualTo(mount.branch());
  }

  @Test
  void captureAndPushCommitsWithTrailersPushesAndCreatesPullRequest() throws Exception {
    RepositoryWorkspaceService.RepositoryMount mount =
        service.prepareWorkspace(RUN_ID, RunnerStage.INVESTIGATION, REX, TICKET, REPO_REF);
    Files.writeString(mount.repoHostPath().resolve("change.txt"), "runner produced this");

    Optional<RepositoryWorkspaceService.RepositoryPushOutcome> outcome =
        service.captureAndPush(REX);

    assertThat(outcome).isPresent();
    assertThat(outcome.get().committed()).isTrue();
    assertThat(outcome.get().commitSha()).isNotBlank();
    assertThat(outcome.get().prRef()).isEqualTo("PR-1");

    // AC12 — the commit on the branch carries the governance trailers.
    String body = runCapture(mount.repoHostPath(), "log", "-1", "--format=%B");
    assertThat(body)
        .contains("Deliveryline-Run: " + RUN_ID)
        .contains("Deliveryline-Stage: investigation")
        .contains("Deliveryline-RunnerExecution: " + REX);

    // The branch + commit reached the bare remote.
    String remoteSha = runCapture(remoteBare, "rev-parse", mount.branch());
    assertThat(remoteSha).isEqualTo(outcome.get().commitSha());
  }

  @Test
  void idempotentBranchReuseResetsToRemoteTipPreservingPriorWork() throws Exception {
    // Pre-create the deterministic branch on the remote with a prior commit (a retry scenario).
    String branch = RepositoryWorkspaceService.branchName(TICKET, RUN_ID);
    Path seed = tmp.resolve("seed2");
    run(tmp, "clone", remoteBare.toString(), seed.toString());
    run(seed, "checkout", "-b", branch);
    Files.writeString(seed.resolve("prior-work.txt"), "from a prior attempt");
    run(seed, "-c", "user.email=s@t", "-c", "user.name=S", "add", "-A");
    run(seed, "-c", "user.email=s@t", "-c", "user.name=S", "commit", "-m", "prior");
    run(seed, "push", "origin", branch);

    RepositoryWorkspaceService.RepositoryMount mount =
        service.prepareWorkspace(RUN_ID, RunnerStage.INVESTIGATION, REX, TICKET, REPO_REF);

    // AC3.a — prior runner work fetched + reset into the workspace.
    assertThat(Files.exists(mount.repoHostPath().resolve("prior-work.txt"))).isTrue();
    assertThat(warnMessages(gitAppender))
        .anyMatch(m -> m.contains("git branch reset to remote tip"));
  }

  @Test
  void prepareWorkspaceReusesExistingLocalCloneFromPartialAttempt() throws Exception {
    RepositoryWorkspaceService.RepositoryMount first =
        service.prepareWorkspace(
            RUN_ID, RunnerStage.INVESTIGATION, REX, TICKET, "Fix dispatch", REPO_REF);
    Files.writeString(first.repoHostPath().resolve("dirty.txt"), "leftover uncommitted work");

    RepositoryWorkspaceService.RepositoryMount second =
        service.prepareWorkspace(
            RUN_ID, RunnerStage.INVESTIGATION, REX, TICKET, "Fix dispatch", REPO_REF);

    assertThat(second.repoHostPath()).isEqualTo(first.repoHostPath());
    assertThat(runCapture(second.repoHostPath(), "rev-parse", "--abbrev-ref", "HEAD"))
        .isEqualTo(first.branch());
    assertThat(Files.exists(second.repoHostPath().resolve("dirty.txt")))
        .as("local-only retry must hand the runner a clean worktree")
        .isFalse();
  }

  @Test
  void repositoryRefThatDoesNotResolveRaisesMismatchBeforeAnyClone() {
    when(gitHubAdapter.getRepositoryByRef(RepositoryRef.of("GH-unknown")))
        .thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                service.prepareWorkspace(
                    RUN_ID, RunnerStage.INVESTIGATION, REX, TICKET, "GH-unknown"))
        .isInstanceOf(DomainException.class)
        .satisfies(
            e ->
                assertThat(((DomainException) e).errorCode())
                    .isEqualTo(DomainErrorCode.LINEAR_GITHUB_REPO_MISMATCH));

    assertThat(store.resolveRepositoryDir(REX)).isEmpty();
  }

  @Test
  void pushRejectionIsClassifiedAndRaisedWithoutAutoRetry() throws Exception {
    RepositoryWorkspaceService.RepositoryMount mount =
        service.prepareWorkspace(RUN_ID, RunnerStage.INVESTIGATION, REX, TICKET, REPO_REF);
    String branch = mount.branch();

    // Advance the REMOTE branch independently so the workspace's later push is non-fast-forward.
    Path seed = tmp.resolve("seed3");
    run(tmp, "clone", remoteBare.toString(), seed.toString());
    run(seed, "checkout", "-b", branch);
    Files.writeString(seed.resolve("remote-advance.txt"), "advanced on the remote");
    run(seed, "-c", "user.email=s@t", "-c", "user.name=S", "add", "-A");
    run(seed, "-c", "user.email=s@t", "-c", "user.name=S", "commit", "-m", "advance");
    run(seed, "push", "origin", branch);

    Files.writeString(mount.repoHostPath().resolve("local-change.txt"), "diverging local change");

    assertThatThrownBy(() -> service.captureAndPush(REX))
        .isInstanceOf(GitCommandException.class)
        .satisfies(
            e ->
                assertThat(((GitCommandException) e).failureCategory())
                    .isEqualTo(IntegrationFailureCategory.GIT_PUSH_REJECTED));
    assertThat(warnMessages(gitAppender)).anyMatch(m -> m.contains("git push rejected"));
  }

  @Test
  void getLocalConfigThrowsWhenRepoConfigCannotBeRead() throws Exception {
    RepositoryWorkspaceService.RepositoryMount mount =
        service.prepareWorkspace(
            RUN_ID, RunnerStage.INVESTIGATION, REX, TICKET, "Fix dispatch", REPO_REF);
    Files.writeString(mount.repoHostPath().resolve(".git").resolve("config"), "[broken");

    assertThatThrownBy(
            () ->
                git.getLocalConfig(
                    mount.repoHostPath(), RepositoryWorkspaceService.CONFIG_REPO_REF))
        .isInstanceOf(GitCommandException.class)
        .satisfies(
            e ->
                assertThat(((GitCommandException) e).failureCategory())
                    .isEqualTo(IntegrationFailureCategory.GIT_NETWORK_FAILURE));
  }

  @Test
  void tokenNeverAppearsInWorkspaceFilesGitConfigOrLogs() throws Exception {
    RepositoryWorkspaceService.RepositoryMount mount =
        service.prepareWorkspace(RUN_ID, RunnerStage.INVESTIGATION, REX, TICKET, REPO_REF);
    Files.writeString(mount.repoHostPath().resolve("change.txt"), "x");
    service.captureAndPush(REX);

    // AC10 — token never persisted to .git/config and never on a workspace file.
    Path gitConfig = mount.repoHostPath().resolve(".git").resolve("config");
    String config = Files.readString(gitConfig, StandardCharsets.UTF_8);
    assertThat(config).doesNotContain(TOKEN).doesNotContain("x-access-token");

    try (Stream<Path> files = Files.walk(mount.repoHostPath())) {
      files
          .filter(Files::isRegularFile)
          .forEach(
              f -> {
                try {
                  assertThat(Files.readString(f, StandardCharsets.UTF_8)).doesNotContain(TOKEN);
                } catch (IOException | RuntimeException ignored) {
                  // binary / unreadable files cannot carry the literal token string
                }
              });
    }
    // AC10 — token never in any captured log line.
    Stream.concat(serviceAppender.list.stream(), gitAppender.list.stream())
        .map(ILoggingEvent::getFormattedMessage)
        .forEach(m -> assertThat(m).doesNotContain(TOKEN));
  }

  @Test
  void cleanupWorkspaceDeletesTheClonedRepo() {
    RepositoryWorkspaceService.RepositoryMount mount =
        service.prepareWorkspace(RUN_ID, RunnerStage.INVESTIGATION, REX, TICKET, REPO_REF);
    assertThat(Files.exists(mount.repoHostPath())).isTrue();

    service.cleanupWorkspace(REX);

    assertThat(Files.exists(mount.repoHostPath())).isFalse();
    assertThat(store.resolveRepositoryDir(REX)).isEmpty();
  }

  // =====================================================================
  // helpers
  // =====================================================================

  private void seedBareRemote() throws Exception {
    run(tmp, "init", "--bare", remoteBare.toString());
    run(remoteBare, "symbolic-ref", "HEAD", "refs/heads/main");
    Path seed = tmp.resolve("seed");
    run(tmp, "clone", remoteBare.toString(), seed.toString());
    Files.writeString(seed.resolve("README.md"), "seed repository\n");
    run(seed, "-c", "user.email=s@t", "-c", "user.name=Seed", "add", "-A");
    run(seed, "-c", "user.email=s@t", "-c", "user.name=Seed", "commit", "-m", "seed commit");
    run(seed, "branch", "-M", "main");
    run(seed, "push", "origin", "main");
  }

  private static RunnerExecutionSnapshot snapshot() {
    OffsetDateTime now = OffsetDateTime.now();
    return new RunnerExecutionSnapshot(
        REX,
        RUN_ID,
        RunnerStage.INVESTIGATION,
        RunnerExecutionStatus.RUNNING,
        1,
        now,
        now,
        null,
        null,
        now,
        null);
  }

  /** Run a git command, asserting success. */
  private static void run(Path workingDir, String... args) throws Exception {
    String[] cmd = new String[args.length + 1];
    cmd[0] = "git";
    System.arraycopy(args, 0, cmd, 1, args.length);
    ProcessBuilder pb =
        new ProcessBuilder(cmd).directory(workingDir.toFile()).redirectErrorStream(true);
    pb.environment().put("GIT_TERMINAL_PROMPT", "0");
    Process p = pb.start();
    String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    if (!p.waitFor(60, TimeUnit.SECONDS)) {
      p.destroyForcibly();
      throw new IllegalStateException("git " + String.join(" ", args) + " timed out");
    }
    if (p.exitValue() != 0) {
      throw new IllegalStateException("git " + String.join(" ", args) + " failed: " + out);
    }
  }

  /** Run a git command and return its trimmed stdout. */
  private static String runCapture(Path workingDir, String... args) throws Exception {
    String[] cmd = new String[args.length + 1];
    cmd[0] = "git";
    System.arraycopy(args, 0, cmd, 1, args.length);
    ProcessBuilder pb = new ProcessBuilder(cmd).directory(workingDir.toFile());
    Process p = pb.start();
    String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    p.waitFor(60, TimeUnit.SECONDS);
    return out.strip();
  }

  private static boolean runQuiet(String... cmd) {
    try {
      Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
      p.getInputStream().readAllBytes();
      return p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0;
    } catch (IOException | InterruptedException e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      return false;
    }
  }

  private static boolean runCloneSmoke() {
    try {
      Path dir = Files.createTempDirectory("deliveryline-git-smoke");
      Path remote = dir.resolve("remote.git");
      Path clone = dir.resolve("clone");
      run(dir, "init", "--bare", remote.toString());
      run(dir, "clone", remote.toString(), clone.toString());
      return true;
    } catch (Exception smokeFailure) {
      return false;
    }
  }

  private static List<String> warnMessages(ListAppender<ILoggingEvent> appender) {
    return appender.list.stream()
        .filter(e -> e.getLevel() == Level.WARN)
        .map(ILoggingEvent::getFormattedMessage)
        .toList();
  }

  private static ListAppender<ILoggingEvent> attach(Class<?> type) {
    Logger logger = (Logger) LoggerFactory.getLogger(type);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    return appender;
  }

  private static void detach(Class<?> type, ListAppender<ILoggingEvent> appender) {
    ((Logger) LoggerFactory.getLogger(type)).detachAppender(appender);
  }
}
