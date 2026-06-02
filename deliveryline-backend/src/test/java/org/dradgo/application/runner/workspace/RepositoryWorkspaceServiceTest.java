package org.dradgo.application.runner.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.dradgo.application.integration.IntegrationLink;
import org.dradgo.application.integration.github.GitHubAdapter;
import org.dradgo.application.integration.github.GitHubAdapterException;
import org.dradgo.application.integration.github.GitHubPullRequest;
import org.dradgo.application.integration.github.GitHubRepository;
import org.dradgo.application.integration.spi.IntegrationLinkRecordPort;
import org.dradgo.application.runner.RunnerSecretsService;
import org.dradgo.application.runner.spi.RunnerExecutionRecordPort;
import org.dradgo.application.runner.spi.RunnerExecutionSnapshot;
import org.dradgo.application.runner.spi.RunnerWorkspaceStore;
import org.dradgo.application.runner.workspace.spi.GitCommandException;
import org.dradgo.application.runner.workspace.spi.GitCommandPort;
import org.dradgo.application.workflow.WorkflowProperties;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.IntegrationFailureCategory;
import org.dradgo.domain.registry.IntegrationSyncStatus;
import org.dradgo.domain.registry.RunnerExecutionStatus;
import org.dradgo.domain.registry.RunnerStage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Story 3.9 — fast unit coverage for {@link RepositoryWorkspaceService} (git port fully mocked).
 */
class RepositoryWorkspaceServiceTest {

  private static final String REX = "rex_repounit000001";
  private static final String RUN_ID = "run_abcdef123456";
  private static final String REPO_REF = "GH-101";
  private static final Path REPO_DIR = Path.of("/tmp/wsroot/" + REX + "/repo");

  private GitCommandPort git;
  private GitHubAdapter gitHubAdapter;
  private RunnerSecretsService secrets;
  private RunnerWorkspaceStore store;
  private RunnerExecutionRecordPort recordPort;
  private IntegrationLinkRecordPort links;
  private RepositoryWorkspaceService service;

  @BeforeEach
  void setUp() {
    git = Mockito.mock(GitCommandPort.class);
    gitHubAdapter = Mockito.mock(GitHubAdapter.class);
    secrets = Mockito.mock(RunnerSecretsService.class);
    store = Mockito.mock(RunnerWorkspaceStore.class);
    recordPort = Mockito.mock(RunnerExecutionRecordPort.class);
    links = Mockito.mock(IntegrationLinkRecordPort.class);
    when(secrets.resolveHostSecret(anyString())).thenReturn(Optional.empty());
    when(links.findActiveByWorkflowRun(anyString())).thenReturn(Optional.empty());
    service =
        new RepositoryWorkspaceService(
            git, gitHubAdapter, secrets, store, recordPort, links, WorkflowProperties.defaults());
  }

  // ---- AC9 guard ----

  @Test
  void prepareWorkspaceThrowsMismatchWhenRepoUnresolvable() {
    when(gitHubAdapter.getRepositoryByRef("GH-unknown")).thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                service.prepareWorkspace(
                    RUN_ID, RunnerStage.INVESTIGATION, REX, "DEL-9", "GH-unknown"))
        .isInstanceOf(DomainException.class)
        .satisfies(
            e ->
                assertThat(((DomainException) e).errorCode())
                    .isEqualTo(DomainErrorCode.LINEAR_GITHUB_REPO_MISMATCH));

