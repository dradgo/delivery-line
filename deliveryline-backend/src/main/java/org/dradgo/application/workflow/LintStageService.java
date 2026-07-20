package org.dradgo.application.workflow;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;
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
import org.dradgo.application.security.RedactionPolicyService;
import org.dradgo.application.workflow.WorkflowTransitionService.TransitionActor;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.id.PublicIdPrefixes;
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.DataClassification;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.FailureCategory;
import org.dradgo.domain.registry.RunnerStage;
import org.dradgo.domain.registry.WorkflowState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * Story 3h-2 (AC1/AC3/AC4/AC6/AC7, FR76) — the pre-review CPU lint gate. When a governed project
 * has {@code lintStageEnabled} + {@code lintCommands} and the PR_OUTPUT run has a materialized
 * workspace, this service runs the configured linters backend-side (each command via {@link
 * BuildCommandPort} in the workspace dir) AFTER a successful BUILD and BEFORE the code is pushed
 * and reviewed. It is the structural twin of {@code BuildStageService}, with two deliberate
 * divergences:
 *
 * <ul>
 *   <li><b>Multi-command, fail-fast</b> — {@code runLint} LOOPS over {@code lintCommands}, stopping
 *       at the first command whose exit signals a critical finding (Decision 2).
 *   <li><b>Parks, never auto-fails</b> — a critical finding transitions the run {@code EXECUTING ->
 *       WaitingForLintApproval} (a governed gate) rather than driving a bounded auto-fix loop; the
 *       operator-driven fix loop lives in {@code LintApprovalService} (Decision 3). The LINT {@code
 *       runner_executions} row is recorded {@code FAILED} with the existing {@code
 *       RUNNER_NON_ZERO_EXIT} category when critical, {@code COMPLETED} when clean/advisory.
 * </ul>
 *
 * <p><b>AC6 (persistence)</b> — the severity-classified findings are serialized to the LINT row's
 * {@code lint_findings} jsonb column (no new table). <b>AC7 (no-token guarantee)</b> — a LINT
 * execution runs backend-side (no LLM, no runner-result) so the token/provider capture paths are
 * never invoked; its token columns stay NULL and no {@code provider_usage_snapshots} row is
 * written.
 */
@Service
public class LintStageService {

  private static final Logger log = LoggerFactory.getLogger(LintStageService.class);

  /**
   * Cross-command aggregate cap on persisted findings (mirrors the classifier's per-command cap).
   */
  private static final int MAX_AGGREGATE_FINDINGS = 500;

  private final ProjectRuntimeConfigResolver projectRuntimeConfigResolver;
  private final RunnerProperties runnerProperties;
  private final RunnerWorkspaceStore workspaceStore;
  private final BuildCommandPort buildCommandPort;
  private final RunnerExecutionRecordPort recordPort;
  private final RunnerExecutionService executionService;
  private final RunnerLogCaptureService logCaptureService;
  private final AfterCommitSideEffectRunner afterCommit;
  private final WorkflowTransitionService transitionService;
  private final org.dradgo.application.workflow.spi.WorkflowRunReadPort workflowRunReadPort;
  private final LintFindingsClassifier classifier;
  private final RedactionPolicyService redactionPolicyService;
  private final ObjectMapper objectMapper = new ObjectMapper();
  // The lint run offloads onto this executor (NOT the poller thread that fires the afterCommit
  // hook)
  // so a minutes-long lint never blocks the single scheduled poller (essential when BUILD is
  // disabled and the lint gate fires directly from the poller thread). Test profile: same-thread.
  private final Executor lintExecutor;

  public LintStageService(
      ProjectRuntimeConfigResolver projectRuntimeConfigResolver,
      RunnerProperties runnerProperties,
      RunnerWorkspaceStore workspaceStore,
      BuildCommandPort buildCommandPort,
      RunnerExecutionRecordPort recordPort,
      RunnerExecutionService executionService,
      RunnerLogCaptureService logCaptureService,
      AfterCommitSideEffectRunner afterCommit,
      WorkflowTransitionService transitionService,
      org.dradgo.application.workflow.spi.WorkflowRunReadPort workflowRunReadPort,
      LintFindingsClassifier classifier,
      RedactionPolicyService redactionPolicyService,
      @Qualifier("lintStageExecutor") Executor lintExecutor) {
    this.projectRuntimeConfigResolver = projectRuntimeConfigResolver;
    this.runnerProperties = runnerProperties;
    this.workspaceStore = workspaceStore;
    this.buildCommandPort = buildCommandPort;
    this.recordPort = recordPort;
    this.executionService = executionService;
    this.logCaptureService = logCaptureService;
    this.afterCommit = afterCommit;
    this.transitionService = transitionService;
    this.workflowRunReadPort = workflowRunReadPort;
    this.classifier = classifier;
    this.redactionPolicyService = redactionPolicyService;
    this.lintExecutor = lintExecutor;
  }

