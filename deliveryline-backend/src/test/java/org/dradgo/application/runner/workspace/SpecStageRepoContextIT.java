package org.dradgo.application.runner.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.dradgo.adapters.files.LocalRunnerWorkspaceStore;
import org.dradgo.adapters.git.CliGitAdapter;
import org.dradgo.application.approval.spi.ApprovalReadPort;
import org.dradgo.application.artifact.ActorContext;
import org.dradgo.application.artifact.spi.ArtifactRecordPort;
import org.dradgo.application.integration.github.GitHubAdapter;
import org.dradgo.application.integration.github.GitHubRepository;
import org.dradgo.application.integration.spi.IntegrationLinkRecordPort;
import org.dradgo.application.runner.ContextBundle;
import org.dradgo.application.runner.ContextBundleService;
import org.dradgo.application.runner.ExecutionConstraints;
import org.dradgo.application.runner.RunnerSecretsService;
import org.dradgo.application.runner.TicketSummary;
import org.dradgo.application.runner.spi.RunnerExecutionRecordPort;
import org.dradgo.application.runner.spi.TicketSummaryProvider;
import org.dradgo.application.runner.workspace.spi.RepoTreeEntry;
import org.dradgo.application.security.DataClassificationService;
import org.dradgo.application.security.RedactionPolicyService;
import org.dradgo.application.workflow.WorkflowProperties;
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.DataClassification;
import org.dradgo.domain.registry.RunnerStage;
import org.dradgo.runnercontracts.RunnerContractValidator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;

/**
 * Story 3a-2 (AC11 e/f, AC3) — end-to-end repo-context path against a LOCAL bare git repo ({@code
 * git init --bare}, local-path remote; no gitea/Docker/network, cross-platform — mirrors {@code
 * RepositoryWorkspaceServiceIT}). Exercises the REAL {@link CliGitAdapter} tree-listing op + REAL
 * {@link RepositoryWorkspaceService#summarize} + REAL {@link ContextBundleService} composition
 * (real {@link RedactionPolicyService} + {@link RunnerContractValidator}); the GitHub adapter +
 * secrets + ports are mocked.
 *
 * <p>Requires {@code git} on PATH (CI Failsafe tier has it; skipped via {@code assumeTrue}
 * otherwise). Named {@code *IT} so it runs in the Failsafe/Docker tier and is excluded from the
 * no-Docker fast Surefire tier (memory: a real-git/Docker test name must be {@code *IT}).
 */
class SpecStageRepoContextIT {

  private static final String REX = "rex_repoctx0000001";
  private static final String RUN_ID = "run_repoctx1234567";
  private static final String REPO_REF = "GH-101";
  private static final String TOKEN = "ghp_FAKEtoken0000000000000000000000000000";
  // A committed filename embedding a token — proves repo-derived path content is redacted (AC3).
  private static final String LEAK_TOKEN = "ghp_1234567890abcdef1234567890abcdef1234";
  private static final ExecutionConstraints CONSTRAINTS =
      new ExecutionConstraints(Duration.ofSeconds(600), false);
  private static final ActorContext ACTOR =
      new ActorContext("system-broker", ActorType.SYSTEM, "corr-repoctx-1");

  private static boolean gitAvailable;

  @TempDir Path tmp;
  private Path remoteBare;
  private Path home;

  private RepositoryWorkspaceService service;
  private final ObjectMapper objectMapper = new ObjectMapper();

  @BeforeAll
  static void detectGit() {
    gitAvailable = runQuiet("git", "--version");
  }

  @BeforeEach
  void setUp() throws Exception {
    assumeTrue(gitAvailable, "git not on PATH — skipping local-bare-repo IT");

    home = Files.createDirectories(tmp.resolve("home"));
    remoteBare = tmp.resolve("remote.git");
    seedBareRemote();
    String remoteUrl = remoteBare.toAbsolutePath().toString();

    LocalRunnerWorkspaceStore store = new LocalRunnerWorkspaceStore(home.toString());
    CliGitAdapter git =
        new CliGitAdapter(new RedactionPolicyService(new DataClassificationService()));
    GitHubAdapter gitHubAdapter = Mockito.mock(GitHubAdapter.class);
    RunnerSecretsService secrets = Mockito.mock(RunnerSecretsService.class);
    RunnerExecutionRecordPort recordPort = Mockito.mock(RunnerExecutionRecordPort.class);
    IntegrationLinkRecordPort links = Mockito.mock(IntegrationLinkRecordPort.class);

    when(gitHubAdapter.getRepositoryByRef(REPO_REF))
        .thenReturn(Optional.of(new GitHubRepository(REPO_REF, "owner/repo", "main", remoteUrl)));
    when(secrets.resolveHostSecret("GITHUB_TOKEN")).thenReturn(Optional.of(TOKEN));
    when(links.findActiveByWorkflowRun(anyString())).thenReturn(Optional.empty());

    // Configured repo url → the broker's config resolver normalizes it to REPO_REF (1:1 pilot, D2).
    WorkflowProperties props =
        new WorkflowProperties(
            WorkflowProperties.Bot.empty(), WorkflowProperties.RepoConfig.of(REPO_REF));
    service =
        new RepositoryWorkspaceService(
            git, gitHubAdapter, secrets, store, recordPort, links, props);
  }