    verify(git, never()).cloneRepository(any(), any());
  }

  @Test
  void prepareWorkspaceThrowsMismatchOnBlankRepoRef() {
    assertThatThrownBy(
            () -> service.prepareWorkspace(RUN_ID, RunnerStage.INVESTIGATION, REX, "DEL-9", "  "))
        .isInstanceOf(DomainException.class);
    verify(gitHubAdapter, never()).getRepositoryByRef(any());
  }

  @Test
  void prepareWorkspaceThrowsMismatchWhenExistingGithubLinkPointsAtDifferentRepo() {
    when(gitHubAdapter.getRepositoryByRef(REPO_REF))
        .thenReturn(Optional.of(new GitHubRepository(REPO_REF, "owner/repo", "main", "url")));
    when(links.findActiveByWorkflowRun(RUN_ID))
        .thenReturn(
            Optional.of(
                new IntegrationLink(
                    "ilk_1",
                    RUN_ID,
                    "github_pr",
                    "PR-9",
                    IntegrationSyncStatus.LINKED,
                    Instant.now(),
                    null,
                    null)));
    when(gitHubAdapter.getPullRequestByRef("PR-9"))
        .thenReturn(
            Optional.of(
                new GitHubPullRequest("PR-9", "GH-OTHER", 9, "b", "open", "u", Instant.now())));

    assertThatThrownBy(
            () ->
                service.prepareWorkspace(RUN_ID, RunnerStage.INVESTIGATION, REX, "DEL-9", REPO_REF))
        .isInstanceOf(DomainException.class)
        .satisfies(
            e ->
                assertThat(((DomainException) e).errorCode())
                    .isEqualTo(DomainErrorCode.LINEAR_GITHUB_REPO_MISMATCH));
    verify(git, never()).cloneRepository(any(), any());
  }

  // ---- prepareWorkspace happy ----

  @Test
  void prepareWorkspaceClonesChecksOutStampsConfigAndReturnsMount() {
    when(gitHubAdapter.getRepositoryByRef(REPO_REF))
        .thenReturn(
            Optional.of(new GitHubRepository(REPO_REF, "owner/repo", "main", "git://remote")));
    when(store.prepareRepositoryDir(REX)).thenReturn(REPO_DIR);
    when(git.checkoutOrReuseBranch(eq(REPO_DIR), anyString(), any()))
        .thenReturn(GitCommandPort.BranchOutcome.CREATED);

    RepositoryWorkspaceService.RepositoryMount mount =
        service.prepareWorkspace(
            RUN_ID, RunnerStage.INVESTIGATION, REX, "DEL-9", "Fix flaky dispatch", REPO_REF);

    assertThat(mount.repoHostPath()).isEqualTo(REPO_DIR);
    assertThat(mount.containerMountPath()).isEqualTo("/workspace/repo");
    assertThat(mount.defaultBranch()).isEqualTo("main");
    assertThat(mount.branch()).isEqualTo("deliveryline/DEL-9/stage-ef123456");
    verify(git).cloneRepository(any(GitCommandPort.CloneSpec.class), any());
    verify(git).configureIdentity(eq(REPO_DIR), anyString(), anyString());
    verify(git).setLocalConfig(REPO_DIR, "deliveryline.repoRef", REPO_REF);
    verify(git).setLocalConfig(REPO_DIR, "deliveryline.ticketSummary", "Fix flaky dispatch");
  }

  // ---- captureAndPush ----

  @Test
  void captureAndPushNoOpWhenNoRepoWorkspace() {
    when(store.resolveRepositoryDir(REX)).thenReturn(Optional.empty());

    Optional<RepositoryWorkspaceService.RepositoryPushOutcome> outcome =
        service.captureAndPush(REX);

    assertThat(outcome).isEmpty();
    Mockito.verifyNoInteractions(git);
  }

  @Test
  void captureAndPushCommitsPushesAndCreatesPullRequest() {
    when(store.resolveRepositoryDir(REX)).thenReturn(Optional.of(REPO_DIR));
    when(recordPort.findByPublicId(REX)).thenReturn(Optional.of(snapshot()));
    when(git.currentBranch(REPO_DIR)).thenReturn("deliveryline/DEL-9/stage-ef123456");
    when(git.getLocalConfig(REPO_DIR, "deliveryline.repoRef")).thenReturn(Optional.of(REPO_REF));
    when(git.getLocalConfig(REPO_DIR, "deliveryline.ticketRef")).thenReturn(Optional.of("DEL-9"));
    when(git.getLocalConfig(REPO_DIR, "deliveryline.ticketSummary"))
        .thenReturn(Optional.of("Fix flaky dispatch"));
    when(git.getLocalConfig(REPO_DIR, "deliveryline.stage"))
        .thenReturn(Optional.of("investigation"));
    when(git.hasUncommittedChanges(REPO_DIR)).thenReturn(true);
    when(git.commitAll(any())).thenReturn("abc1234");
    when(git.push(eq(REPO_DIR), anyString(), any()))
        .thenReturn(
            new GitCommandPort.PushResult(
                "abc1234", "deliveryline/DEL-9/stage-ef123456", "git://remote"));
    when(gitHubAdapter.createPullRequest(eq(REPO_REF), anyString(), anyString(), anyString()))
        .thenReturn(new GitHubPullRequest("PR-1", REPO_REF, 1, "b", "open", "u", Instant.now()));

    Optional<RepositoryWorkspaceService.RepositoryPushOutcome> outcome =
        service.captureAndPush(REX);

    assertThat(outcome).isPresent();
    assertThat(outcome.get().committed()).isTrue();
    assertThat(outcome.get().commitSha()).isEqualTo("abc1234");
    assertThat(outcome.get().prRef()).isEqualTo("PR-1");
    verify(gitHubAdapter)
        .createPullRequest(
            eq(REPO_REF),
            eq("deliveryline/DEL-9/stage-ef123456"),
            eq("[DEL-9] Fix flaky dispatch"),
            anyString());

    org.mockito.ArgumentCaptor<GitCommandPort.CommitSpec> commitCaptor =
        org.mockito.ArgumentCaptor.forClass(GitCommandPort.CommitSpec.class);
    verify(git).commitAll(commitCaptor.capture());
    assertThat(commitCaptor.getValue().message())
        .contains("Deliveryline-Run: " + RUN_ID)
        .contains("Deliveryline-Stage: investigation")
        .contains("Deliveryline-RunnerExecution: " + REX);
  }

  @Test
  void captureAndPushSkipsPushAndPrWhenWorktreeIsClean() {
    when(store.resolveRepositoryDir(REX)).thenReturn(Optional.of(REPO_DIR));
    when(recordPort.findByPublicId(REX)).thenReturn(Optional.of(snapshot()));
    when(git.currentBranch(REPO_DIR)).thenReturn("deliveryline/DEL-9/stage-ef123456");
    when(git.getLocalConfig(any(), anyString())).thenReturn(Optional.empty());
    when(git.hasUncommittedChanges(REPO_DIR)).thenReturn(false);

    Optional<RepositoryWorkspaceService.RepositoryPushOutcome> outcome =
        service.captureAndPush(REX);

    assertThat(outcome).isEmpty();
    verify(git, never()).push(any(), anyString(), any());
    verify(gitHubAdapter, never())
        .createPullRequest(anyString(), anyString(), anyString(), anyString());
    verify(gitHubAdapter, never()).updatePullRequest(anyString(), anyString());
  }

  @Test
  void captureAndPushMapsPrCreateFailureToTypedGitFailure() {
    when(store.resolveRepositoryDir(REX)).thenReturn(Optional.of(REPO_DIR));
    when(recordPort.findByPublicId(REX)).thenReturn(Optional.of(snapshot()));
    when(git.currentBranch(REPO_DIR)).thenReturn("deliveryline/DEL-9/stage-ef123456");
    when(git.getLocalConfig(REPO_DIR, "deliveryline.repoRef")).thenReturn(Optional.of(REPO_REF));
    when(git.getLocalConfig(REPO_DIR, "deliveryline.ticketRef")).thenReturn(Optional.of("DEL-9"));
    when(git.getLocalConfig(REPO_DIR, "deliveryline.ticketSummary"))
        .thenReturn(Optional.of("Fix flaky dispatch"));
    when(git.getLocalConfig(REPO_DIR, "deliveryline.stage"))
        .thenReturn(Optional.of("investigation"));
    when(git.hasUncommittedChanges(REPO_DIR)).thenReturn(true);
    when(git.commitAll(any())).thenReturn("abc1234");
    when(git.push(eq(REPO_DIR), anyString(), any()))
        .thenReturn(
            new GitCommandPort.PushResult(
                "abc1234", "deliveryline/DEL-9/stage-ef123456", "git://remote"));
    when(gitHubAdapter.createPullRequest(anyString(), anyString(), anyString(), anyString()))
        .thenThrow(
            new GitHubAdapterException(
                IntegrationFailureCategory.GITHUB_NETWORK_FAILURE, "github unavailable"));

    assertThatThrownBy(() -> service.captureAndPush(REX))
        .isInstanceOf(GitCommandException.class)
        .satisfies(
            e ->
                assertThat(((GitCommandException) e).failureCategory())
                    .isEqualTo(IntegrationFailureCategory.GIT_NETWORK_FAILURE));
  }

  @Test
  void prepareWorkspaceTreatsEmptyExistingGithubLinkAsMismatchBeforeClone() {
    when(gitHubAdapter.getRepositoryByRef(REPO_REF))
        .thenReturn(Optional.of(new GitHubRepository(REPO_REF, "owner/repo", "main", "url")));
    when(links.findActiveByWorkflowRun(RUN_ID))
        .thenReturn(
            Optional.of(
                new IntegrationLink(
                    "ilk_1",
                    RUN_ID,
                    "github_pr",
                    "PR-9",
                    IntegrationSyncStatus.LINKED,
                    Instant.now(),
                    null,
                    null)));
    when(gitHubAdapter.getPullRequestByRef("PR-9")).thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                service.prepareWorkspace(
                    RUN_ID, RunnerStage.INVESTIGATION, REX, "DEL-9", "Fix dispatch", REPO_REF))
        .isInstanceOf(DomainException.class)
        .satisfies(
            e ->
                assertThat(((DomainException) e).errorCode())
                    .isEqualTo(DomainErrorCode.LINEAR_GITHUB_REPO_MISMATCH));
    verify(git, never()).cloneRepository(any(), any());
  }

  @Test
  void captureAndPushPropagatesPushRejection() {
    when(store.resolveRepositoryDir(REX)).thenReturn(Optional.of(REPO_DIR));
    when(recordPort.findByPublicId(REX)).thenReturn(Optional.of(snapshot()));
    when(git.currentBranch(REPO_DIR)).thenReturn("b");
    when(git.getLocalConfig(any(), anyString())).thenReturn(Optional.empty());
    when(git.hasUncommittedChanges(REPO_DIR)).thenReturn(true);
    when(git.commitAll(any())).thenReturn("abc1234");
    when(git.push(eq(REPO_DIR), anyString(), any()))
        .thenThrow(
            new GitCommandException(
                IntegrationFailureCategory.GIT_PUSH_REJECTED, "rejected (non-fast-forward)"));

    assertThatThrownBy(() -> service.captureAndPush(REX))
        .isInstanceOf(GitCommandException.class)
        .satisfies(
            e ->
                assertThat(((GitCommandException) e).failureCategory())
                    .isEqualTo(IntegrationFailureCategory.GIT_PUSH_REJECTED));
  }

  // ---- branch / message helpers ----

  @Test
  void branchNameUsesSanitizedTicketSlugAndRunIdShort() {
    assertThat(RepositoryWorkspaceService.branchName("DEL-9", "run_abcdef123456"))
        .isEqualTo("deliveryline/DEL-9/stage-ef123456");
    assertThat(RepositoryWorkspaceService.ticketSlug("feature/My Ticket"))
        .isEqualTo("feature-My-Ticket");
    assertThat(RepositoryWorkspaceService.ticketSlug(null)).isEqualTo("no-ticket");
    assertThat(RepositoryWorkspaceService.runIdShort("run_abcdef123456")).isEqualTo("ef123456");
    assertThat(RepositoryWorkspaceService.runIdShort("short")).isEqualTo("short");
  }

  @Test
  void branchNameRejectsInvalidGitRefComponentsAfterSanitization() {
    assertThatThrownBy(() -> RepositoryWorkspaceService.branchName("...", RUN_ID))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("valid git branch");
    assertThatThrownBy(() -> RepositoryWorkspaceService.branchName("topic.lock", RUN_ID))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("valid git branch");
  }

  @Test
  void commitMessageCarriesGovernanceTrailers() {
    String message =
        RepositoryWorkspaceService.commitMessage("DEL-9", RUN_ID, "investigation", REX);
    assertThat(message)
        .startsWith("[DEL-9] DeliveryLine investigation output")
        .contains("Deliveryline-Run: " + RUN_ID)
        .contains("Deliveryline-Stage: investigation")
        .contains("Deliveryline-RunnerExecution: " + REX);
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
}
