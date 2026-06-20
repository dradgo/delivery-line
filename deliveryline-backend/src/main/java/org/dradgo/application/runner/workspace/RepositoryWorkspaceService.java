package org.dradgo.application.runner.workspace;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.dradgo.application.integration.IntegrationLink;
import org.dradgo.application.integration.repohost.RepositoryHostAdapter;
import org.dradgo.application.integration.repohost.RepositoryHostAdapterException;
import org.dradgo.application.integration.spi.IntegrationLinkRecordPort;
import org.dradgo.application.observability.MdcKeys;
import org.dradgo.application.project.ProjectRuntimeConfigResolver;
import org.dradgo.application.runner.RunnerSecretsService;
import org.dradgo.application.runner.spi.RunnerExecutionRecordPort;
import org.dradgo.application.runner.spi.RunnerExecutionSnapshot;
import org.dradgo.application.runner.spi.RunnerWorkspaceStore;
import org.dradgo.application.runner.workspace.spi.GitCommandException;
import org.dradgo.application.runner.workspace.spi.GitCommandPort;
import org.dradgo.application.runner.workspace.spi.RepoTreeEntry;
import org.dradgo.application.workflow.WorkflowProperties;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.id.PublicIdPrefixes;
import org.dradgo.domain.integration.repohost.PullRequest;
import org.dradgo.domain.integration.repohost.PullRequestRef;
import org.dradgo.domain.integration.repohost.Repository;
import org.dradgo.domain.integration.repohost.RepositoryRef;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.IntegrationFailureCategory;
import org.dradgo.domain.registry.RunnerStage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * Story 3.9 — the git half of agent execution. Clones the linked GitHub repository into a
 * per-execution workspace ({@code {rex}/repo/}), checks out the deterministic feature branch {@code
 * deliveryline/{ticketRef}/stage-{runIdShort}}, exposes the working tree as a {@code
 * /workspace/repo} runner mount, captures runner-produced changes after exit, commits +
 * direct-pushes to the target repo, and opens/updates the GitHub PR —
 * <strong>failing-and-deferring-to-recovery on push rejection</strong> (never auto-rebasing,
 * force-pushing, or silently retrying, AC7/T8).
 *
 * <p>Serves all three runner stages with an identical workspace lifecycle (the branch uses {@code
 * runIdShort}, not the stage, so the convention is stage-agnostic).
 *
 * <p>Boundary (AC13/Trap T1): collaborators are the {@link GitCommandPort} SPI, the {@link
 * RepositoryHostAdapter} port (read-only here), {@link RunnerSecretsService}, {@link
 * RunnerWorkspaceStore}, {@link IntegrationLinkRecordPort}, and {@link WorkflowProperties} — never
 * {@code org.dradgo.adapters..} or a git-library type. Pinned by the {@code
 * REPOSITORY_WORKSPACE_SERVICE_SCOPE} ArchUnit rule.
 *
 * <p>Bean-gated on {@code github-mock}/{@code github-real} — exactly the profiles under which a
 * {@link RepositoryHostAdapter} bean exists ({@code GitHubMockAdapter}/{@code GitHubRealAdapter}
 * carry the same profiles). In any other context (e.g. a lean {@code @SpringBootTest} contract on
 * {@code [test, linear-mock]}) the bean is absent, the broker/adapter {@code
 * ObjectProvider<RepositoryWorkspaceService>} resolves to null, and the repository seam stays
 * dormant (Decision D0). The service-level tests construct it directly via {@code new}, so the
 * profile gate does not affect them.
 */
@Service
@Profile({"github-mock", "github-real"})
public class RepositoryWorkspaceService {

  private static final Logger log = LoggerFactory.getLogger(RepositoryWorkspaceService.class);

  /** Container-side bind-mount point for the cloned working tree (AC4). */
  public static final String CONTAINER_REPO_MOUNT = "/workspace/repo";

  static final String GITHUB_TOKEN_ENV = "GITHUB_TOKEN";
  static final String BRANCH_NAMESPACE = "deliveryline/";
  static final String GITHUB_PR_INTEGRATION_TYPE = "github_pr";

  // Story 3a-2 (AC6/T9) — bound the embedded tree summary so a large repo never bloats the bundle
  // past the validator's payload-size limit. Top-level depth + entry cap (no config key added —
  // keeps both application.yml files untouched, dodging the validated-config test-yaml burden T3).
  static final int TREE_DEPTH_LIMIT = 2;
  static final int TREE_ENTRY_CAP = 200;