  @Test
  void treeListingIsDepthBoundedGitignoreRespectingAndDeterministic() {
    RepositoryWorkspaceService.RepositoryMount mount =
        service.prepareWorkspace(RUN_ID, RunnerStage.INVESTIGATION, REX, null, null, REPO_REF);

    CliGitAdapter git =
        new CliGitAdapter(new RedactionPolicyService(new DataClassificationService()));
    List<RepoTreeEntry> tree =
        git.listTopLevelTree(mount.repoHostPath(), RepositoryWorkspaceService.TREE_DEPTH_LIMIT);

    List<String> paths = tree.stream().map(RepoTreeEntry::path).toList();
    // Deterministic ascending order.
    assertThat(paths).isSorted();
    // Depth limit (2): the deep src/main/java/App.java is truncated to the src/main directory.
    assertThat(paths).contains("src", "src/main");
    assertThat(paths).doesNotContain("src/main/java", "src/main/java/App.java");
    assertThat(typeOf(tree, "src")).isEqualTo(RepoTreeEntry.Type.DIR);
    assertThat(typeOf(tree, "src/main")).isEqualTo(RepoTreeEntry.Type.DIR);
    // .gitignore respect: the ignored (never-committed) file is absent from HEAD's tree.
    assertThat(paths).noneMatch(p -> p.startsWith("ignored"));
    // Top-level manifests + README are present as files.
    assertThat(typeOf(tree, "README.md")).isEqualTo(RepoTreeEntry.Type.FILE);
    assertThat(typeOf(tree, "package.json")).isEqualTo(RepoTreeEntry.Type.FILE);
  }

  @Test
  void summarizeDetectsReadmeManifestsAndMountRelativePaths() {
    RepositoryWorkspaceService.RepositoryMount mount =
        service.prepareWorkspace(RUN_ID, RunnerStage.INVESTIGATION, REX, null, null, REPO_REF);

    RepositoryContextSummary summary =
        service.summarize(mount, RepositoryWorkspaceService.configMappingVersion(REPO_REF));

    assertThat(summary.mountPath()).isEqualTo("/workspace/repo");
    assertThat(summary.mappingVersion()).isEqualTo("config:GH-101@1");
    assertThat(summary.readmeRef()).isEqualTo("README.md");
    assertThat(summary.manifestRefs())
        .extracting(RepoManifestRef::kind)
        .contains("package.json", "pom.xml");
    // Every manifest path is mount-relative (no leading slash / no host path, Trap T7).
    assertThat(summary.manifestRefs())
        .allSatisfy(
            m -> assertThat(m.path()).doesNotStartWith("/").doesNotContain(home.toString()));
    assertThat(summary.treeSummary()).isNotEmpty();
  }

  @Test
  void composedSpecBundleCarriesRepoContextWithNoTokenOrHostPathLeak() throws Exception {
    RepositoryWorkspaceService.RepositoryMount mount =
        service.prepareWorkspace(RUN_ID, RunnerStage.INVESTIGATION, REX, null, null, REPO_REF);

    RepositoryContextSummary summary =
        service.summarize(mount, RepositoryWorkspaceService.configMappingVersion(REPO_REF));

    TicketSummaryProvider ticketProvider = Mockito.mock(TicketSummaryProvider.class);
    ArtifactRecordPort artifactRecordPort = Mockito.mock(ArtifactRecordPort.class);
    ApprovalReadPort approvalReadPort = Mockito.mock(ApprovalReadPort.class);
    when(ticketProvider.fetchByWorkflowRun(RUN_ID))
        .thenReturn(new TicketSummary("DL-205", "Add export pipeline", "Spec investigation."));
    when(approvalReadPort.listRejectionsByWorkflowRunAndArtifactType(RUN_ID, "spec"))
        .thenReturn(List.of());
    when(artifactRecordPort.listByWorkflowRunIdAndArtifactType(RUN_ID, "spec"))
        .thenReturn(List.of());

    ContextBundleService bundleService =
        new ContextBundleService(
            ticketProvider,
            artifactRecordPort,
            approvalReadPort,
            new RedactionPolicyService(new DataClassificationService()),
            new RunnerContractValidator());

    ContextBundle bundle =
        bundleService.createForSpecInvestigation(
            RUN_ID, REX, 1, CONSTRAINTS, DataClassification.SHAREABLE_REDACTED, ACTOR, summary);

    JsonNode tree = objectMapper.readTree(bundle.redactedPayload());
    // AC1 — the five repo fields are present and schema-valid (validation already ran in compose).
    assertThat(tree.get("repositoryWorkspaceRef").asText()).isEqualTo("/workspace/repo");
    assertThat(tree.get("repositoryTreeSummary").isArray()).isTrue();
    assertThat(tree.get("repositoryReadmeRef").asText()).isEqualTo("README.md");
    assertThat(tree.get("packageManifestRefs").size()).isGreaterThanOrEqualTo(2);
    assertThat(tree.get("ticketRepositoryMappingVersion").asText()).isEqualTo("config:GH-101@1");

    String serialized = new String(bundle.redactedPayload(), StandardCharsets.UTF_8);
    // AC3 — the token embedded in a committed filename is redacted (adapter line-redaction +
    // bundle redaction backstop); the persisted bundle carries no host absolute path (Trap T7).
    assertThat(serialized).doesNotContain(LEAK_TOKEN);
    assertThat(serialized).doesNotContain(mount.repoHostPath().toString());
    assertThat(serialized).doesNotContain(home.toString());
  }

