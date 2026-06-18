package org.dradgo.application.runner.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
import org.dradgo.application.clarification.spi.ClarificationReadPort;
import org.dradgo.application.integration.repohost.RepositoryHostAdapter;
import org.dradgo.application.integration.spi.IntegrationLinkRecordPort;
import org.dradgo.application.runner.ContextBundle;
import org.dradgo.application.runner.ContextBundleService;
import org.dradgo.application.runner.ExecutionConstraints;
import org.dradgo.application.runner.ExecutionSubStage;
import org.dradgo.application.runner.RunnerSecretsService;
import org.dradgo.application.runner.TicketSummary;
import org.dradgo.application.runner.spi.RunnerExecutionRecordPort;
import org.dradgo.application.runner.spi.TicketSummaryProvider;
import org.dradgo.application.security.DataClassificationService;
import org.dradgo.application.security.RedactionPolicyService;
import org.dradgo.application.workflow.WorkflowProperties;
import org.dradgo.domain.integration.repohost.Repository;
import org.dradgo.domain.integration.repohost.RepositoryRef;
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
 * Story 3.10 (AC9 integration + AC4/AC2) — end-to-end execution-stage repo-context path against a
 * LOCAL bare git repo ({@code git init --bare}, local-path remote; no gitea/Docker/network,
 * cross-platform — mirrors {@link SpecStageRepoContextIT}). Exercises the REAL {@link
 * CliGitAdapter} + REAL {@link RepositoryWorkspaceService#prepareWorkspace}/{@link
 * RepositoryWorkspaceService#summarize} for the EXECUTION stage with a real ticketRef (so the
 * deterministic branch resolves, story 3.9 AC2) + REAL {@link ContextBundleService} PR-output
 * composition (real {@link RedactionPolicyService} + {@link RunnerContractValidator}).
 *
 * <p>Requires {@code git} on PATH (skipped via {@code assumeTrue} otherwise). Named {@code *IT} so
 * it runs in the Failsafe tier and is excluded from the no-Docker fast Surefire tier (memory:
 * springboot-testcontainers-test-must-be-IT).
 */
class ImplementationStageRepoContextIT {

  private static final String REX = "rex_implctx0000001";
  private static final String RUN_ID = "run_implctx1234567";
  private static final String REPO_REF = "GH-101";
  private static final String TICKET_REF = "DL-310";
  private static final String TOKEN = "ghp_FAKEtoken0000000000000000000000000000";
  private static final String LEAK_TOKEN = "ghp_1234567890abcdef1234567890abcdef1234";
  private static final ExecutionConstraints CONSTRAINTS =
      new ExecutionConstraints(Duration.ofSeconds(1800), false);
  private static final ActorContext ACTOR =
      new ActorContext("system-broker", ActorType.SYSTEM, "corr-implctx-1");

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
    RepositoryHostAdapter gitHubAdapter = Mockito.mock(RepositoryHostAdapter.class);
    RunnerSecretsService secrets = Mockito.mock(RunnerSecretsService.class);
    RunnerExecutionRecordPort recordPort = Mockito.mock(RunnerExecutionRecordPort.class);
    IntegrationLinkRecordPort links = Mockito.mock(IntegrationLinkRecordPort.class);

    when(gitHubAdapter.getRepositoryByRef(RepositoryRef.of(REPO_REF)))
        .thenReturn(
            Optional.of(
                new Repository(RepositoryRef.of(REPO_REF), "owner/repo", "main", remoteUrl)));
    when(secrets.resolveHostSecret("GITHUB_TOKEN")).thenReturn(Optional.of(TOKEN));
    when(links.findActiveByWorkflowRun(anyString())).thenReturn(Optional.empty());

