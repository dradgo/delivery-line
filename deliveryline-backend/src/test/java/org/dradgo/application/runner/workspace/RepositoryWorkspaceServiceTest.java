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
import org.dradgo.application.integration.repohost.RepositoryHostAdapter;
import org.dradgo.application.integration.repohost.RepositoryHostAdapterException;
import org.dradgo.application.integration.spi.IntegrationLinkRecordPort;
import org.dradgo.application.project.ProjectRuntimeConfigResolver;
import org.dradgo.application.runner.RunnerSecretsService;
import org.dradgo.application.runner.spi.RunnerExecutionRecordPort;
import org.dradgo.application.runner.spi.RunnerExecutionSnapshot;
import org.dradgo.application.runner.spi.RunnerWorkspaceStore;
import org.dradgo.application.runner.workspace.spi.GitCommandException;
import org.dradgo.application.runner.workspace.spi.GitCommandPort;
import org.dradgo.application.workflow.WorkflowProperties;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.integration.repohost.PullRequest;
import org.dradgo.domain.integration.repohost.PullRequestRef;
import org.dradgo.domain.integration.repohost.Repository;
import org.dradgo.domain.integration.repohost.RepositoryRef;
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
  private RepositoryHostAdapter gitHubAdapter;
  private RunnerSecretsService secrets;
  private RunnerWorkspaceStore store;
  private RunnerExecutionRecordPort recordPort;
  private IntegrationLinkRecordPort links;
  private RepositoryWorkspaceService service;

  @BeforeEach
  void setUp() {
    git = Mockito.mock(GitCommandPort.class);
    gitHubAdapter = Mockito.mock(RepositoryHostAdapter.class);
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

  /** Story 3c-7 review (P3) — a Project whose normalized repositoryUrl drives the expected ref. */
  private static org.dradgo.domain.project.Project projectWithRepo(String repositoryUrl) {
    return new org.dradgo.domain.project.Project(
        org.dradgo.domain.id.PublicIdPrefixes.PROJECT.next(),
        "Acme",
        "acme",
        org.dradgo.domain.registry.ProjectStatus.ACTIVE,
        repositoryUrl,
        org.dradgo.domain.registry.ConnectorKind.LINEAR,
        org.dradgo.domain.registry.ConnectorKind.GITHUB,
        false,
        null,
        false,
        null,
        OffsetDateTime.now(),
        null);
  }

  // ---- AC9 guard ----

  @Test
  void prepareWorkspaceThrowsMismatchWhenRepoUnresolvable() {
    when(gitHubAdapter.getRepositoryByRef(RepositoryRef.of("GH-unknown")))
        .thenReturn(Optional.empty());

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
    when(gitHubAdapter.getRepositoryByRef(RepositoryRef.of(REPO_REF)))
        .thenReturn(
            Optional.of(new Repository(RepositoryRef.of(REPO_REF), "owner/repo", "main", "url")));
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
    when(gitHubAdapter.getPullRequestByRef(PullRequestRef.of("PR-9")))
        .thenReturn(
            Optional.of(
                new PullRequest(
                    PullRequestRef.of("PR-9"),
                    RepositoryRef.of("GH-OTHER"),
                    9,
                    "b",
                    "open",
                    "u",
                    Instant.now())));

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

  // ---- AC5 project-scoped repo-mismatch seam (3c-3) ----

  @Test
  void prepareWorkspaceIsByteIdenticalWhenConfiguredRefEqualsRequested() {
    // Parity: today the broker passes the configured repo ref as repositoryRef, so the new
    // expected==requested seam is a no-op and the happy path proceeds unchanged.
    RepositoryWorkspaceService configured =
        new RepositoryWorkspaceService(
            git,
            gitHubAdapter,
            secrets,
            store,
            recordPort,
            links,
            new WorkflowProperties(
                WorkflowProperties.Bot.empty(),
                WorkflowProperties.RepoConfig.of("octo/hello"),
                WorkflowProperties.LinearCompletionSync.defaults()));
    when(gitHubAdapter.getRepositoryByRef(RepositoryRef.of("octo/hello")))
        .thenReturn(
            Optional.of(
                new Repository(RepositoryRef.of("octo/hello"), "octo/hello", "main", "git://r")));
    when(store.prepareRepositoryDir(REX)).thenReturn(REPO_DIR);
    when(git.checkoutOrReuseBranch(eq(REPO_DIR), anyString(), any()))
        .thenReturn(GitCommandPort.BranchOutcome.CREATED);

    RepositoryWorkspaceService.RepositoryMount mount =
        configured.prepareWorkspace(RUN_ID, RunnerStage.INVESTIGATION, REX, "DEL-9", "octo/hello");

    assertThat(mount.defaultBranch()).isEqualTo("main");
    verify(git).cloneRepository(any(GitCommandPort.CloneSpec.class), any());
  }

  @Test
  void prepareWorkspaceRaisesMismatchWhenConfiguredRefDiffersFromRequested() {
    RepositoryWorkspaceService configured =
        new RepositoryWorkspaceService(
            git,
            gitHubAdapter,
            secrets,
            store,
            recordPort,
            links,
            new WorkflowProperties(
                WorkflowProperties.Bot.empty(),
                WorkflowProperties.RepoConfig.of("octo/hello"),
                WorkflowProperties.LinearCompletionSync.defaults()));

    assertThatThrownBy(
            () ->
                configured.prepareWorkspace(
                    RUN_ID, RunnerStage.INVESTIGATION, REX, "DEL-9", "octo/other"))
        .isInstanceOf(DomainException.class)
        .satisfies(
            e ->
                assertThat(((DomainException) e).errorCode())
                    .isEqualTo(DomainErrorCode.LINEAR_GITHUB_REPO_MISMATCH));
    // Seam runs before the existing AC9 resolve guard and before any clone.
    verify(gitHubAdapter, never()).getRepositoryByRef(any());
    verify(git, never()).cloneRepository(any(), any());
  }

  @Test
  void prepareWorkspaceDoesNotMismatchOnACaseOrDotGitVariantOfTheConfiguredRef() {
    // Review hardening: the seam normalizes + case-folds the requested ref, so a case-variant of
    // the configured repo is accepted rather than falsely raising LINEAR_GITHUB_REPO_MISMATCH.
    RepositoryWorkspaceService configured =
        new RepositoryWorkspaceService(
            git,
            gitHubAdapter,
            secrets,
            store,
            recordPort,
            links,
            new WorkflowProperties(
                WorkflowProperties.Bot.empty(),
                WorkflowProperties.RepoConfig.of("octo/hello"),
                WorkflowProperties.LinearCompletionSync.defaults()));
    when(gitHubAdapter.getRepositoryByRef(RepositoryRef.of("Octo/Hello")))
        .thenReturn(
            Optional.of(
                new Repository(RepositoryRef.of("Octo/Hello"), "Octo/Hello", "main", "git://r")));
    when(store.prepareRepositoryDir(REX)).thenReturn(REPO_DIR);
    when(git.checkoutOrReuseBranch(eq(REPO_DIR), anyString(), any()))
        .thenReturn(GitCommandPort.BranchOutcome.CREATED);

    RepositoryWorkspaceService.RepositoryMount mount =
        configured.prepareWorkspace(RUN_ID, RunnerStage.INVESTIGATION, REX, "DEL-9", "Octo/Hello");

    assertThat(mount.defaultBranch()).isEqualTo("main");
    verify(git).cloneRepository(any(GitCommandPort.CloneSpec.class), any());
  }

  // ---- AC3/AC4 3c-6 repoint: expected ref resolves through ProjectRuntimeConfigResolver ----

  @Test
  void prepareWorkspaceRepointsExpectedRefThroughResolverAndIsByteIdenticalOnParity() {
    // 3c-6: when the resolver is wired (production), the expected ref comes from the run's Project
    // (default-project fallback). The seeded default's repositoryUrl == the global config url, so a
    // matching requested ref is byte-identical parity — no mismatch, clone proceeds.
    ProjectRuntimeConfigResolver resolver = Mockito.mock(ProjectRuntimeConfigResolver.class);
    // Story 3c-7 review (P3) — prepareWorkspace resolves the run's Project ONCE and shares it; the
    // expected ref is derived from project.repositoryUrl (identical to resolveRepositoryRef).
    when(resolver.resolveForRun(RUN_ID)).thenReturn(projectWithRepo("octo/hello"));
    RepositoryWorkspaceService wired =
        new RepositoryWorkspaceService(
            git,
            gitHubAdapter,
            secrets,
            store,
            recordPort,
            links,
            WorkflowProperties.defaults(),
            resolver);
    when(gitHubAdapter.getRepositoryByRef(RepositoryRef.of("octo/hello")))
        .thenReturn(
            Optional.of(
                new Repository(RepositoryRef.of("octo/hello"), "octo/hello", "main", "git://r")));
    when(store.prepareRepositoryDir(REX)).thenReturn(REPO_DIR);
    when(git.checkoutOrReuseBranch(eq(REPO_DIR), anyString(), any()))
        .thenReturn(GitCommandPort.BranchOutcome.CREATED);

    RepositoryWorkspaceService.RepositoryMount mount =
        wired.prepareWorkspace(RUN_ID, RunnerStage.INVESTIGATION, REX, "DEL-9", "octo/hello");

    assertThat(mount.defaultBranch()).isEqualTo("main");
    verify(resolver).resolveForRun(RUN_ID);
    verify(git).cloneRepository(any(GitCommandPort.CloneSpec.class), any());
  }

  @Test
  void prepareWorkspaceRaisesMismatchWhenResolverRefDiffersFromRequested() {
    ProjectRuntimeConfigResolver resolver = Mockito.mock(ProjectRuntimeConfigResolver.class);
    when(resolver.resolveForRun(RUN_ID)).thenReturn(projectWithRepo("octo/hello"));
    RepositoryWorkspaceService wired =
        new RepositoryWorkspaceService(
            git,
            gitHubAdapter,
            secrets,
            store,
            recordPort,
            links,
            WorkflowProperties.defaults(),
            resolver);

    assertThatThrownBy(
            () ->
                wired.prepareWorkspace(
                    RUN_ID, RunnerStage.INVESTIGATION, REX, "DEL-9", "octo/other"))
        .isInstanceOf(DomainException.class)
        .satisfies(
            e ->
                assertThat(((DomainException) e).errorCode())
                    .isEqualTo(DomainErrorCode.LINEAR_GITHUB_REPO_MISMATCH));
    verify(gitHubAdapter, never()).getRepositoryByRef(any());
    verify(git, never()).cloneRepository(any(), any());
  }

  // ---- prepareWorkspace happy ----

  @Test
  void prepareWorkspaceClonesChecksOutStampsConfigAndReturnsMount() {
    when(gitHubAdapter.getRepositoryByRef(RepositoryRef.of(REPO_REF)))
        .thenReturn(
            Optional.of(
                new Repository(RepositoryRef.of(REPO_REF), "owner/repo", "main", "git://remote")));
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
    when(git.getLocalConfig(REPO_DIR, "deliveryline.defaultBranch"))
        .thenReturn(Optional.of("main"));
    when(gitHubAdapter.createPullRequest(
            eq(RepositoryRef.of(REPO_REF)), anyString(), anyString(), anyString(), anyString()))
        .thenReturn(
            new PullRequest(
                PullRequestRef.of("PR-1"),
                RepositoryRef.of(REPO_REF),
                1,
                "b",
                "open",
                "u",
                Instant.now()));

    Optional<RepositoryWorkspaceService.RepositoryPushOutcome> outcome =
        service.captureAndPush(REX);

    assertThat(outcome).isPresent();
    assertThat(outcome.get().committed()).isTrue();
    assertThat(outcome.get().commitSha()).isEqualTo("abc1234");
    assertThat(outcome.get().prRef()).isEqualTo("PR-1");
    verify(gitHubAdapter)
        .createPullRequest(
            eq(RepositoryRef.of(REPO_REF)),
            eq("deliveryline/DEL-9/stage-ef123456"),
            eq("main"),
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
  void captureAndPushCapturesUnifiedDiffAgainstDefaultBranch() {
    // The pr-output advisory reviewer runs offline and can only inspect its mounted input; it needs
    // the actual code changes. captureAndPush (which holds the repo checkout + committed changes)
    // must therefore also capture the unified diff of the pushed branch against the default branch
    // and expose it on the outcome, so the broker can materialize it for the reviewer.
    when(store.resolveRepositoryDir(REX)).thenReturn(Optional.of(REPO_DIR));
    when(recordPort.findByPublicId(REX)).thenReturn(Optional.of(snapshot()));
    when(git.currentBranch(REPO_DIR)).thenReturn("deliveryline/DEL-9/stage-ef123456");
    when(git.getLocalConfig(REPO_DIR, "deliveryline.repoRef")).thenReturn(Optional.of(REPO_REF));
    when(git.getLocalConfig(REPO_DIR, "deliveryline.ticketRef")).thenReturn(Optional.of("DEL-9"));
    when(git.getLocalConfig(REPO_DIR, "deliveryline.ticketSummary"))
        .thenReturn(Optional.of("Fix flaky dispatch"));
    when(git.getLocalConfig(REPO_DIR, "deliveryline.stage")).thenReturn(Optional.of("prOutput"));
    when(git.getLocalConfig(REPO_DIR, "deliveryline.defaultBranch"))
        .thenReturn(Optional.of("main"));
    when(git.hasUncommittedChanges(REPO_DIR)).thenReturn(true);
    when(git.commitAll(any())).thenReturn("abc1234");
    when(git.push(eq(REPO_DIR), anyString(), any()))
        .thenReturn(
            new GitCommandPort.PushResult(
                "abc1234", "deliveryline/DEL-9/stage-ef123456", "git://remote"));
    when(gitHubAdapter.createPullRequest(any(), anyString(), anyString(), anyString(), anyString()))
        .thenReturn(
            new PullRequest(
                PullRequestRef.of("PR-1"),
                RepositoryRef.of(REPO_REF),
                1,
                "b",
                "open",
                "u",
                Instant.now()));
    when(git.diff(REPO_DIR, "main"))
        .thenReturn(
            "diff --git a/pom.xml b/pom.xml\n"
                + "+<hibernate.version>5.6.15.Final</hibernate.version>\n");

    Optional<RepositoryWorkspaceService.RepositoryPushOutcome> outcome =
        service.captureAndPush(REX);

    assertThat(outcome).isPresent();
    assertThat(outcome.get().diff())
        .contains("diff --git a/pom.xml b/pom.xml")
        .contains("<hibernate.version>5.6.15.Final</hibernate.version>");
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
        .createPullRequest(any(), anyString(), any(), anyString(), anyString());
    verify(gitHubAdapter, never()).updatePullRequest(any(), anyString());
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
    when(gitHubAdapter.createPullRequest(any(), anyString(), any(), anyString(), anyString()))
        .thenThrow(
            new RepositoryHostAdapterException(
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
    when(gitHubAdapter.getRepositoryByRef(RepositoryRef.of(REPO_REF)))
        .thenReturn(
            Optional.of(new Repository(RepositoryRef.of(REPO_REF), "owner/repo", "main", "url")));
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
    when(gitHubAdapter.getPullRequestByRef(PullRequestRef.of("PR-9"))).thenReturn(Optional.empty());

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