  @Test
  void summarizeLogsFieldOnlyRepoContextWithoutTokenOrHostPath() {
    Logger logger = (Logger) LoggerFactory.getLogger(RepositoryWorkspaceService.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      RepositoryWorkspaceService.RepositoryMount mount =
          service.prepareWorkspace(RUN_ID, RunnerStage.INVESTIGATION, REX, null, null, REPO_REF);
      service.summarize(mount, RepositoryWorkspaceService.configMappingVersion(REPO_REF));

      List<ILoggingEvent> events = appender.list;
      // The field-only "summarize ok" line is emitted at INFO with counts (Logging task).
      assertThat(events)
          .anyMatch(
              e ->
                  e.getLevel() == Level.INFO
                      && e.getFormattedMessage().contains("summarize ok")
                      && e.getFormattedMessage().contains("treeEntryCount=")
                      && e.getFormattedMessage().contains("readmePresent="));
      // No git token and no host absolute path is ever serialized into a log line.
      assertThat(events)
          .allSatisfy(
              e -> {
                assertThat(e.getFormattedMessage()).doesNotContain(TOKEN);
                assertThat(e.getFormattedMessage()).doesNotContain(LEAK_TOKEN);
                assertThat(e.getFormattedMessage()).doesNotContain(home.toString());
              });
    } finally {
      logger.detachAppender(appender);
    }
  }

  // =====================================================================
  // helpers
  // =====================================================================

  private static RepoTreeEntry.Type typeOf(List<RepoTreeEntry> tree, String path) {
    return tree.stream()
        .filter(e -> e.path().equals(path))
        .map(RepoTreeEntry::type)
        .findFirst()
        .orElse(null);
  }

  private void seedBareRemote() throws Exception {
    run(tmp, "init", "--bare", remoteBare.toString());
    run(remoteBare, "symbolic-ref", "HEAD", "refs/heads/main");
    Path seed = tmp.resolve("seed");
    run(tmp, "clone", remoteBare.toString(), seed.toString());
    Files.writeString(seed.resolve("README.md"), "# Seed repository\n");
    Files.writeString(seed.resolve("package.json"), "{\"name\": \"seed\"}\n");
    Files.writeString(seed.resolve("pom.xml"), "<project></project>\n");
    Files.writeString(seed.resolve(".gitignore"), "ignored/\n");
    Files.createDirectories(seed.resolve("src/main/java"));
    Files.writeString(seed.resolve("src/main/java/App.java"), "class App {}\n");
    Files.createDirectories(seed.resolve("docs"));
    Files.writeString(seed.resolve("docs/notes.md"), "notes\n");
    // A file whose NAME embeds a token — committed, so it reaches the tree summary path.
    Files.writeString(seed.resolve(LEAK_TOKEN + ".txt"), "x\n");
    // An ignored file that must never reach the committed tree (.gitignore respect).
    Files.createDirectories(seed.resolve("ignored"));
    Files.writeString(seed.resolve("ignored/secret.txt"), "should-not-be-committed\n");
    run(seed, "-c", "user.email=s@t", "-c", "user.name=Seed", "add", "-A");
    run(seed, "-c", "user.email=s@t", "-c", "user.name=Seed", "commit", "-m", "seed commit");
    run(seed, "branch", "-M", "main");
    run(seed, "push", "origin", "main");
  }

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
}