    WorkflowProperties props =
        new WorkflowProperties(
            WorkflowProperties.Bot.empty(),
            WorkflowProperties.RepoConfig.of(REPO_REF),
            WorkflowProperties.LinearCompletionSync.defaults());
    service =
        new RepositoryWorkspaceService(
            git, gitHubAdapter, secrets, store, recordPort, links, props);
  }

  @Test
  void prOutputExecutionBundleCarriesRepoContextAndDeterministicBranchWithNoLeak()
      throws Exception {
    // EXECUTION stage with a real ticketRef → the deterministic feature branch (story 3.9 AC2).
    RepositoryWorkspaceService.RepositoryMount mount =
        service.prepareWorkspace(RUN_ID, RunnerStage.EXECUTION, REX, TICKET_REF, null, REPO_REF);
    assertThat(mount.branch()).startsWith("deliveryline/DL-310/stage-");

    RepositoryContextSummary summary =
        service.summarize(mount, RepositoryWorkspaceService.configMappingVersion(REPO_REF));

    ContextBundle bundle =
        executionBundleService()
            .create(
                RUN_ID,
                RunnerStage.EXECUTION,
                REX,
                1,
                CONSTRAINTS,
                DataClassification.SHAREABLE_REDACTED,
                ACTOR,
                ExecutionSubStage.PR_OUTPUT,
                summary,
                mount.branch());

    JsonNode tree = objectMapper.readTree(bundle.redactedPayload());
    assertThat(tree.get("repositoryWorkspaceRef").asText()).isEqualTo("/workspace/repo");
    assertThat(tree.get("repositoryTreeSummary").isArray()).isTrue();
    assertThat(tree.get("repositoryReadmeRef").asText()).isEqualTo("README.md");
    assertThat(tree.get("ticketRepositoryMappingVersion").asText()).isEqualTo("config:GH-101@1");
    // AC2 — the expected deterministic branch ref is the mount's branch (a ref, never a host path).
    assertThat(tree.get("repositoryBranchRef").asText()).isEqualTo(mount.branch());
    // PR-output declares the approved-plan slot (null here — no available plan seeded).
    assertThat(tree.get("approvedImplementationPlanReference").isNull()).isTrue();

    String serialized = new String(bundle.redactedPayload(), StandardCharsets.UTF_8);
    // AC4/Trap T5 — token embedded in a committed filename redacted; no host path; branch is a ref.
    assertThat(serialized).doesNotContain(LEAK_TOKEN);
    assertThat(serialized).doesNotContain(mount.repoHostPath().toString());
    assertThat(serialized).doesNotContain(home.toString());
  }

  @Test
  void executionBundleCompositionLogsSubStageAndRepoContextWithoutLeak() throws Exception {
    RepositoryWorkspaceService.RepositoryMount mount =
        service.prepareWorkspace(RUN_ID, RunnerStage.EXECUTION, REX, TICKET_REF, null, REPO_REF);
    RepositoryContextSummary summary =
        service.summarize(mount, RepositoryWorkspaceService.configMappingVersion(REPO_REF));

    Logger logger = (Logger) LoggerFactory.getLogger(ContextBundleService.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      executionBundleService()
          .create(
              RUN_ID,
              RunnerStage.EXECUTION,
              REX,
              1,
              CONSTRAINTS,
              DataClassification.SHAREABLE_REDACTED,
              ACTOR,
              ExecutionSubStage.PR_OUTPUT,
              summary,
              mount.branch());

      List<ILoggingEvent> events = appender.list;
      assertThat(events)
          .anyMatch(
              e ->
                  e.getLevel() == Level.INFO
                      && e.getFormattedMessage().contains("create context-bundle ok")
                      && e.getFormattedMessage().contains("subStage=PR_OUTPUT")
                      && e.getFormattedMessage().contains("repoContextPresent=true"));
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

  private ContextBundleService executionBundleService() {
    TicketSummaryProvider ticketProvider = Mockito.mock(TicketSummaryProvider.class);
    ArtifactRecordPort artifactRecordPort = Mockito.mock(ArtifactRecordPort.class);
    ApprovalReadPort approvalReadPort = Mockito.mock(ApprovalReadPort.class);
    ClarificationReadPort clarificationReadPort = Mockito.mock(ClarificationReadPort.class);
    when(ticketProvider.fetchByWorkflowRun(RUN_ID))
        .thenReturn(new TicketSummary(TICKET_REF, "Export pipeline", "PR output."));
    when(artifactRecordPort.findLatestByWorkflowRunIdAndArtifactType(eq(RUN_ID), any()))
        .thenReturn(Optional.empty());
    when(approvalReadPort.listByWorkflowRunAndArtifactType(RUN_ID, "spec")).thenReturn(List.of());
    when(approvalReadPort.listRejectionsByWorkflowRunAndArtifactType(RUN_ID, "implementationPlan"))
        .thenReturn(List.of());
    when(clarificationReadPort.listByWorkflowRunId(RUN_ID)).thenReturn(List.of());
    return new ContextBundleService(
        ticketProvider,
        artifactRecordPort,
        approvalReadPort,
        clarificationReadPort,
        new RedactionPolicyService(new DataClassificationService()),
        new RunnerContractValidator());
  }

  private void seedBareRemote() throws Exception {
    run(tmp, "init", "--bare", remoteBare.toString());
    run(remoteBare, "symbolic-ref", "HEAD", "refs/heads/main");
    Path seed = tmp.resolve("seed");
    run(tmp, "clone", remoteBare.toString(), seed.toString());
    Files.writeString(seed.resolve("README.md"), "# Seed repository\n");
    Files.writeString(seed.resolve("pom.xml"), "<project></project>\n");
    Files.writeString(seed.resolve(".gitignore"), "ignored/\n");
    Files.createDirectories(seed.resolve("src/main/java"));
    Files.writeString(seed.resolve("src/main/java/App.java"), "class App {}\n");
    // A file whose NAME embeds a token — committed, so it reaches the tree summary path (AC4).
    Files.writeString(seed.resolve(LEAK_TOKEN + ".txt"), "x\n");
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