  /**
   * AC1/AC3 — if the lint gate applies to this PR_OUTPUT success, reserve a LINT execution row (in
   * the caller's active tx) and register the afterCommit lint hook, returning {@code true}. Returns
   * {@code false} (gate skipped ⇒ pre-3h-2 parity) when the project is not lint-enabled, has no
   * lint commands, or the run has no materialized workspace.
   *
   * @param onLintPassTail the deferred delivery tail (resume-delivery-tail seam) — run only when
   *     LINT produces NO critical finding.
   */
  public boolean tryGateBehindLint(
      String workflowRunId,
      String prOutputRunnerExecutionId,
      String correlationId,
      Runnable onLintPassTail) {
    if (!projectRuntimeConfigResolver.resolveLintStageEnabled(workflowRunId)) {
      log.info("lint stage skipped workflowRunId={} reason=disabled", workflowRunId);
      return false;
    }
    List<String> commands = projectRuntimeConfigResolver.resolveLintCommands(workflowRunId);
    if (commands.isEmpty()) {
      log.info("lint stage skipped workflowRunId={} reason=no_lint_commands", workflowRunId);
      return false;
    }
    Optional<Path> repoDir = workspaceStore.resolveRepositoryDir(prOutputRunnerExecutionId);
    if (repoDir.isEmpty()) {
      log.info(
          "lint stage skipped workflowRunId={} runnerExecutionId={} reason=no_repo_workspace",
          workflowRunId,
          prOutputRunnerExecutionId);
      return false;
    }

    // Reserve the LINT execution row inside the caller's (build-advance / poller) transaction so it
    // commits with the ingest and the deferred lint runs against an existing row.
    String lintRunnerExecutionId = PublicIdPrefixes.RUNNER_EXECUTION.next();
    int contextBundleVersion = recordPort.nextContextBundleVersion(workflowRunId, RunnerStage.LINT);
    recordPort.insertPending(
        lintRunnerExecutionId,
        workflowRunId,
        RunnerStage.LINT,
        contextBundleVersion,
        new ExecutionConstraints(runnerProperties.lintTimeout(), true));
    log.info(
        "lint stage enabled workflowRunId={} prOutputRunnerExecutionId={} lintRunnerExecutionId={} "
            + "commandCount={}",
        workflowRunId,
        prOutputRunnerExecutionId,
        lintRunnerExecutionId,
        commands.size());

    // Fire AFTER the ingest tx commits, then OFFLOAD the (potentially minutes-long) lint onto the
    // lint executor so the afterCommit callback returns immediately and never blocks the poller.
    afterCommit.runAfterCommit(
        "lint-stage",
        workflowRunId,
        () ->
            lintExecutor.execute(
                () ->
                    runLint(
                        workflowRunId,
                        prOutputRunnerExecutionId,
                        lintRunnerExecutionId,
                        commands,
                        repoDir.get(),
                        correlationId,
                        onLintPassTail)));
    return true;
  }