  // Story 3a-2 (AC6) — package/build manifests detected by exact top-or-nested filename match.
  private static final Set<String> MANIFEST_FILENAMES =
      Set.of(
          "package.json",
          "pom.xml",
          "Cargo.toml",
          "build.gradle",
          "build.gradle.kts",
          "go.mod",
          "requirements.txt",
          "pyproject.toml");

  // Decision D6 — self-describing on-disk markers re-derived by captureAndPush(rex). Non-secret.
  static final String CONFIG_REPO_REF = "deliveryline.repoRef";
  static final String CONFIG_TICKET_REF = "deliveryline.ticketRef";
  static final String CONFIG_TICKET_SUMMARY = "deliveryline.ticketSummary";
  static final String CONFIG_RUN_ID = "deliveryline.workflowRunId";
  static final String CONFIG_STAGE = "deliveryline.stage";
  // Story 3.33 (OQ-2) — the resolved repository default branch, stamped at prepare time so the
  // capture-time PR-create can thread it as the explicit createPullRequest targetBranch (D6
  // restart-robust: derived from the on-disk repo, not re-fetched). A blank/absent marker makes the
  // host adapter fall back to its internally-resolved default branch (legacy behavior preserved).
  static final String CONFIG_DEFAULT_BRANCH = "deliveryline.defaultBranch";

  private final GitCommandPort git;
  private final RepositoryHostAdapter repositoryHostAdapter;
  private final RunnerSecretsService runnerSecretsService;
  private final RunnerWorkspaceStore workspaceStore;
  private final RunnerExecutionRecordPort recordPort;
  private final IntegrationLinkRecordPort integrationLinkRecordPort;
  private final WorkflowProperties workflowProperties;
  // Story 3c-6 — the repoint target of resolveExpectedRepositoryRef (run -> Project repo binding,
  // default-project fallback). Nullable ONLY for the git-focused service-level tests that construct
  // via the no-resolver ctor below: in PRODUCTION the @Autowired ctor always injects it (it is an
  // always-present application.project @Component), so the seam always resolves through the
  // Project.
  // When null the seam falls back to the global single-repo config (byte-identical pre-3c-6).
  private final ProjectRuntimeConfigResolver projectRuntimeConfigResolver;
  private final Clock clock;

  @org.springframework.beans.factory.annotation.Autowired
  public RepositoryWorkspaceService(
      GitCommandPort git,
      RepositoryHostAdapter repositoryHostAdapter,
      RunnerSecretsService runnerSecretsService,
      RunnerWorkspaceStore workspaceStore,
      RunnerExecutionRecordPort recordPort,
      IntegrationLinkRecordPort integrationLinkRecordPort,
      WorkflowProperties workflowProperties,
      ProjectRuntimeConfigResolver projectRuntimeConfigResolver) {
    this(
        git,
        repositoryHostAdapter,
        runnerSecretsService,
        workspaceStore,
        recordPort,
        integrationLinkRecordPort,
        workflowProperties,
        projectRuntimeConfigResolver,
        Clock.systemUTC());
  }

  /**
   * Test-facing constructor (no {@link ProjectRuntimeConfigResolver}): the git-focused
   * service-level tests exercise clone/capture/push behavior, not project resolution, so they keep
   * the pre-3c-6 global-config repo-ref behavior. Production always uses the {@code @Autowired}
   * resolver ctor.
   */
  public RepositoryWorkspaceService(
      GitCommandPort git,
      RepositoryHostAdapter repositoryHostAdapter,
      RunnerSecretsService runnerSecretsService,
      RunnerWorkspaceStore workspaceStore,
      RunnerExecutionRecordPort recordPort,
      IntegrationLinkRecordPort integrationLinkRecordPort,
      WorkflowProperties workflowProperties) {
    this(
        git,
        repositoryHostAdapter,
        runnerSecretsService,
        workspaceStore,
        recordPort,
        integrationLinkRecordPort,
        workflowProperties,
        null,
        Clock.systemUTC());
  }

