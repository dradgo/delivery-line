package org.dradgo.application.workflow;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import org.dradgo.application.observability.MdcKeys;
import org.dradgo.application.project.ProjectRuntimeConfigResolver;
import org.dradgo.application.runner.CapturedLogs;
import org.dradgo.application.runner.ExecutionConstraints;
import org.dradgo.application.runner.RunnerExecutionService;
import org.dradgo.application.runner.RunnerLogCaptureService;
import org.dradgo.application.runner.RunnerProperties;
import org.dradgo.application.runner.spi.RunnerExecutionRecordPort;
import org.dradgo.application.runner.spi.RunnerWorkspaceStore;
import org.dradgo.application.runner.workspace.spi.BuildCommandPort;
import org.dradgo.application.runner.workspace.spi.GitCommandPort;
import org.dradgo.application.workflow.WorkflowTransitionService.TransitionActor;
import org.dradgo.application.workflow.spi.WorkflowRunRejectionLoopPort;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.id.PublicIdPrefixes;
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.FailureCategory;
import org.dradgo.domain.registry.RunnerStage;
import org.dradgo.domain.registry.WorkflowState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * Story 3h-1 (AC1/AC3/AC4/AC5/AC6/AC7, FR75) — the pre-review build-validation gate. When a
 * governed project has {@code buildStageEnabled} + a {@code buildCommand} and the PR_OUTPUT run has
 * a materialized workspace, this service runs the build backend-side (via {@link BuildCommandPort}
 * in the workspace dir) BEFORE the code is pushed and reviewed:
 *
 * <ul>
 *   <li><b>Applicability + trigger</b> ({@link #tryGateBehindBuild}) — called from {@code
 *       RunnerBroker.handleSuccess} on a PR_OUTPUT success while still inside the poller
 *       transaction. When the gate applies it reserves a BUILD {@code runner_executions} row
 *       (reusing the story 3.6 raw-output capture + 3d-5 step view — zero new persistence) and
 *       registers an afterCommit hook (the 3h-0 {@link AfterCommitSideEffectRunner}) so the
 *       (potentially minutes-long) build runs AFTER the ingest tx commits, never holding the broker
 *       tx. Returns {@code true} when it took ownership of the tail (the broker must NOT run the
 *       inline tail); {@code false} otherwise (disabled / no command / no workspace ⇒ pre-3h
 *       parity).
 *   <li><b>Success</b> — the deferred tail ({@code completeExecutionTailAndAdvance}, passed as a
 *       continuation) runs: captureAndPush + WaitingForReview + reviewer enqueue.
 *   <li><b>Failure</b> — a bounded auto-fix loop (mirrors {@code
 *       TechnicalApprovalService.rejectImplementation}): bump {@code build_fix_loop_count}; while
 *       {@code <= cap} re-dispatch the implementation runner (the {@code build.failure} log flows
 *       back by reference via {@code ContextBundleService}); on the attempt that reaches the cap,
 *       transition the run to {@code FAILED} with {@code RUNNER_BUILD_FAILED} and flip the shared
 *       escalation marker ONCE.
 * </ul>
 *
 * <p><b>AC6 (no-token guarantee)</b> — a BUILD execution runs backend-side (no LLM, no
 * runner-result) so the {@code onResult} token/provider capture paths are never invoked for it; its
 * token columns stay NULL and no {@code provider_usage_snapshots} row is written.
 */
@Service
public class BuildStageService {

  private static final Logger log = LoggerFactory.getLogger(BuildStageService.class);

  private final ProjectRuntimeConfigResolver projectRuntimeConfigResolver;
  private final RunnerProperties runnerProperties;
  private final RunnerWorkspaceStore workspaceStore;
  private final BuildCommandPort buildCommandPort;
  private final RunnerExecutionRecordPort recordPort;
  private final RunnerExecutionService executionService;
  private final RunnerLogCaptureService logCaptureService;
  private final AfterCommitSideEffectRunner afterCommit;
  private final WorkflowRunRejectionLoopPort rejectionLoopPort;
  private final BuildFixEscalationThresholdProvider thresholdProvider;
  private final WorkflowTransitionService transitionService;
  private final GitCommandPort gitCommandPort;
  // The build runs on this executor (NOT the poller thread that fires the afterCommit hook) so a
  // minutes-long build never blocks the single scheduled poller. Production = virtual-thread-per-
  // task; the test profile injects a same-thread executor for deterministic ITs.
  private final Executor buildExecutor;
  // Lazy: WorkflowOrchestrationService depends (transitively) on the broker which supplies this
  // service lazily, so keep the reverse edge lazy too for ctor-order safety.
  private final Supplier<WorkflowOrchestrationService> orchestrationSupplier;

  public BuildStageService(
      ProjectRuntimeConfigResolver projectRuntimeConfigResolver,
      RunnerProperties runnerProperties,
      RunnerWorkspaceStore workspaceStore,
      BuildCommandPort buildCommandPort,
      RunnerExecutionRecordPort recordPort,
      RunnerExecutionService executionService,
      RunnerLogCaptureService logCaptureService,
      AfterCommitSideEffectRunner afterCommit,
      WorkflowRunRejectionLoopPort rejectionLoopPort,
      BuildFixEscalationThresholdProvider thresholdProvider,
      WorkflowTransitionService transitionService,
      GitCommandPort gitCommandPort,
      @Qualifier("buildStageExecutor") Executor buildExecutor,
      ObjectProvider<WorkflowOrchestrationService> orchestrationProvider) {
    this.projectRuntimeConfigResolver = projectRuntimeConfigResolver;
    this.runnerProperties = runnerProperties;
    this.workspaceStore = workspaceStore;
    this.buildCommandPort = buildCommandPort;
    this.recordPort = recordPort;
    this.executionService = executionService;
    this.logCaptureService = logCaptureService;
    this.afterCommit = afterCommit;
    this.rejectionLoopPort = rejectionLoopPort;
    this.thresholdProvider = thresholdProvider;
    this.transitionService = transitionService;
    this.gitCommandPort = gitCommandPort;
    this.buildExecutor = buildExecutor;
    this.orchestrationSupplier = orchestrationProvider::getIfAvailable;
  }

  /**
   * AC1/AC3 — if the build gate applies to this PR_OUTPUT success, reserve a BUILD execution row
   * (in the caller's active tx) and register the afterCommit build hook, returning {@code true}.
   * Returns {@code false} (gate skipped ⇒ pre-3h parity) when the project is not build-enabled, has
   * no build command, or the run has no materialized workspace.
   *
   * @param onBuildSuccessTail the deferred delivery tail (captureAndPush + WaitingForReview +
   *     reviewer) — run only on BUILD success
   */
  public boolean tryGateBehindBuild(
      String workflowRunId,
      String prOutputRunnerExecutionId,
      String correlationId,
      Runnable onBuildSuccessTail) {
    if (!projectRuntimeConfigResolver.resolveBuildStageEnabled(workflowRunId)) {
      log.info("build stage skipped workflowRunId={} reason=disabled", workflowRunId);
      return false;
    }
    Optional<String> command = projectRuntimeConfigResolver.resolveBuildCommand(workflowRunId);
    if (command.isEmpty()) {
      log.info("build stage skipped workflowRunId={} reason=no_build_command", workflowRunId);
      return false;
    }
    Optional<Path> repoDir = workspaceStore.resolveRepositoryDir(prOutputRunnerExecutionId);
    if (repoDir.isEmpty()) {
      log.info(
          "build stage skipped workflowRunId={} runnerExecutionId={} reason=no_repo_workspace",
          workflowRunId,
          prOutputRunnerExecutionId);
      return false;
    }

    // Reserve the BUILD execution row inside the caller's (poller) transaction so it commits with
    // the
    // PR_OUTPUT ingest and the deferred build runs against an existing row.
    String buildRunnerExecutionId = PublicIdPrefixes.RUNNER_EXECUTION.next();
    int contextBundleVersion =
        recordPort.nextContextBundleVersion(workflowRunId, RunnerStage.BUILD);
    recordPort.insertPending(
        buildRunnerExecutionId,
        workflowRunId,
        RunnerStage.BUILD,
        contextBundleVersion,
        new ExecutionConstraints(runnerProperties.buildTimeout(), true));
    log.info(
        "build stage enabled workflowRunId={} prOutputRunnerExecutionId={} buildRunnerExecutionId={}",
        workflowRunId,
        prOutputRunnerExecutionId,
        buildRunnerExecutionId);

    // Fire AFTER the poller tx commits, then OFFLOAD the (potentially minutes-long) build onto the
    // build executor so the afterCommit callback — which runs synchronously on the committing
    // poller
    // thread — returns immediately and never blocks the single scheduled poller for the build's
    // duration. (Test profile: a same-thread executor keeps this deterministic.)
    afterCommit.runAfterCommit(
        "build-stage",
        workflowRunId,
        () ->
            buildExecutor.execute(
                () ->
                    runBuild(
                        workflowRunId,
                        prOutputRunnerExecutionId,
                        buildRunnerExecutionId,
                        command.get(),
                        repoDir.get(),
                        correlationId,
                        onBuildSuccessTail)));
    return true;
  }

  /** Post-commit: run the build OUTSIDE any tx, capture logs, then advance or loop. */
  private void runBuild(
      String workflowRunId,
      String prOutputRunnerExecutionId,
      String buildRunnerExecutionId,
      String command,
      Path repoDir,
      String correlationId,
      Runnable onBuildSuccessTail) {
    String priorRun = MdcKeys.beginScope(MdcKeys.WORKFLOW_RUN_ID, workflowRunId);
    String priorRex = MdcKeys.beginScope(MdcKeys.RUNNER_EXECUTION_ID, buildRunnerExecutionId);
    try {
      // Snapshot the untracked (non-ignored) worktree BEFORE the build so its own outputs can be
      // discarded before push (BUILD is validation-only — its files must never reach
      // captureAndPush's
      // `git add -A`). Null = snapshot failed ⇒ skip discard entirely (never a blind clean).
      List<String> preBuildUntracked = safeListUntracked(repoDir);

      Duration timeout = runnerProperties.buildTimeout();
      BuildCommandPort.BuildResult result = buildCommandPort.run(repoDir, command, timeout);

      // Capture the raw output through the story-3.6 redaction / secret-scan / store path and
      // finalize
      // the BUILD execution row. NEVER log the bytes (only ids/lengths). REQUIRES_NEW so it commits
      // independently of the (now-committed) ingest tx.
      afterCommit.runInNewTransaction(
          "build-stage-capture",
          workflowRunId,
          () -> {
            CapturedLogs captured =
                logCaptureService.captureLogs(
                    buildRunnerExecutionId, workflowRunId, result.stdout(), result.stderr());
            executionService.recordRawOutput(buildRunnerExecutionId, captured);
            if (result.succeeded()) {
              executionService.recordCompleted(buildRunnerExecutionId);
            } else {
              executionService.recordFailed(
                  buildRunnerExecutionId, FailureCategory.RUNNER_NON_ZERO_EXIT);
            }
          });

      if (result.succeeded()) {
        // Discard build-created files BEFORE the deferred captureAndPush (git add -A) so a passing
        // build never pollutes the PR with its own outputs.
        discardBuildArtifacts(workflowRunId, repoDir, preBuildUntracked);
        log.info(
            "build stage passed workflowRunId={} buildRunnerExecutionId={} exitCode=0",
            workflowRunId,
            buildRunnerExecutionId);
        afterCommit.runInNewTransaction("build-stage-advance", workflowRunId, onBuildSuccessTail);
      } else if (result.exitCode() == BuildCommandPort.EXECUTOR_FAILURE_EXIT_CODE) {
        // Infra/executor failure (the build process could not even start — e.g. missing shell or
        // build tool). Re-dispatching the implementation runner CANNOT fix a broken build executor,
        // and would burn the whole fix-loop budget on unactionable feedback before failing anyway.
        // Fail fast + escalate once instead of looping. (A timeout, by contrast, can be
        // code-induced
        // — e.g. a hanging test — so it stays in the bounded loop below.)
        log.error(
            "build stage executor failure workflowRunId={} buildRunnerExecutionId={} exitCode={} — "
                + "failing run without fix loop",
            workflowRunId,
            buildRunnerExecutionId,
            result.exitCode());
        afterCommit.runInNewTransaction(
            "build-stage-executor-failure",
            workflowRunId,
            () ->
                failRunForExecutorFailure(
                    workflowRunId, prOutputRunnerExecutionId, buildRunnerExecutionId));
      } else {
        log.warn(
            "build stage failed workflowRunId={} buildRunnerExecutionId={} exitCode={}",
            workflowRunId,
            buildRunnerExecutionId,
            result.exitCode());
        afterCommit.runInNewTransaction(
            "build-stage-fix-loop",
            workflowRunId,
            () ->
                handleBuildFailure(
                    workflowRunId,
                    prOutputRunnerExecutionId,
                    buildRunnerExecutionId,
                    correlationId));
      }
    } finally {
      MdcKeys.endScope(MdcKeys.RUNNER_EXECUTION_ID, priorRex);
      MdcKeys.endScope(MdcKeys.WORKFLOW_RUN_ID, priorRun);
    }
  }

  /**
   * AC4/AC5 — the bounded auto-fix loop. Bump {@code build_fix_loop_count}; while it is {@code <=
   * cap} re-dispatch the implementation runner (the {@code build.failure} feedback flows back by
   * reference via {@code ContextBundleService} on the regenerated bundle — the run is already
   * {@code Executing}, so no transition is needed). With cap {@code N} this allows up to {@code N}
   * fix re-dispatches; the run is FAILED on the {@code (N+1)}-th consecutive build failure ({@code
   * loopCount > cap}) with {@code RUNNER_BUILD_FAILED}, flipping the shared escalation marker ONCE.
   *
   * <p>The producing PR_OUTPUT execution is finalized FIRST: it is still {@code running} (its
   * completion lives in the success-only delivery tail), and leaving it active would make {@code
   * retryImplementation} a {@code Replayed} no-op against the sub-stage-aware in-flight dispatch
   * guard — the loop would silently never re-run and the run would wedge. The PR_OUTPUT runner
   * itself succeeded (it produced valid output); only the backend-side BUILD failed, so {@code
   * completed} is the correct terminal state for that row.
   */
  private void handleBuildFailure(
      String workflowRunId,
      String prOutputRunnerExecutionId,
      String buildRunnerExecutionId,
      String correlationId) {
    finalizeProducingExecution(prOutputRunnerExecutionId);
    int loopCount = rejectionLoopPort.incrementAndReadBuildFixLoopCount(workflowRunId);
    int cap = thresholdProvider.get();

    if (loopCount <= cap) {
      log.warn(
          "build fix loop attempt {}/{} workflowRunId={} buildRunnerExecutionId={}",
          loopCount,
          cap,
          workflowRunId,
          buildRunnerExecutionId);
      // The run never left Executing during BUILD, so re-dispatch only (Trap T1 — never a second
      // transition). BUILD only fires post-PR_OUTPUT, so the producing stage is always PR_OUTPUT ⇒
      // retryImplementation. The build.failure reference is materialized by ContextBundleService.
      WorkflowOrchestrationService orchestration = orchestrationSupplier.get();
      if (orchestration != null) {
        orchestration.retryImplementation(workflowRunId, correlationId);
      } else {
        log.warn(
            "build fix loop cannot re-dispatch (no orchestration bean) workflowRunId={}",
            workflowRunId);
      }
      return;
    }

    // Cap exceeded — fail the run and escalate ONCE (never push past an unresolved build failure).
    log.warn(
        "build fix loop cap exceeded workflowRunId={} loopCount={} cap={} — failing run",
        workflowRunId,
        loopCount,
        cap);
    failRun(
        workflowRunId,
        "build-fix:" + workflowRunId + ":" + loopCount,
        "build failed after " + cap + " fix attempts",
        Map.of(
            "runnerExecutionId", buildRunnerExecutionId,
            "buildFixLoopCount", loopCount));
  }

  /**
   * Executor-failure fast-fail (the build could not be started): finalize the producing PR_OUTPUT
   * execution (same reasoning as the fix loop), then FAIL the run with {@code RUNNER_BUILD_FAILED}
   * + escalate once — WITHOUT burning a fix-loop iteration, since re-running the LLM cannot repair
   * a broken build executor.
   */
  private void failRunForExecutorFailure(
      String workflowRunId, String prOutputRunnerExecutionId, String buildRunnerExecutionId) {
    finalizeProducingExecution(prOutputRunnerExecutionId);
    failRun(
        workflowRunId,
        "build-infra:" + workflowRunId + ":" + buildRunnerExecutionId,
        "build executor unavailable",
        Map.of("runnerExecutionId", buildRunnerExecutionId));
  }

  /**
   * Shared terminal path — transition the run to {@code FAILED} with {@code RUNNER_BUILD_FAILED}
   * (recorded on the workflow_events row) and flip the shared escalation marker ONCE. The
   * idempotency {@code key} is distinct per call site (fix-loop iteration vs executor failure).
   */
  private void failRun(
      String workflowRunId, String key, String reason, Map<String, Object> metadata) {
    transitionService.transition(
        workflowRunId,
        WorkflowState.FAILED,
        new TransitionActor("system", ActorType.SYSTEM),
        reason,
        key,
        FailureCategory.RUNNER_BUILD_FAILED,
        metadata);
    boolean flipped = rejectionLoopPort.markEscalationOnce(workflowRunId) == 1;
    log.warn(
        "build fix loop escalation workflowRunId={} escalationMarkerFlipped={}",
        workflowRunId,
        flipped);
  }

  /**
   * Finalize the producing PR_OUTPUT execution row as {@code completed} so it no longer strands the
   * sub-stage-aware in-flight dispatch guard (which would otherwise turn the fix re-dispatch into a
   * no-op) and so the terminal FAILED path leaves no dangling {@code running} row for the timeout
   * sweeper. Idempotent: an already-terminal row (replay / concurrent finalize) is a no-op.
   */
  private void finalizeProducingExecution(String prOutputRunnerExecutionId) {
    try {
      executionService.recordCompleted(prOutputRunnerExecutionId);
    } catch (DomainException alreadyTerminal) {
      if (alreadyTerminal.errorCode() != DomainErrorCode.ILLEGAL_TRANSITION) {
        throw alreadyTerminal;
      }
      log.info(
          "build stage producing execution already terminal runnerExecutionId={}",
          prOutputRunnerExecutionId);
    }
  }

  /**
   * Best-effort snapshot of the untracked, non-ignored worktree paths before the build. Returns
   * {@code null} on any failure — the caller then SKIPS the post-build discard rather than risk a
   * blind clean (fail toward keeping files).
   */
  private List<String> safeListUntracked(Path repoDir) {
    try {
      return gitCommandPort.listUntrackedFiles(repoDir);
    } catch (RuntimeException gitFailure) {
      log.warn(
          "build stage could not snapshot untracked files (skipping artifact discard) repoDir={} "
              + "cause={}",
          repoDir,
          gitFailure.getMessage());
      return null;
    }
  }

  /**
   * Remove only the untracked (non-ignored) paths the build itself created — the post-build set
   * minus the pre-build snapshot — so runner-authored files (present in the snapshot) and tracked
   * content are never touched. Best-effort: a discard failure must NEVER block the push (the run
   * still ships valid runner output); it just logs and proceeds. Skipped when the pre-build
   * snapshot is {@code null} (snapshot failed).
   */
  private void discardBuildArtifacts(
      String workflowRunId, Path repoDir, List<String> preBuildUntracked) {
    if (preBuildUntracked == null) {
      return;
    }
    try {
      Set<String> before = Set.copyOf(preBuildUntracked);
      List<String> created =
          gitCommandPort.listUntrackedFiles(repoDir).stream()
              .filter(path -> !before.contains(path))
              .toList();
      if (!created.isEmpty()) {
        gitCommandPort.removeUntrackedPaths(repoDir, created);
        log.info(
            "build stage discarded {} build-created untracked path(s) before push workflowRunId={}",
            created.size(),
            workflowRunId);
      }
    } catch (RuntimeException gitFailure) {
      log.warn(
          "build stage artifact discard failed (proceeding to push) workflowRunId={} cause={}",
          workflowRunId,
          gitFailure.getMessage());
    }
  }
}