  /**
   * Post-commit: run each lint command OUTSIDE any tx, classify + capture, then park or advance.
   */
  private void runLint(
      String workflowRunId,
      String prOutputRunnerExecutionId,
      String lintRunnerExecutionId,
      List<String> commands,
      Path repoDir,
      String correlationId,
      Runnable onLintPassTail) {
    String priorRun = MdcKeys.beginScope(MdcKeys.WORKFLOW_RUN_ID, workflowRunId);
    String priorRex = MdcKeys.beginScope(MdcKeys.RUNNER_EXECUTION_ID, lintRunnerExecutionId);
    try {
      Duration timeout = runnerProperties.lintTimeout();
      List<LintFinding> aggregatedFindings = new ArrayList<>();
      StringBuilder aggregatedStdout = new StringBuilder();
      StringBuilder aggregatedStderr = new StringBuilder();
      boolean critical = false;

      for (String command : commands) {
        BuildCommandPort.BuildResult result = buildCommandPort.run(repoDir, command, timeout);
        // P3 (code-review 2026-07-06) — redact each stream BEFORE classification so the persisted
        // lint_findings (served over REST) never carry raw secret bytes (the LintFinding
        // invariant).
        // Mirrors RunnerLogCaptureService: redaction is the first action on each stream, so the
        // later
        // captureLogs re-redaction of the aggregate is an idempotent no-op.
        String redactedStdout = redact(result.stdout());
        String redactedStderr = redact(result.stderr());
        aggregatedStdout.append(redactedStdout).append('\n');
        aggregatedStderr.append(redactedStderr).append('\n');
        LintFindings classified =
            classifier.classify(command, result.exitCode(), redactedStdout, redactedStderr);
        log.info(
            "lint command executed workflowRunId={} lintRunnerExecutionId={} exitCode={} "
                + "critical={} findingCount={}",
            workflowRunId,
            lintRunnerExecutionId,
            result.exitCode(),
            classified.hasCritical(),
            classified.findings().size());
        if (classified.hasCritical()) {
          // Fail-fast on the first command whose exit signals critical findings (Decision 2). This
          // command is always LAST (we break here). Code-review 2026-07-06 re-review: give its
          // findings PRIORITY within MAX_AGGREGATE_FINDINGS — a naive tail-append would DROP them
          // (incl. the classifier's synthetic `error` entry) when earlier non-critical commands
          // already filled the cap, leaving a `gated` result whose panel shows no error.
          critical = true;
          addGatingFindingsWithinCap(aggregatedFindings, classified.findings());
          break;
        }
        // Non-critical: cap the cross-command aggregate so a run with many non-critical lint
        // commands cannot persist an unbounded jsonb payload.
        for (LintFinding finding : classified.findings()) {
          if (aggregatedFindings.size() >= MAX_AGGREGATE_FINDINGS) {
            break;
          }
          aggregatedFindings.add(finding);
        }
      }

      LintFindings aggregate = new LintFindings(critical, aggregatedFindings);
      log.info(
          "lint classification result workflowRunId={} lintRunnerExecutionId={} critical={} "
              + "total={}",
          workflowRunId,
          lintRunnerExecutionId,
          critical,
          aggregate.findings().size());

      boolean isCritical = critical;
      String findingsJson = serializeFindings(aggregate);
      String stdout = aggregatedStdout.toString();
      String stderr = aggregatedStderr.toString();

      // Capture the aggregated raw output through the story-3.6 redaction path, persist the
      // classified findings jsonb, and finalize the LINT row — REQUIRES_NEW so it commits
      // independently of the (now-committed) ingest tx. NEVER logs the bytes (ids/counts only).
      afterCommit.runInNewTransaction(
          "lint-stage-capture",
          workflowRunId,
          () -> {
            CapturedLogs captured =
                logCaptureService.captureLogs(lintRunnerExecutionId, workflowRunId, stdout, stderr);
            executionService.recordRawOutput(lintRunnerExecutionId, captured);
            executionService.recordLintFindings(lintRunnerExecutionId, findingsJson);
            if (isCritical) {
              executionService.recordFailed(
                  lintRunnerExecutionId, FailureCategory.RUNNER_NON_ZERO_EXIT);
            } else {
              executionService.recordCompleted(lintRunnerExecutionId);
            }
          });

      if (isCritical) {
        log.warn(
            "lint stage critical findings workflowRunId={} lintRunnerExecutionId={} — parking at "
                + "WaitingForLintApproval",
            workflowRunId,
            lintRunnerExecutionId);
        afterCommit.runInNewTransaction(
            "lint-stage-park",
            workflowRunId,
            () ->
                parkForLintApproval(
                    workflowRunId, prOutputRunnerExecutionId, lintRunnerExecutionId));
      } else {
        log.info(
            "lint stage passed workflowRunId={} lintRunnerExecutionId={} — resuming delivery tail",
            workflowRunId,
            lintRunnerExecutionId);
        afterCommit.runInNewTransaction("lint-stage-advance", workflowRunId, onLintPassTail);
      }
    } finally {
      MdcKeys.endScope(MdcKeys.RUNNER_EXECUTION_ID, priorRex);
      MdcKeys.endScope(MdcKeys.WORKFLOW_RUN_ID, priorRun);
    }
  }

  /**
   * AC4 — park the run at the pre-review lint gate. Transition {@code EXECUTING ->
   * WaitingForLintApproval} (a NON-failure transition — no failure category, Decision 3); the
   * delivery tail is NOT run (no push, no review). The run leaves the gate only on the operator
   * actions (approve_lint / request_lint_fix) executed by {@code LintApprovalService}.
   */
  private void parkForLintApproval(
      String workflowRunId, String prOutputRunnerExecutionId, String lintRunnerExecutionId) {
    // Story 4.8 review — executor-as-gate: a stage success landing while the run is Paused must
    // NOT park it (PAUSED → WaitingForLintApproval became a LEGAL resume edge, so the transition
    // table alone no longer rejects it). Log + ignore, like the broker's late-result guard; the
    // operator's resume owns the next transition.
    if (isPausedIgnoringStageSuccess("lint-stage-park", workflowRunId, lintRunnerExecutionId)) {
      return;
    }
    // Finalize the producing PR_OUTPUT execution FIRST — it succeeded (only the backend-side LINT
    // gated) and is still `running` (its completion lives in the delivery tail that never ran). A
    // dangling running row would be timeout-reaped as RUNNER_TIMEOUT during the (possibly long)
    // operator-approval wait, and would make the later approve_lint re-dispatch a no-op.
    // Idempotent.
    finalizeProducingExecution(prOutputRunnerExecutionId);
    transitionService.transition(
        workflowRunId,
        WorkflowState.WAITING_FOR_LINT_APPROVAL,
        new TransitionActor("system", ActorType.SYSTEM),
        "critical lint findings require operator approval",
        "lint-gate:" + workflowRunId,
        Map.of("runnerExecutionId", lintRunnerExecutionId));
  }