  public RepositoryWorkspaceService(
      GitCommandPort git,
      RepositoryHostAdapter repositoryHostAdapter,
      RunnerSecretsService runnerSecretsService,
      RunnerWorkspaceStore workspaceStore,
      RunnerExecutionRecordPort recordPort,
      IntegrationLinkRecordPort integrationLinkRecordPort,
      WorkflowProperties workflowProperties,
      ProjectRuntimeConfigResolver projectRuntimeConfigResolver,
      Clock clock) {
    this.git = Objects.requireNonNull(git, "git");
    this.repositoryHostAdapter =
        Objects.requireNonNull(repositoryHostAdapter, "repositoryHostAdapter");
    this.runnerSecretsService =
        Objects.requireNonNull(runnerSecretsService, "runnerSecretsService");
    this.workspaceStore = Objects.requireNonNull(workspaceStore, "workspaceStore");
    this.recordPort = Objects.requireNonNull(recordPort, "recordPort");
    this.integrationLinkRecordPort =
        Objects.requireNonNull(integrationLinkRecordPort, "integrationLinkRecordPort");
    this.workflowProperties = Objects.requireNonNull(workflowProperties, "workflowProperties");
    // nullable by design (see field doc) — no requireNonNull.
    this.projectRuntimeConfigResolver = projectRuntimeConfigResolver;
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * AC1/AC2 — clone the linked repo, check out the deterministic branch, set the bot identity, and
   * return the {@code /workspace/repo} mount reference + resolved default branch. The AC9
   * repo-reconciliation guard runs FIRST, before any clone.
   */
  public RepositoryMount prepareWorkspace(
      String workflowRunId,
      RunnerStage stage,
      String runnerExecutionId,
      String linearTicketRef,
      String linearTicketSummary,
      String repositoryRef) {
    PublicIdPrefixes.require(runnerExecutionId, PublicIdPrefixes.RUNNER_EXECUTION);
    Objects.requireNonNull(stage, "stage");
    if (repositoryRef == null || repositoryRef.isBlank()) {
      throw repoMismatch(workflowRunId, repositoryRef, "blank repositoryRef");
    }
    String priorRunMdc = MdcKeys.beginScope(MdcKeys.WORKFLOW_RUN_ID, workflowRunId);
    String priorRexMdc = MdcKeys.beginScope(MdcKeys.RUNNER_EXECUTION_ID, runnerExecutionId);
    try {
      log.info(
          "prepareWorkspace start workflowRunId={} runnerExecutionId={} stage={} repoRef={}",
          workflowRunId,
          runnerExecutionId,
          stage.value(),
          repositoryRef);

      // 3c-3 AC5 seam — project-scoped repo binding check (pre-clone). Today the expected ref falls
      // back to the global single-repo config (resolveExpectedRepositoryRef -> the configured ref),
      // so when the broker passes that same configured ref expected == requested and no new
      // rejection occurs (byte-identical). 3c-6/3c-7 repoint resolveExpectedRepositoryRef at the
      // run's Project so this becomes a genuine per-project guard. The requested ref is normalized
      // and compared case-insensitively (mirrors
      // ProjectConnectorResolver.assertRepositoryRefMatches
      // Project) so a URL/.git/trailing-slash/case-variant form of the same repo is not falsely
      // rejected; idempotent on an already-bare lowercase ref so parity holds.
      Optional<String> expectedRepositoryRef = resolveExpectedRepositoryRef(workflowRunId);
      String requestedRepositoryRef = RepositoryRef.normalizeRepositoryUrl(repositoryRef);
      if (expectedRepositoryRef.isPresent()
          && !expectedRepositoryRef.get().equalsIgnoreCase(requestedRepositoryRef)) {
        throw repoMismatch(
            workflowRunId,
            repositoryRef,
            "requested repo does not match expected binding " + expectedRepositoryRef.get());
      }

      // AC9 guard FIRST — resolve the repo and reconcile against any existing github_pr link.
      Repository repository = resolveRepositoryOrMismatch(workflowRunId, repositoryRef);
      assertNoConflictingRepoLink(workflowRunId, repositoryRef);

      String token = runnerSecretsService.resolveHostSecret(GITHUB_TOKEN_ENV).orElse(null);
      Path repoDir = workspaceStore.prepareRepositoryDir(runnerExecutionId);
      String branch = branchName(linearTicketRef, workflowRunId);

      WorkflowProperties.RepoConfig repoConfig = workflowProperties.repos();
      if (!isExistingGitRepository(repoDir)) {
        ensureCloneTargetEmpty(repoDir, runnerExecutionId);
        git.cloneRepository(
            new GitCommandPort.CloneSpec(
                repository.url(), repoDir, repoConfig.cloneDepth(), repoConfig.sparsePaths()),
            token);
      } else {
        log.warn(
            "prepareWorkspace reusing existing local clone workflowRunId={} runnerExecutionId={} repoDir={}",
            workflowRunId,
            runnerExecutionId,
            repoDir);
      }

      WorkflowProperties.Bot bot = workflowProperties.bot();
      git.configureIdentity(repoDir, bot.effectiveName(), bot.effectiveEmail());
      GitCommandPort.BranchOutcome outcome = git.checkoutOrReuseBranch(repoDir, branch, token);

      // Decision D6 — stamp the self-describing markers so captureAndPush(rex) can re-derive them.
      git.setLocalConfig(repoDir, CONFIG_REPO_REF, repositoryRef);
      git.setLocalConfig(
          repoDir, CONFIG_TICKET_REF, linearTicketRef == null ? "" : linearTicketRef);
      git.setLocalConfig(
          repoDir, CONFIG_TICKET_SUMMARY, linearTicketSummary == null ? "" : linearTicketSummary);
      git.setLocalConfig(repoDir, CONFIG_RUN_ID, workflowRunId == null ? "" : workflowRunId);
      git.setLocalConfig(repoDir, CONFIG_STAGE, stage.value());
      // Story 3.33 — stamp the resolved default branch for capture-time createPullRequest
      // targeting.
      git.setLocalConfig(repoDir, CONFIG_DEFAULT_BRANCH, repository.defaultBranch());

      log.info(
          "prepareWorkspace ok workflowRunId={} runnerExecutionId={} branch={} branchOutcome={} defaultBranch={}",
          workflowRunId,
          runnerExecutionId,
          branch,
          outcome,
          repository.defaultBranch());
      return new RepositoryMount(repoDir, CONTAINER_REPO_MOUNT, repository.defaultBranch(), branch);
    } finally {
      MdcKeys.endScope(MdcKeys.RUNNER_EXECUTION_ID, priorRexMdc);
      MdcKeys.endScope(MdcKeys.WORKFLOW_RUN_ID, priorRunMdc);
    }
  }

  public RepositoryMount prepareWorkspace(
      String workflowRunId,
      RunnerStage stage,
      String runnerExecutionId,
      String linearTicketRef,
      String repositoryRef) {
    return prepareWorkspace(
        workflowRunId, stage, runnerExecutionId, linearTicketRef, null, repositoryRef);
  }

  /**
   * AC6/AC8 — after a successful runner exit, commit any runner-produced changes (with governance
   * trailers, AC12), push the branch, and create/update the GitHub PR. Returns {@link
   * Optional#empty()} when the run had no repo workspace (the common no-repo dispatch — the broker
   * then proceeds to its normal completion). On push rejection raises {@link GitCommandException}
   * (AC7) for the caller to map onto the failure path.
   */
  public Optional<RepositoryPushOutcome> captureAndPush(String runnerExecutionId) {
    PublicIdPrefixes.require(runnerExecutionId, PublicIdPrefixes.RUNNER_EXECUTION);
    Optional<Path> repoDirOpt = workspaceStore.resolveRepositoryDir(runnerExecutionId);
    if (repoDirOpt.isEmpty()) {
      log.debug(
          "captureAndPush no-op runnerExecutionId={} reason=no_repo_workspace", runnerExecutionId);
      return Optional.empty();
    }
    Path repoDir = repoDirOpt.get();

    RunnerExecutionSnapshot snapshot =
        recordPort
            .findByPublicId(runnerExecutionId)
            .orElseThrow(
                () ->
                    new DomainException(
                        DomainErrorCode.RUNNER_EXECUTION_NOT_FOUND,
                        "runner execution not found for captureAndPush: " + runnerExecutionId));
    String workflowRunId = snapshot.workflowRunPublicId();
    String priorRunMdc = MdcKeys.beginScope(MdcKeys.WORKFLOW_RUN_ID, workflowRunId);
    String priorRexMdc = MdcKeys.beginScope(MdcKeys.RUNNER_EXECUTION_ID, runnerExecutionId);
    try {
      // Decision D6 — everything below is derived from the on-disk repo (restart-robust).
      String branch = git.currentBranch(repoDir);
      String repoRef = git.getLocalConfig(repoDir, CONFIG_REPO_REF).orElse(null);
      String ticketRef = git.getLocalConfig(repoDir, CONFIG_TICKET_REF).orElse(null);
      String ticketSummary = git.getLocalConfig(repoDir, CONFIG_TICKET_SUMMARY).orElse(null);
      String stage = git.getLocalConfig(repoDir, CONFIG_STAGE).orElse(snapshot.stage().value());
      String defaultBranch = git.getLocalConfig(repoDir, CONFIG_DEFAULT_BRANCH).orElse(null);
      String token = runnerSecretsService.resolveHostSecret(GITHUB_TOKEN_ENV).orElse(null);

      boolean committed = false;
      if (git.hasUncommittedChanges(repoDir)) {
        WorkflowProperties.Bot bot = workflowProperties.bot();
        String message = commitMessage(ticketRef, workflowRunId, stage, runnerExecutionId);
        git.commitAll(
            new GitCommandPort.CommitSpec(
                repoDir, message, bot.effectiveName(), bot.effectiveEmail()));
        committed = true;
      } else {
        log.info(
            "captureAndPush no-op workflowRunId={} runnerExecutionId={} reason=clean_worktree branch={}",
            workflowRunId,
            runnerExecutionId,
            branch);
        return Optional.empty();
      }

      // AC7 — push rejection raises GitCommandException; do NOT catch it here (the broker maps it).
      GitCommandPort.PushResult push = git.push(repoDir, branch, token);

      String prRef =
          createOrUpdatePullRequest(
              workflowRunId,
              repoRef,
              branch,
              defaultBranch,
              ticketRef,
              ticketSummary,
              stage,
              push.commitSha());

      log.info(
          "captureAndPush ok workflowRunId={} runnerExecutionId={} branch={} commitSha={} committed={} prRef={}",
          workflowRunId,
          runnerExecutionId,
          branch,
          push.commitSha(),
          committed,
          prRef);
      return Optional.of(new RepositoryPushOutcome(push.commitSha(), branch, prRef, committed));
    } finally {
      MdcKeys.endScope(MdcKeys.RUNNER_EXECUTION_ID, priorRexMdc);
      MdcKeys.endScope(MdcKeys.WORKFLOW_RUN_ID, priorRunMdc);
    }
  }

  /** AC11 — delegate to the workspace store's recursive delete (Decision D3). */
  public void cleanupWorkspace(String runnerExecutionId) {
    PublicIdPrefixes.require(runnerExecutionId, PublicIdPrefixes.RUNNER_EXECUTION);
    workspaceStore.deleteWorkspace(runnerExecutionId);
    log.info("cleanupWorkspace done runnerExecutionId={}", runnerExecutionId);
  }

  // =====================================================================
  // Story 3a-2 — spec-stage repo-context summary (AC6, Decision D3)
  // =====================================================================

  /**
   * Story 3a-2 (AC6) — derive the curated {@link RepositoryContextSummary} from a prepared {@link
   * RepositoryMount}: a depth-bounded, entry-capped, {@code .gitignore}-respecting top-level tree
   * (via the {@link GitCommandPort#listTopLevelTree} SPI op), the detected README, and the detected
   * package/build manifests — all as mount-relative paths off {@link #CONTAINER_REPO_MOUNT} (never
   * a host absolute path, Trap T7). The returned record is passed as a plain method parameter into
   * {@code ContextBundleService.createForSpecInvestigation} (Traps T1/T2).
   *
   * @param mount the prepared workspace (its {@code repoHostPath} is read only locally, never
   *     serialized)
   * @param mappingVersion the Linear↔GitHub mapping marker (config-derived for the pilot, see
   *     {@link #configMappingVersion})
   */
  public RepositoryContextSummary summarize(RepositoryMount mount, String mappingVersion) {
    Objects.requireNonNull(mount, "mount");
    if (mappingVersion == null || mappingVersion.isBlank()) {
      throw new IllegalArgumentException("mappingVersion must be non-blank");
    }
    log.info("summarize start mountPath={}", mount.containerMountPath());

    List<RepoTreeEntry> fullTree = git.listTopLevelTree(mount.repoHostPath(), TREE_DEPTH_LIMIT);
    boolean truncated = fullTree.size() > TREE_ENTRY_CAP;
    List<RepoTreeEntry> embeddedTree =
        truncated ? List.copyOf(fullTree.subList(0, TREE_ENTRY_CAP)) : fullTree;

    String readmeRef = detectReadme(fullTree);
    List<RepoManifestRef> manifestRefs = detectManifests(fullTree);

    if (truncated) {
      log.warn(
          "summarize tree truncated mountPath={} totalEntries={} cap={}",
          mount.containerMountPath(),
          fullTree.size(),
          TREE_ENTRY_CAP);
    }
    if (readmeRef == null) {
      log.warn("summarize no README detected mountPath={}", mount.containerMountPath());
    }
    log.info(
        "summarize ok mountPath={} treeEntryCount={} manifestCount={} readmePresent={} truncated={}",
        mount.containerMountPath(),
        embeddedTree.size(),
        manifestRefs.size(),
        readmeRef != null,
        truncated);

    return new RepositoryContextSummary(
        mount.containerMountPath(), embeddedTree, readmeRef, manifestRefs, mappingVersion);
  }

  /**
   * Story 3a-2 (AC7, Decision D2) — resolve the {@code repositoryRef} for the pilot from the single
   * configured repo under {@code deliveryline.workflow.repos.url} (1:1 single-repo assumption).
   * Returns empty when no repo is configured (no real Linear↔GitHub mapping yet; deferred to
   * 3.32/3.33). {@code url} is normalized to {@code owner/repo}. The broker gates the repo-context
   * seam on this.
   */
  public Optional<String> resolveConfiguredRepositoryRef() {
    return Optional.ofNullable(workflowProperties.repos().repositoryRef());
  }

  /**
   * Story 3c-3 (AC5) / 3c-6 — the single swap point for the expected repository binding used by the
   * pre-clone mismatch guard in {@link #prepareWorkspace}. As of 3c-6 it resolves through the
   * {@link ProjectRuntimeConfigResolver} (run &rarr; Project {@code repositoryUrl}, default-project
   * fallback). Because the {@code default} project's {@code repositoryUrl} is seeded from {@code
   * deliveryline.workflow.repos.url}, this returns the same {@code owner/repo} the global path
   * returned for a single-project deployment (byte-identical parity; expected == requested ⇒ no new
   * mismatch). The remaining hot-path consumers are repointed in 3c-7. The git-focused
   * service-level tests construct without a resolver and keep the global-config fallback. The
   * resolver is {@code application.project}, so {@code REPOSITORY_WORKSPACE_SERVICE_SCOPE} (forbids
   * {@code adapters..}/ {@code jgit}) still holds.
   */
  // 3c-6 — repointed to run's Project (default-project fallback); remaining consumers wired in 3c-7
  private Optional<String> resolveExpectedRepositoryRef(String workflowRunId) {
    if (projectRuntimeConfigResolver != null) {
      Optional<String> expected = projectRuntimeConfigResolver.resolveRepositoryRef(workflowRunId);
      log.debug(
          "resolveExpectedRepositoryRef workflowRunId={} expectedRepoRef={} source=run_project_default_fallback",
          workflowRunId,
          expected.orElse("none"));
      return expected;
    }
    Optional<String> expected = resolveConfiguredRepositoryRef();
    log.debug(
        "resolveExpectedRepositoryRef workflowRunId={} expectedRepoRef={} source=global_config_no_resolver",
        workflowRunId,
        expected.orElse("none"));
    return expected;
  }

  /**
   * Story 3a-2 (Decision D2) — the config-derived {@code ticketRepositoryMappingVersion} marker for
   * the 1:1 pilot assumption (NOT a real mapping version; a real one is deferred to 3.32/3.33).
   */
  public static String configMappingVersion(String repositoryRef) {
    return "config:" + repositoryRef + "@1";
  }

  private static String detectReadme(List<RepoTreeEntry> tree) {
    String nested = null;
    for (RepoTreeEntry entry : tree) {
      if (entry.type() != RepoTreeEntry.Type.FILE) {
        continue;
      }
      String fileName = lastSegment(entry.path());
      if (!fileName.toLowerCase(Locale.ROOT).matches("^readme(\\..+)?$")) {
        continue;
      }
      if (entry.path().indexOf('/') < 0) {
        // Top-level README wins; tree is sorted ascending so this is deterministic.
        return entry.path();
      }
      if (nested == null) {
        nested = entry.path();
      }
    }
    return nested;
  }

  private static List<RepoManifestRef> detectManifests(List<RepoTreeEntry> tree) {
    List<RepoManifestRef> manifests = new ArrayList<>();
    for (RepoTreeEntry entry : tree) {
      if (entry.type() != RepoTreeEntry.Type.FILE) {
        continue;
      }
      String fileName = lastSegment(entry.path());
      if (MANIFEST_FILENAMES.contains(fileName)) {
        manifests.add(new RepoManifestRef(entry.path(), manifestKind(fileName)));
      }
    }
    return List.copyOf(manifests);
  }

  private static String manifestKind(String fileName) {
    // build.gradle.kts is the Kotlin DSL variant — same Gradle manifest kind.
    return "build.gradle.kts".equals(fileName) ? "build.gradle" : fileName;
  }

  private static String lastSegment(String path) {
    int slash = path.lastIndexOf('/');
    return slash < 0 ? path : path.substring(slash + 1);
  }

  // =====================================================================
  // AC9 guard helpers
  // =====================================================================

  private Repository resolveRepositoryOrMismatch(String workflowRunId, String repositoryRef) {
    Optional<Repository> repo;
    try {
      repo = repositoryHostAdapter.getRepositoryByRef(RepositoryRef.of(repositoryRef));
    } catch (RepositoryHostAdapterException adapterError) {
      throw repoMismatch(
          workflowRunId,
          repositoryRef,
          "repository ref unresolvable: " + adapterError.failureCategory().value());
    }
    return repo.orElseThrow(
        () -> repoMismatch(workflowRunId, repositoryRef, "repository ref does not resolve"));
  }

  private void assertNoConflictingRepoLink(String workflowRunId, String repositoryRef) {
    if (workflowRunId == null || workflowRunId.isBlank()) {
      return;
    }
    Optional<IntegrationLink> existing =
        integrationLinkRecordPort.findActiveByWorkflowRun(workflowRunId);
    if (existing.isEmpty()
        || !GITHUB_PR_INTEGRATION_TYPE.equals(existing.get().integrationType())) {
      return;
    }
    String prRef = existing.get().externalRef();
    Optional<PullRequest> pr;
    try {
      pr = repositoryHostAdapter.getPullRequestByRef(PullRequestRef.of(prRef));
    } catch (RepositoryHostAdapterException adapterError) {
      // A linked PR we can no longer resolve is itself a reconciliation failure.
      throw repoMismatch(workflowRunId, repositoryRef, "linked PR unresolvable");
    }
    if (pr.isPresent() && !repositoryRef.equals(pr.get().repoRef().value())) {
      throw repoMismatch(
          workflowRunId,
          repositoryRef,
          "run already linked to repo " + pr.get().repoRef().value() + " via PR " + prRef);
    }
    if (pr.isEmpty()) {
      throw repoMismatch(workflowRunId, repositoryRef, "linked PR does not resolve");
    }
  }

  private DomainException repoMismatch(String workflowRunId, String repositoryRef, String reason) {
    log.warn(
        "repo mismatch workflowRunId={} repoRef={} reason={}",
        workflowRunId,
        repositoryRef,
        reason);
    return new DomainException(
        DomainErrorCode.LINEAR_GITHUB_REPO_MISMATCH,
        "Requested repository cannot be reconciled with the run: " + reason);
  }

  // =====================================================================
  // PR create/update (AC8, Decision D7)
  // =====================================================================

  private String createOrUpdatePullRequest(
      String workflowRunId,
      String repoRef,
      String branch,
      String targetBranch,
      String ticketRef,
      String ticketSummary,
      String stage,
      String commitSha) {
    if (repoRef == null || repoRef.isBlank()) {
      // No repoRef stamped (OQ-1: no Linear↔GitHub mapping yet for this run) — push succeeded, PR
      // step deferred to a caller that supplies the linkage (3a-1/3.32). Not an error.
      log.info(
          "captureAndPush PR step skipped workflowRunId={} reason=no_repo_ref branch={}",
          workflowRunId,
          branch);
      return null;
    }
    String body = pullRequestBody(workflowRunId, stage, commitSha);
    // D7 — if a github_pr link already exists, refresh its body; else rely on createPullRequest's
    // built-in head/base&state=open idempotency (story 3.14 AC4) — no separate existing-PR probe.
    Optional<IntegrationLink> existing =
        workflowRunId == null || workflowRunId.isBlank()
            ? Optional.empty()
            : integrationLinkRecordPort.findActiveByWorkflowRun(workflowRunId);
    if (existing.isPresent()
        && GITHUB_PR_INTEGRATION_TYPE.equals(existing.get().integrationType())) {
      try {
        PullRequest updated =
            repositoryHostAdapter.updatePullRequest(
                PullRequestRef.of(existing.get().externalRef()), body);
        log.info(
            "captureAndPush PR updated workflowRunId={} prRef={}",
            workflowRunId,
            updated.prRef().value());
        return updated.prRef().value();
      } catch (RepositoryHostAdapterException adapterFailure) {
        throw mapGitHubFailure("updatePullRequest", adapterFailure);
      }
    }
    String title = pullRequestTitle(ticketRef, ticketSummary);
    try {
      PullRequest created =
          repositoryHostAdapter.createPullRequest(
              RepositoryRef.of(repoRef), branch, targetBranch, title, body);
      log.info(
          "captureAndPush PR created workflowRunId={} prRef={}",
          workflowRunId,
          created.prRef().value());
      return created.prRef().value();
    } catch (RepositoryHostAdapterException adapterFailure) {
      throw mapGitHubFailure("createPullRequest", adapterFailure);
    }
  }

  private static String pullRequestTitle(String ticketRef, String ticketSummary) {
    String ref = (ticketRef == null || ticketRef.isBlank()) ? "DeliveryLine" : ticketRef;
    String summary =
        (ticketSummary == null || ticketSummary.isBlank()) ? "DeliveryLine output" : ticketSummary;
    return "[" + ref + "] " + summary;
  }

  private static String pullRequestBody(String workflowRunId, String stage, String commitSha) {
    return "Automated changes produced by the DeliveryLine "
        + stage
        + " runner for governed run "
        + workflowRunId
        + ".\n\nCommit: "
        + commitSha
        + "\n";
  }

  // =====================================================================
  // Branch + commit-message helpers
  // =====================================================================

  /**
   * Deterministic branch {@code deliveryline/{ticketSlug}/stage-{runIdShort}} (AC2c, Trap T6). The
   * ticket ref is sanitized to a branch-safe slug; {@code runIdShort} is the last 8 chars of the
   * workflow run id.
   */
  static String branchName(String linearTicketRef, String workflowRunId) {
    String branch =
        BRANCH_NAMESPACE + ticketSlug(linearTicketRef) + "/stage-" + runIdShort(workflowRunId);
    validateBranchName(branch);
    return branch;
  }

  static String ticketSlug(String ticketRef) {
    if (ticketRef == null || ticketRef.isBlank()) {
      return "no-ticket";
    }
    String slug = ticketRef.trim().replaceAll("[^A-Za-z0-9._-]+", "-").replaceAll("-{2,}", "-");
    slug = slug.replaceAll("^-+", "").replaceAll("-+$", "");
    return slug.isBlank() ? "no-ticket" : slug;
  }

  static String runIdShort(String workflowRunId) {
    if (workflowRunId == null || workflowRunId.isBlank()) {
      return "00000000";
    }
    String trimmed = workflowRunId.trim();
    return trimmed.length() <= 8 ? trimmed : trimmed.substring(trimmed.length() - 8);
  }

  static String commitMessage(
      String ticketRef, String workflowRunId, String stage, String runnerExecutionId) {
    String subjectRef = (ticketRef == null || ticketRef.isBlank()) ? "DeliveryLine" : ticketRef;
    // AC12 — stable governance trailers carried into git history (Trap T9).
    return "["
        + subjectRef
        + "] DeliveryLine "
        + stage
        + " output\n\n"
        + "Deliveryline-Run: "
        + workflowRunId
        + "\n"
        + "Deliveryline-Stage: "
        + stage
        + "\n"
        + "Deliveryline-RunnerExecution: "
        + runnerExecutionId
        + "\n";
  }

  private static boolean isExistingGitRepository(Path repoDir) {
    return Files.isDirectory(repoDir.resolve(".git"), LinkOption.NOFOLLOW_LINKS);
  }

  private static void ensureCloneTargetEmpty(Path repoDir, String runnerExecutionId) {
    if (!Files.exists(repoDir, LinkOption.NOFOLLOW_LINKS)) {
      return;
    }
    try (java.util.stream.Stream<Path> entries = Files.list(repoDir)) {
      if (entries.findAny().isPresent()) {
        throw new IllegalStateException(
            "runner repo workspace is not empty and is not a git repository: " + runnerExecutionId);
      }
    } catch (java.io.IOException error) {
      throw new IllegalStateException(
          "runner repo workspace could not be inspected: " + runnerExecutionId, error);
    }
  }

  private static GitCommandException mapGitHubFailure(
      String op, RepositoryHostAdapterException adapterFailure) {
    return new GitCommandException(
        switch (adapterFailure.failureCategory()) {
          case GITHUB_AUTH_FAILED, GITHUB_PERMISSION_DENIED ->
              IntegrationFailureCategory.GIT_AUTH_FAILED;
          case GITHUB_BRANCH_PROTECTED ->
              IntegrationFailureCategory.GIT_BRANCH_PROTECTION_VIOLATION;
          case GITHUB_NETWORK_FAILURE, GITHUB_RATE_LIMITED, GITHUB_API_VERSION_INCOMPATIBLE ->
              IntegrationFailureCategory.GIT_NETWORK_FAILURE;
          default -> IntegrationFailureCategory.GIT_PUSH_REJECTED;
        },
        "GitHub " + op + " failed after git push: " + adapterFailure.getMessage(),
        adapterFailure);
  }

  private static void validateBranchName(String branch) {
    if (branch.contains("..")
        || branch.contains("@{")
        || branch.contains("\\")
        || branch.endsWith("/")
        || branch.endsWith(".")
        || branch.endsWith(".lock")
        || branch.startsWith(".")
        || branch.chars().anyMatch(Character::isISOControl)
        || branch.contains(" ")) {
      throw new IllegalArgumentException("ticket ref must produce a valid git branch: " + branch);
    }
    for (String part : branch.split("/")) {
      if (part.isBlank() || part.startsWith(".") || part.endsWith(".lock")) {
        throw new IllegalArgumentException("ticket ref must produce a valid git branch: " + branch);
      }
    }
  }

  /** Result of {@link #prepareWorkspace} — the runner mount reference + branch context (AC4). */
  public record RepositoryMount(
      Path repoHostPath, String containerMountPath, String defaultBranch, String branch) {}

  /** Result of {@link #captureAndPush} — commit SHA + branch + PR ref for the prOutput artifact. */
  public record RepositoryPushOutcome(
      String commitSha, String branchRef, String prRef, boolean committed) {}
}
