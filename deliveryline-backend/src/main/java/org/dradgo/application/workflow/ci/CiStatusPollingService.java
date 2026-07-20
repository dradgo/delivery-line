package org.dradgo.application.workflow.ci;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.dradgo.application.integration.repohost.RepositoryHostAdapter;
import org.dradgo.application.observability.MdcKeys;
import org.dradgo.application.project.ProjectConnectorResolver;
import org.dradgo.application.project.ProjectRuntimeConfigResolver;
import org.dradgo.application.runner.CapturedLogs;
import org.dradgo.application.runner.ExecutionConstraints;
import org.dradgo.application.runner.RunnerExecutionService;
import org.dradgo.application.runner.RunnerLogCaptureService;
import org.dradgo.application.runner.spi.RunnerExecutionRecordPort;
import org.dradgo.application.workflow.AfterCommitSideEffectRunner;
import org.dradgo.application.workflow.CiFixEscalationThresholdProvider;
import org.dradgo.application.workflow.WorkflowOrchestrationService;
import org.dradgo.application.workflow.WorkflowTransitionService;
import org.dradgo.application.workflow.WorkflowTransitionService.TransitionActor;
import org.dradgo.application.workflow.spi.CiPollRow;
import org.dradgo.application.workflow.spi.CiStatusPort;
import org.dradgo.application.workflow.spi.WorkflowEventRecord;
import org.dradgo.application.workflow.spi.WorkflowEventWritePort;
import org.dradgo.application.workflow.spi.WorkflowRunRejectionLoopPort;
import org.dradgo.domain.id.PublicIdPrefixes;
import org.dradgo.domain.integration.repohost.CiCheck;
import org.dradgo.domain.integration.repohost.CiStatus;
import org.dradgo.domain.integration.repohost.RepositoryRef;
import org.dradgo.domain.project.Project;
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.FailureCategory;
import org.dradgo.domain.registry.RunnerStage;
import org.dradgo.domain.registry.WorkflowEventType;
import org.dradgo.domain.registry.WorkflowState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Story 3h-5 (AC2/AC4/AC6, FR79, Decision 1) — the bounded, best-effort CI-investigation sweep. It
 * reads the CI build verdict for runs stamped {@code ci_status='pending'} by a backend push and, on
 * a red build, drives the bounded fix loop that re-dispatches the EXECUTION runner with the CI
 * failure log as referenced feedback.
 *
 * <p><strong>Framework-trigger-free</strong> — this is a plain {@code @Service}; the
 * {@code @Scheduled}/{@code @ConditionalOnProperty} trigger lives in {@code
 * infrastructure.config.CiInvestigationConfiguration}. When the sweep is disabled the trigger bean
 * is never registered, so {@link #sweep()} is simply never called and the delivery tail is byte-
 * identical to pre-3h-5 (AC4).
 *
 * <p><strong>Two-phase, no-I/O-under-lock</strong> (clones {@code
 * IntegrationConflictDetectionService}): phase 1 takes the sweep advisory lock + reads one keyset
 * window in a short tx (lock released on commit, NO external I/O); phase 2 reads each run's CI
 * verdict lock-free, then records the outcome inside a per-run {@code REQUIRES_NEW} transaction via
 * the 3h-0 {@link AfterCommitSideEffectRunner} (Layer B alone — ADR 0032's consume-note). One bad
 * run never aborts the tick.
 *
 * <p><strong>Escalate, never fail</strong> (Decision 5) — {@code WaitingForReview} has no {@code →
 * Failed} edge, so an exhausted {@code ci_fix_loop_count} flips the shared escalation marker ONCE
 * and leaves the run parked at {@code WaitingForReview} for Epic-4 recovery. No new {@code
 * FailureCategory}, no new {@code WorkflowEventType} (reuses {@code ESCALATION_REQUIRED}).
 */
@Service
public class CiStatusPollingService {

  private static final Logger log = LoggerFactory.getLogger(CiStatusPollingService.class);

  private static final String CI_POLL_LABEL = "ci-poll";
  private static final Duration CI_EXECUTION_TIMEOUT = Duration.ofMinutes(1);
  private static final String REDISPATCH_REASON = "ci build failed — bounded investigation loop";

  /**
   * Aggregate cap on the composed CI-failure body persisted as the CI rex raw output. Each check's
   * {@code failureText} is already individually bounded by the adapter, but a build with many
   * failed checks would otherwise concatenate to an unbounded body; cap the total so the persisted
   * {@code runner_executions} raw output stays bounded (the context bundle references it by id,
   * never inlines it).
   */
  private static final int MAX_COMPOSED_FAILURE_BYTES = 256 * 1024;

  private static final String TRUNCATION_SUFFIX = "\n…(truncated)";

  /**
   * Story 3h-5 review — grace window before ANY verdict (SUCCESS, NEUTRAL, or FAILURE) is acted on.
   * A read taken before GitHub has registered every workflow's check-runs — a passing subset
   * registered (SUCCESS), no check-runs / all cancelled-stale (NEUTRAL), or a fast check registered
   * and failed while the build check is not yet created (FAILURE) — polled within the first few
   * attempts is indistinguishable from GitHub not having registered the ref's check-runs yet, so
   * the run keeps polling (stays {@code pending}) until it has been polled at least this many
   * times; only then is the verdict acted on (recorded, or — for a red — re-dispatched) and the run
   * leaves the sweep. This closes both halves of the push→register race: a build that turns red
   * moments after the push is no longer dropped green/neutral (the non-red half, review rounds
   * 1-2), and a transient partial-registration red no longer triggers a premature re-dispatch (the
   * red half, 3rd review). The poll-attempt cap still overrides so a stuck ref cannot poll past its
   * budget.
   */
  private static final int MIN_POLL_ATTEMPTS_BEFORE_ACCEPT = 3;

  private final CiStatusPort ciStatusPort;
  private final ProjectRuntimeConfigResolver projectRuntimeConfigResolver;
  private final ProjectConnectorResolver projectConnectorResolver;
  private final AfterCommitSideEffectRunner afterCommit;
  private final CiInvestigationProperties properties;
  private final WorkflowRunRejectionLoopPort rejectionLoopPort;
  private final CiFixEscalationThresholdProvider ciFixEscalationThresholdProvider;
  private final WorkflowTransitionService workflowTransitionService;
  private final RunnerExecutionRecordPort recordPort;
  private final RunnerExecutionService executionService;
  private final RunnerLogCaptureService runnerLogCaptureService;
  private final WorkflowEventWritePort workflowEventWritePort;
  // Lazy: WorkflowOrchestrationService depends (transitively) on the broker; keep the reverse edge
  // lazy for ctor-order safety (the BuildStageService precedent).
  private final Supplier<WorkflowOrchestrationService> orchestrationSupplier;
  private final TransactionTemplate scanTemplate;
  // Process-local keyset cursor (last scanned workflow_runs.id). The single-threaded @Scheduled
  // trigger + the sweep advisory lock serialize sweep(), so a plain AtomicLong is sufficient.
  private final AtomicLong scanCursor = new AtomicLong(0L);

  public CiStatusPollingService(
      CiStatusPort ciStatusPort,
      ProjectRuntimeConfigResolver projectRuntimeConfigResolver,
      ProjectConnectorResolver projectConnectorResolver,
      AfterCommitSideEffectRunner afterCommit,
      CiInvestigationProperties properties,
      WorkflowRunRejectionLoopPort rejectionLoopPort,
      CiFixEscalationThresholdProvider ciFixEscalationThresholdProvider,
      WorkflowTransitionService workflowTransitionService,
      RunnerExecutionRecordPort recordPort,
      RunnerExecutionService executionService,
      RunnerLogCaptureService runnerLogCaptureService,
      WorkflowEventWritePort workflowEventWritePort,
      ObjectProvider<WorkflowOrchestrationService> orchestrationProvider,
      PlatformTransactionManager transactionManager) {
    this.ciStatusPort = Objects.requireNonNull(ciStatusPort, "ciStatusPort");
    this.projectRuntimeConfigResolver =
        Objects.requireNonNull(projectRuntimeConfigResolver, "projectRuntimeConfigResolver");
    this.projectConnectorResolver =
        Objects.requireNonNull(projectConnectorResolver, "projectConnectorResolver");
    this.afterCommit = Objects.requireNonNull(afterCommit, "afterCommit");
    this.properties = Objects.requireNonNull(properties, "properties");
    this.rejectionLoopPort = Objects.requireNonNull(rejectionLoopPort, "rejectionLoopPort");
    this.ciFixEscalationThresholdProvider =
        Objects.requireNonNull(
            ciFixEscalationThresholdProvider, "ciFixEscalationThresholdProvider");
    this.workflowTransitionService =
        Objects.requireNonNull(workflowTransitionService, "workflowTransitionService");
    this.recordPort = Objects.requireNonNull(recordPort, "recordPort");
    this.executionService = Objects.requireNonNull(executionService, "executionService");
    this.runnerLogCaptureService =
        Objects.requireNonNull(runnerLogCaptureService, "runnerLogCaptureService");
    this.workflowEventWritePort =
        Objects.requireNonNull(workflowEventWritePort, "workflowEventWritePort");
    Objects.requireNonNull(orchestrationProvider, "orchestrationProvider");
    this.orchestrationSupplier = orchestrationProvider::getIfAvailable;
    Objects.requireNonNull(transactionManager, "transactionManager");
    this.scanTemplate = new TransactionTemplate(transactionManager);
  }

  /**
   * Run one CI-investigation tick. Phase 1 takes the sweep advisory lock + reads one keyset window
   * in a short tx (lock released on commit); phase 2 reads each run's CI verdict lock-free and
   * records the outcome in its own {@code REQUIRES_NEW} transaction. Never throws for per-run
   * failures — those are swallowed and retried next tick. Returns a tally for tests /
   * observability.
   */
  public CiSweepResult sweep() {
    long startNanos = System.nanoTime();
    int batchLimit = properties.batchLimit();
    long cursor = scanCursor.get();

    // Phase 1 — lock + scan one window inside one short-lived tx; NO external I/O here. Fetch
    // batchLimit + 1 to distinguish an exactly-full batch from a truncated one without a spurious
    // WARN.
    List<CiPollRow> fetched =
        scanTemplate.execute(
            status -> {
              ciStatusPort.acquireCiSweepLock();
              return ciStatusPort.findRunsAwaitingCiStatus(cursor, batchLimit + 1);
            });
    if (fetched == null) {
      fetched = List.of();
    }
    boolean batchLimitHit = fetched.size() > batchLimit;
    List<CiPollRow> rows = batchLimitHit ? fetched.subList(0, batchLimit) : fetched;
    if (batchLimitHit) {
      log.warn(
          "ci-investigation SWEEP hit batch limit batchLimit={} — cursor advances so the remainder "
              + "is scanned next tick",
          batchLimit);
    }
    // Advance the keyset cursor: when more remain, advance past this batch; otherwise wrap to the
    // oldest so a newly-stamped early row is re-scanned each full rotation.
    long nextCursor = batchLimitHit && !rows.isEmpty() ? rows.get(rows.size() - 1).seq() : 0L;
    scanCursor.set(nextCursor);

    Tally tally = new Tally();
    tally.batchLimitHit = batchLimitHit;
    // Phase 2 — read each verdict lock-free; record the outcome in a per-run REQUIRES_NEW tx.
    for (CiPollRow row : rows) {
      processRun(row, tally);
    }

    CiSweepResult result = tally.toResult();
    log.info(
        "ci-investigation SWEEP complete scanned={} green={} red={} pending={} neutral={} "
            + "unavailable={} readFailures={} batchLimitHit={} durationMs={}",
        result.scanned(),
        result.green(),
        result.red(),
        result.pending(),
        result.neutral(),
        result.unavailable(),
        result.readFailures(),
        result.batchLimitHit(),
        (System.nanoTime() - startNanos) / 1_000_000L);
    return result;
  }

  private void processRun(CiPollRow row, Tally tally) {
    String runId = row.workflowRunPublicId();
    String correlationId = "ci-sweep:" + runId;
    MdcKeys.withKey(
        MdcKeys.CORRELATION_ID,
        correlationId,
        () -> {
          String priorRun = MdcKeys.beginScope(MdcKeys.WORKFLOW_RUN_ID, runId);
          try {
            tally.scanned++;
            CiStatus status;
            try {
              RepositoryHostAdapter adapter = resolveRepositoryHost(runId);
              RepositoryRef repoRef = resolveRepositoryRef(runId);
              status = adapter.readCheckRuns(repoRef, row.ciHeadSha());
            } catch (RuntimeException readFailure) {
              // Transient host/read failure — swallow + WARN, bump the attempt, leave pending,
              // retry
              // next tick. ONE bad run never aborts the sweep.
              tally.readFailures++;
              log.warn(
                  "ci poll read failed reason=ci_read_failed workflowRunId={} ciHeadSha={} cause={}"
                      + " message={}",
                  runId,
                  row.ciHeadSha(),
                  readFailure.getClass().getSimpleName(),
                  readFailure.getMessage());
              afterCommit.runInNewTransaction(
                  CI_POLL_LABEL,
                  runId,
                  () -> {
                    // Mirror the PENDING branch: a persistently unreadable host must still hit the
                    // poll-attempt cap and terminate to `unavailable`, else it is re-polled
                    // forever.
                    int attempts = ciStatusPort.recordCiPollAttempt(runId);
                    if (attempts > properties.maxPollAttempts()) {
                      ciStatusPort.recordCiStatus(runId, "unavailable");
                      log.warn(
                          "ci poll cap exhausted reason=ci_read_failed_attempts_exhausted "
                              + "workflowRunId={} attempts={} max={}",
                          runId,
                          attempts,
                          properties.maxPollAttempts());
                    }
                  });
              return;
            }
            dispatchOutcome(runId, row, status, correlationId, tally);
          } finally {
            MdcKeys.endScope(MdcKeys.WORKFLOW_RUN_ID, priorRun);
          }
        });
  }

  private void dispatchOutcome(
      String runId, CiPollRow row, CiStatus status, String correlationId, Tally tally) {
    switch (status.conclusion()) {
      case PENDING -> {
        tally.pending++;
        afterCommit.runInNewTransaction(
            CI_POLL_LABEL,
            runId,
            () -> {
              int attempts = ciStatusPort.recordCiPollAttempt(runId);
              if (attempts > properties.maxPollAttempts()) {
                ciStatusPort.recordCiStatus(runId, "unavailable");
                log.warn(
                    "ci poll cap exhausted reason=ci_poll_attempts_exhausted workflowRunId={} "
                        + "attempts={} max={}",
                    runId,
                    attempts,
                    properties.maxPollAttempts());
              }
            });
      }
      case SUCCESS -> {
        // Grace window (story 3h-5 review round 2 — the "green" half of the push→register race): a
        // SUCCESS verdict read before GitHub has registered every workflow's check-runs can be a
        // false green (only a passing subset registered, the rest not yet posted). Keep the run
        // PENDING (bump the attempt) until it has been polled a minimum number of times; only then
        // record success and drop it from the sweep. The poll-attempt cap still overrides so a
        // stuck
        // ref cannot poll past its budget.
        afterCommit.runInNewTransaction(
            CI_POLL_LABEL,
            runId,
            () -> {
              int attempts = ciStatusPort.recordCiPollAttempt(runId);
              if (attempts >= MIN_POLL_ATTEMPTS_BEFORE_ACCEPT
                  || attempts > properties.maxPollAttempts()) {
                // Record before bumping the tally: recordCiStatus can throw (e.g. RUN_NOT_FOUND on
                // a
                // concurrently-archived run) and roll this REQUIRES_NEW tx back, so the
                // observability
                // counter must not be incremented for a write that did not land.
                ciStatusPort.recordCiStatus(runId, "success");
                tally.green++;
              } else {
                tally.pending++;
                log.info(
                    "ci poll success within grace — keep polling workflowRunId={} attempts={} "
                        + "minBeforeAccept={}",
                    runId,
                    attempts,
                    MIN_POLL_ATTEMPTS_BEFORE_ACCEPT);
              }
            });
      }
      case NEUTRAL -> {
        // Grace window (story 3h-5 review — the zero-checks race): a NEUTRAL verdict polled in the
        // seconds between the push and GitHub registering its check-runs is indistinguishable from
        // a
        // genuinely no-CI ref. Keep the run PENDING (bump the attempt) until it has been polled a
        // minimum number of times; only then record NEUTRAL and drop it from the sweep. The
        // poll-attempt cap still overrides so a stuck ref cannot poll past its budget.
        afterCommit.runInNewTransaction(
            CI_POLL_LABEL,
            runId,
            () -> {
              int attempts = ciStatusPort.recordCiPollAttempt(runId);
              if (attempts >= MIN_POLL_ATTEMPTS_BEFORE_ACCEPT
                  || attempts > properties.maxPollAttempts()) {
                // Record before bumping the tally (see the SUCCESS branch): a rolled-back
                // recordCiStatus must not leave the neutral counter incremented.
                ciStatusPort.recordCiStatus(runId, "neutral");
                tally.neutral++;
              } else {
                tally.pending++;
                log.info(
                    "ci poll neutral within grace — keep polling workflowRunId={} attempts={} "
                        + "minBeforeAccept={}",
                    runId,
                    attempts,
                    MIN_POLL_ATTEMPTS_BEFORE_ACCEPT);
              }
            });
      }
      case UNAVAILABLE -> {
        tally.unavailable++;
        afterCommit.runInNewTransaction(
            CI_POLL_LABEL, runId, () -> ciStatusPort.recordCiStatus(runId, "unavailable"));
      }
      case FAILURE -> {
        // Grace window (story 3h-5 3rd review — the RED half of the push→register race): a FAILURE
        // read taken before GitHub has registered every workflow's check-runs can be a false red —
        // a fast check registers and fails while the build check has not been created yet (absent
        // from total_count, so the adapter's `anyPending` precedence cannot see it). Mirror the
        // SUCCESS/NEUTRAL grace: bump the poll attempt and keep the run PENDING until it has been
        // polled a minimum number of times; only then act on the red verdict (re-dispatch the fix
        // loop). The poll-attempt cap still overrides so a stuck ref cannot poll past its budget.
        // The attempt bump commits synchronously in its own swallow-guarded REQUIRES_NEW tx FIRST,
        // so the act/skip decision is settled before handleCiFailure runs its own committed
        // sub-transactions (which must not be nested inside another tx — the reserve/capture
        // split).
        boolean[] actOnRed = {false};
        afterCommit.runInNewTransaction(
            CI_POLL_LABEL,
            runId,
            () -> {
              int attempts = ciStatusPort.recordCiPollAttempt(runId);
              if (attempts >= MIN_POLL_ATTEMPTS_BEFORE_ACCEPT
                  || attempts > properties.maxPollAttempts()) {
                actOnRed[0] = true;
              } else {
                log.info(
                    "ci poll failure within grace — keep polling workflowRunId={} attempts={} "
                        + "minBeforeAccept={}",
                    runId,
                    attempts,
                    MIN_POLL_ATTEMPTS_BEFORE_ACCEPT);
              }
            });
        if (actOnRed[0]) {
          tally.red++;
          // handleCiFailure orchestrates its OWN committed sub-transactions (the reserved CI rex
          // must commit before the REQUIRES_NEW capture can read it — the BuildStageService split),
          // so it is NOT wrapped in a single ci-poll tx here.
          handleCiFailure(runId, row, status, correlationId);
        } else {
          tally.pending++;
        }
      }
    }
  }

  /**
   * Story 3h-5 (AC2/AC5/AC6, Decisions 1/5) — the bounded CI fix loop. Orchestrates its own
   * committed sub-transactions (Layer B, one per phase): the reserved CI rex must COMMIT before the
   * {@code REQUIRES_NEW} capture can read it (the BuildStageService reserve/capture split). Phase 1
   * takes the per-run advisory lock + re-reads {@code current_state} (the CAS): only a run still at
   * {@code WaitingForReview} is re-dispatched; a {@code Completed}/{@code TakenOver}/{@code
   * Failed}/already- {@code Executing} run records the verdict and skips (Decision 1). Materializes
   * the CI failure log as a FAILED CI {@code runner_executions} row (Decision 3, so {@code
   * ContextBundleService} threads it back as a {@code ci.failure} reference), then either
   * re-dispatches (loop {@code <=} cap) or flips the shared escalation marker once and parks the
   * run (loop {@code >} cap — never fails it).
   */
  void handleCiFailure(String runId, CiPollRow row, CiStatus ciStatus, String correlationId) {
    // Phase 1 (CAS) — take the per-run advisory lock, re-read current_state, and skip unless still
    // WaitingForReview (Decision 1). Committed on its own so a non-reviewable run records failure
    // and
    // stops. The transition in Phase 4 is the load-bearing guard (ILLEGAL_TRANSITION on a state
    // change); this CAS is the early-out.
    boolean[] reviewable = {false};
    afterCommit.runInNewTransaction(
        "ci-cas",
        runId,
        () -> {
          ciStatusPort.lockRunForCiInvestigation(runId);
          Optional<String> currentState = ciStatusPort.readCurrentState(runId);
          if (currentState.isPresent()
              && WorkflowState.WAITING_FOR_REVIEW.value().equals(currentState.get())) {
            reviewable[0] = true;
          } else {
            ciStatusPort.recordCiStatus(runId, "failure");
            log.warn(
                "ci red skipped reason=ci_red_but_run_not_reviewable workflowRunId={} "
                    + "currentState={}",
                runId,
                currentState.orElse("<absent>"));
          }
        });
    if (!reviewable[0]) {
      return;
    }

    // Materialize the CI failure log as a FAILED CI runner_executions row (Decision 3). Because
    // RunnerExecutionService.recordRawOutput is REQUIRES_NEW, the reserved rex must be COMMITTED
    // before the capture (the BuildStageService split: reserve in one tx, capture in another). Its
    // already-redacted raw output IS the ci.failure feedback body — never inlined past the 256 KB
    // reference-by-id cap (AC5).
    String ciRex = PublicIdPrefixes.RUNNER_EXECUTION.next();
    String priorRex = MdcKeys.beginScope(MdcKeys.RUNNER_EXECUTION_ID, ciRex);
    try {
      String composedFailureText = composeFailureText(runId, ciStatus);
      // Phase 2 — reserve the CI rex (committed) so the REQUIRES_NEW capture below can see it.
      afterCommit.runInNewTransaction(
          "ci-reserve",
          runId,
          () -> {
            int contextBundleVersion = recordPort.nextContextBundleVersion(runId, RunnerStage.CI);
            recordPort.insertPending(
                ciRex,
                runId,
                RunnerStage.CI,
                contextBundleVersion,
                new ExecutionConstraints(CI_EXECUTION_TIMEOUT, true));
          });
      // Phase 3 — capture (committed): redaction/secret-scan/store (AC5), then FAILED so
      // ContextBundleService threads it back as a ci.failure reference (AC8). Reuse
      // RUNNER_NON_ZERO_EXIT exactly as LINT does — no new FailureCategory (Decision 5). Sets
      // captureCommitted only after recordFailed succeeds, so Phase 4 can gate on a materialized
      // feedback body (story 3h-5 review round 2).
      boolean[] captureCommitted = {false};
      afterCommit.runInNewTransaction(
          "ci-capture",
          runId,
          () -> {
            CapturedLogs captured =
                runnerLogCaptureService.captureLogs(ciRex, runId, composedFailureText, "");
            executionService.recordRawOutput(ciRex, captured);
            executionService.recordFailed(ciRex, FailureCategory.RUNNER_NON_ZERO_EXIT);
            captureCommitted[0] = true;
          });

      // Phase 4 — the bounded loop (committed atomically: increment + transition + re-dispatch roll
      // back together, the rejectImplementation semantics). The committed FAILED CI rex from Phase
      // 3
      // is picked up as a ci.failure reference by the re-dispatch's context bundle. GATED on
      // Phase-3
      // success (story 3h-5 review round 2): a swallowed reserve/capture must NOT consume a
      // fix-loop
      // iteration or re-dispatch EXECUTION without the ci.failure feedback — leave
      // ci_status='pending'
      // so the next tick re-scans this run and retries the whole reserve→capture→loop sequence.
      if (captureCommitted[0]) {
        afterCommit.runInNewTransaction(
            "ci-loop", runId, () -> runCiFixLoop(runId, ciStatus, ciRex, correlationId));
      } else {
        log.warn(
            "ci fix loop skipped — CI failure capture not committed, leaving run pending for a "
                + "next-tick retry workflowRunId={} ciRunnerExecutionId={}",
            runId,
            ciRex);
      }
    } finally {
      MdcKeys.endScope(MdcKeys.RUNNER_EXECUTION_ID, priorRex);
    }
  }

  private void runCiFixLoop(String runId, CiStatus ciStatus, String ciRex, String correlationId) {
    int loop = rejectionLoopPort.incrementAndReadCiFixLoopCount(runId);
    int cap = ciFixEscalationThresholdProvider.get();
    if (loop <= cap) {
      // Bounded re-dispatch: transition WaitingForReview -> Executing, then retryImplementation
      // AFTER the transition (Trap T1 — pure re-dispatch, never a second transition). Clone
      // TechnicalApprovalService.rejectImplementation's shape with a SYSTEM actor + ci-fix key.
      Map<String, Object> details =
          Map.of(
              "ciHeadSha", nullToEmpty(ciStatus.headSha()),
              "ciFixLoopCount", loop,
              "ciFixMaxLoops", cap,
              "ciRunnerExecutionId", ciRex);
      workflowTransitionService.transition(
          runId,
          WorkflowState.EXECUTING,
          new TransitionActor("system", ActorType.SYSTEM),
          REDISPATCH_REASON,
          "ci-fix:" + runId + ":" + loop,
          details);
      // Record failure AFTER the transition — the next push re-stamps ci_status='pending'.
      ciStatusPort.recordCiStatus(runId, "failure");
      WorkflowOrchestrationService orchestration = orchestrationSupplier.get();
      if (orchestration == null) {
        // Roll the whole ci-loop tx back (increment + transition + recordCiStatus) rather than
        // strand the run in EXECUTING with no runner ever dispatched: throw so the swallowing
        // REQUIRES_NEW wrapper rolls back and the run stays WaitingForReview with
        // ci_status='pending'
        // for a clean next-tick retry. Practically unreachable — WorkflowOrchestrationService is
        // always present post-startup; the lazy ObjectProvider exists only for ctor-cycle
        // avoidance.
        throw new IllegalStateException(
            "ci fix loop cannot re-dispatch: no WorkflowOrchestrationService bean for run "
                + runId);
      }
      orchestration.retryImplementation(runId, correlationId);
      log.info(
          "ci fix loop redispatch workflowRunId={} ciFixLoopCount={} cap={} ciRunnerExecutionId={}",
          runId,
          loop,
          cap,
          ciRex);
    } else {
      // Cap exhausted — flip the shared escalation marker ONCE, park at WaitingForReview. No
      // transition, no re-dispatch (Decision 5). Emit ESCALATION_REQUIRED on the flip edge only.
      ciStatusPort.recordCiStatus(runId, "failure");
      boolean flipped;
      if (rejectionLoopPort.isEscalationMarkerSet(runId)) {
        flipped = false;
      } else {
        flipped = rejectionLoopPort.markEscalationOnce(runId) == 1;
      }
      if (flipped) {
        workflowEventWritePort.append(
            new WorkflowEventRecord(
                PublicIdPrefixes.WORKFLOW_EVENT.next(),
                runId,
                WorkflowEventType.ESCALATION_REQUIRED,
                null,
                null,
                "system",
                ActorType.SYSTEM,
                "ci fix loop threshold exceeded",
                null,
                false,
                OffsetDateTime.now(),
                Map.of("ciFixLoopCount", loop, "ciFixMaxLoops", cap)));
        log.warn(
            "ci fix loop cap exhausted — escalation marker raised workflowRunId={} "
                + "ciFixLoopCount={} cap={}",
            runId,
            loop,
            cap);
      } else {
        // Already set, or a concurrent transaction just flipped it (the other tx emitted the
        // event) — idempotency holds at the DB level.
        log.debug(
            "ci fix loop cap exhausted — escalation marker already set / concurrent-flip "
                + "workflowRunId={} ciFixLoopCount={} cap={}",
            runId,
            loop,
            cap);
      }
    }
  }

  /**
   * Compose the CI failure body from the failed checks' bounded {@code failureText}. Falls back to
   * a generic line keyed to the commit when no check carried a body (defensive — a FAILURE verdict
   * from GitHub always carries at least one failed check).
   */
  private static String composeFailureText(String runId, CiStatus ciStatus) {
    StringBuilder body = new StringBuilder();
    for (CiCheck check : ciStatus.checks()) {
      if (check.failureText() != null && !check.failureText().isBlank()) {
        if (body.length() > 0) {
          body.append("\n---\n");
        }
        body.append(check.failureText());
      }
    }
    if (body.length() == 0) {
      body.append("CI build failed for commit ").append(nullToEmpty(ciStatus.headSha()));
    }
    return boundBytes(body.toString(), MAX_COMPOSED_FAILURE_BYTES);
  }

  /**
   * Truncate {@code value} to at most {@code maxBytes} UTF-8 bytes INCLUDING the truncation suffix
   * (whole-char safe). Reserves the suffix's byte budget before trimming so the returned string
   * never exceeds {@code maxBytes}.
   */
  private static String boundBytes(String value, int maxBytes) {
    if (value.getBytes(StandardCharsets.UTF_8).length <= maxBytes) {
      return value;
    }
    int budget = Math.max(0, maxBytes - TRUNCATION_SUFFIX.getBytes(StandardCharsets.UTF_8).length);
    String truncated = value;
    while (truncated.getBytes(StandardCharsets.UTF_8).length > budget && !truncated.isEmpty()) {
      truncated = truncated.substring(0, truncated.length() - Math.max(1, truncated.length() / 16));
    }
    return truncated + TRUNCATION_SUFFIX;
  }

  private RepositoryHostAdapter resolveRepositoryHost(String runId) {
    Project project = projectRuntimeConfigResolver.resolveForRun(runId);
    RepositoryHostAdapter adapter = projectConnectorResolver.resolveRepositoryHost(project);
    if (adapter == null) {
      throw new IllegalStateException("no repository host adapter resolved for run " + runId);
    }
    return adapter;
  }

  private RepositoryRef resolveRepositoryRef(String runId) {
    return RepositoryRef.of(
        projectRuntimeConfigResolver
            .resolveRepositoryRef(runId)
            .orElseThrow(
                () -> new IllegalStateException("no repository ref resolved for run " + runId)));
  }

  private static String nullToEmpty(String value) {
    return value == null ? "" : value;
  }

  /** Mutable per-sweep tally, mirroring {@code IntegrationConflictDetectionService.Tally}. */
  private static final class Tally {
    private int scanned;
    private int green;
    private int red;
    private int pending;
    private int neutral;
    private int unavailable;
    private int readFailures;
    private boolean batchLimitHit;

    private CiSweepResult toResult() {
      return new CiSweepResult(
          scanned, green, red, pending, neutral, unavailable, readFailures, batchLimitHit);
    }
  }

  /** Immutable per-sweep result (story 3h-5 Task 6), mirroring {@code SweepResult}. */
  public record CiSweepResult(
      int scanned,
      int green,
      int red,
      int pending,
      int neutral,
      int unavailable,
      int readFailures,
      boolean batchLimitHit) {}
}