  // Story 4.8 review — shared paused-run guard for stage-success side effects (see
  // parkForLintApproval). Reads the CURRENT committed state (this runs in the afterCommit hook's
  // fresh tx, after the pause would have committed).
  private boolean isPausedIgnoringStageSuccess(
      String seam, String workflowRunId, String runnerExecutionId) {
    boolean paused;
    try {
      paused =
          workflowRunReadPort
              .findByPublicId(workflowRunId)
              .map(run -> run.currentState() == WorkflowState.PAUSED)
              .orElse(false);
    } catch (RuntimeException readError) {
      // Best-effort: the guard must never break the park path. An unreadable run proceeds to the
      // transition, whose own guards still apply.
      log.warn(
          "{} paused-guard read failed workflowRunId={} errorClass={} — proceeding",
          seam,
          workflowRunId,
          readError.getClass().getSimpleName());
      return false;
    }
    if (paused) {
      log.warn(
          "{} ignored workflowRunId={} runnerExecutionId={} reason=run_paused — stage success "
              + "arrived while the run is Paused; operator resume owns the next transition",
          seam,
          workflowRunId,
          runnerExecutionId);
    }
    return paused;
  }

  /**
   * Finalize the producing PR_OUTPUT execution as {@code completed}. Idempotent — an
   * already-terminal row (replay / concurrent finalize) is a no-op. Mirrors {@code
   * BuildStageService.finalizeProducingExecution}.
   */
  private void finalizeProducingExecution(String prOutputRunnerExecutionId) {
    try {
      executionService.recordCompleted(prOutputRunnerExecutionId);
    } catch (DomainException alreadyTerminal) {
      if (alreadyTerminal.errorCode() != DomainErrorCode.ILLEGAL_TRANSITION) {
        throw alreadyTerminal;
      }
      log.info(
          "lint stage producing execution already terminal runnerExecutionId={}",
          prOutputRunnerExecutionId);
    }
  }

  /**
   * Code-review 2026-07-06 re-review — append the GATING command's findings while keeping the
   * cross-command aggregate within {@link #MAX_AGGREGATE_FINDINGS}. The gating command runs LAST
   * (fail-fast), so if earlier non-critical commands already filled the cap its findings — incl.
   * the classifier's synthetic {@code error} entry that EXPLAINS the gate — must not be silently
   * dropped. Give them priority by evicting the oldest (earlier, non-critical) findings from the
   * front to make room; the classifier already caps each command's own findings, so the gating
   * slice is bounded and the aggregate never exceeds the cap.
   */
  private static void addGatingFindingsWithinCap(
      List<LintFinding> aggregate, List<LintFinding> gating) {
    List<LintFinding> gatingCapped =
        gating.size() > MAX_AGGREGATE_FINDINGS ? gating.subList(0, MAX_AGGREGATE_FINDINGS) : gating;
    int overflow = aggregate.size() + gatingCapped.size() - MAX_AGGREGATE_FINDINGS;
    if (overflow > 0) {
      aggregate.subList(0, Math.min(overflow, aggregate.size())).clear();
    }
    aggregate.addAll(gatingCapped);
  }

  private String serializeFindings(LintFindings findings) {
    try {
      return objectMapper.writeValueAsString(findings);
    } catch (JsonProcessingException serializationFailure) {
      // A serialization failure must never wedge the gate — persist null (the read leg degrades to
      // state:"none") and keep the classified criticality decision intact.
      log.warn(
          "lint findings could not be serialized (persisting null): {}",
          serializationFailure.getMessage());
      return null;
    }
  }

  /**
   * P3 (code-review 2026-07-06) — redact a lint stream through the shared {@link
   * RedactionPolicyService} (LOCAL_ONLY classification, the same posture as {@code
   * RunnerLogCaptureService}) BEFORE it is classified or persisted, so no raw secret byte reaches
   * the {@code lint_findings} jsonb / the {@code getLintFindings} REST leg. Empty/null ⇒ "".
   */
  private String redact(String value) {
    if (value == null || value.isEmpty()) {
      return "";
    }
    return redactionPolicyService
        .redact(value, DataClassification.LOCAL_ONLY.value())
        .sanitizedText();
  }
}
