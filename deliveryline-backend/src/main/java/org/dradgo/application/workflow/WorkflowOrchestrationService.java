package org.dradgo.application.workflow;

import java.util.Map;
import java.util.Objects;
import org.dradgo.application.artifact.ActorContext;
import org.dradgo.application.observability.MdcKeys;
import org.dradgo.application.runner.ContextBundleService;
import org.dradgo.application.runner.ExecutionSubStage;
import org.dradgo.application.runner.RunnerBroker;
import org.dradgo.application.runner.RunnerDispatchResult;
import org.dradgo.application.runner.RunnerExecutionHandle;
import org.dradgo.application.runner.RunnerProperties;
import org.dradgo.application.runner.spi.RunnerExecutionRecordPort;
import org.dradgo.application.runner.spi.RunnerExecutionSnapshot;
import org.dradgo.application.workflow.WorkflowTransitionService.TransitionActor;
import org.dradgo.application.workflow.spi.WorkflowRunReadPort;
import org.dradgo.application.workflow.spi.WorkflowRunSnapshot;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.id.PublicIdPrefixes;
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.RunnerStage;
import org.dradgo.domain.registry.WorkflowState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Story 3a-1 — the single application service that auto-advances workflow state on a spec-stage
 * runner outcome (ADR 0004 §Decision-1). It is the spec-stage analog of story 3.11's plan-stage
 * orchestration, and the sole owner of the {@code Investigating -> WaitingForSpecApproval}
 * auto-advance (AC9; enforced by an ArchUnit rule).
 *
 * <p>Two entry points fire a runner against the spec-investigation context bundle:
 *
 * <ul>
 *   <li>{@link #dispatchSpecGeneration} — called from the submit path once a run is created in
 *       {@code Inbox}. Ensures the run is {@code Investigating} (ADR 0004's direct {@code Inbox ->
 *       Investigating} trigger), then dispatches through {@link RunnerBroker}.
 *   <li>{@link #retrySpecGeneration} — called from {@code ApprovalService.rejectSpec} AFTER it has
 *       already transitioned the run {@code WaitingForSpecApproval -> Investigating}. It
 *       re-dispatches ONLY and never re-transitions (Trap T8).
 * </ul>
 *
 * <p>{@link #onSpecStageSucceeded} is the callback the broker invokes (via {@code
 * ObjectProvider}/{@code @Lazy} to break the broker↔orchestration constructor cycle, Trap T2) once
 * a spec artifact for an {@code INVESTIGATION}-stage execution becomes {@code available}.
 *
 * <p>Lives in {@code application.workflow} (alongside {@code WorkflowCommandService} / {@code
 * WorkflowTransitionService}); it consumes the runner subsystem only through the application-layer
 * {@link RunnerBroker} bean, never an adapter type.
 */
@Service
public class WorkflowOrchestrationService {

  private static final Logger log = LoggerFactory.getLogger(WorkflowOrchestrationService.class);

  private static final java.util.List<org.dradgo.domain.registry.RunnerExecutionStatus>
      ACTIVE_STATUSES =
          java.util.List.of(
              org.dradgo.domain.registry.RunnerExecutionStatus.PENDING,
              org.dradgo.domain.registry.RunnerExecutionStatus.RUNNING);

  /**
   * Review finding P3 — states in which the spec-ready transition has already effectively been
   * applied: the run reached {@code WaitingForSpecApproval} or progressed normally beyond it. A
   * late/duplicate spec outcome landing in one of these is a benign idempotent replay; any OTHER
   * state behind an {@code ILLEGAL_TRANSITION} is treated as a probable anomaly (best-effort — the
   * current snapshot cannot prove spec-ready was never applied; see {@link
   * #handleSpecReadyIllegalTransition}).
   */
  private static final java.util.Set<WorkflowState> SPEC_READY_OR_BEYOND =
      java.util.EnumSet.of(
          WorkflowState.WAITING_FOR_SPEC_APPROVAL,
          WorkflowState.EXECUTING,
          WorkflowState.WAITING_FOR_REVIEW,
          WorkflowState.COMPLETED);

  /**
   * Story 3.11 (AC3) / story 3.12 (AC5, Task 4) — the EXECUTION-stage twin of {@link
   * #SPEC_READY_OR_BEYOND}, SHARED by the plan-ready ({@link #onPlanStageSucceeded}) and pr-output-
   * ready ({@link #onPrOutputStageSucceeded}) transitions: both fire {@code Executing ->
   * WaitingForReview}, so the states in which that transition has already effectively been applied
   * are identical — the run reached {@code WaitingForReview} or progressed normally beyond it to
   * {@code Completed}. A late/duplicate outcome landing here is a benign idempotent replay; any
   * OTHER state behind an {@code ILLEGAL_TRANSITION} is treated as a probable anomaly (best-effort,
   * mirror of the spec path; see {@link #handlePlanReadyIllegalTransition} / {@link
   * #handlePrOutputReadyIllegalTransition}).
   *
   * <p><b>Story 3.12 (Task 4) — the 3.11-deferred "{@code EXECUTING} omission" item, resolved by
   * analysis.</b> With both plan and pr-output live a run can legitimately return to {@code
   * EXECUTING} (the pr-output dispatch follows the plan-ready transition). But {@code Executing ->
   * WaitingForReview} is a LEGAL transition, so an outcome arriving while the run is in {@code
   * EXECUTING} succeeds outright and never reaches the {@code ILLEGAL_TRANSITION} handler where
   * this set is consulted. Adding {@code EXECUTING} would therefore be inert (dead in the only code
   * path that reads the set), so it is deliberately omitted — the forward set stays the minimal
   * {WaitingForReview, Completed}.
   */
  private static final java.util.Set<WorkflowState> EXECUTION_READY_OR_BEYOND =
      java.util.EnumSet.of(WorkflowState.WAITING_FOR_REVIEW, WorkflowState.COMPLETED);

  private final RunnerBroker runnerBroker;
  private final WorkflowTransitionService workflowTransitionService;
  private final WorkflowRunReadPort workflowRunReadPort;
  private final RunnerExecutionRecordPort runnerExecutionRecordPort;
  private final RunnerProperties runnerProperties;
  private final ContextBundleService contextBundleService;

  public WorkflowOrchestrationService(
      RunnerBroker runnerBroker,
      WorkflowTransitionService workflowTransitionService,
      WorkflowRunReadPort workflowRunReadPort,
      RunnerExecutionRecordPort runnerExecutionRecordPort,
      RunnerProperties runnerProperties,
      ContextBundleService contextBundleService) {
    this.runnerBroker = Objects.requireNonNull(runnerBroker, "runnerBroker");
    this.workflowTransitionService =
        Objects.requireNonNull(workflowTransitionService, "workflowTransitionService");
    this.workflowRunReadPort = Objects.requireNonNull(workflowRunReadPort, "workflowRunReadPort");
    this.runnerExecutionRecordPort =
        Objects.requireNonNull(runnerExecutionRecordPort, "runnerExecutionRecordPort");
    this.runnerProperties = Objects.requireNonNull(runnerProperties, "runnerProperties");
    this.contextBundleService =
        Objects.requireNonNull(contextBundleService, "contextBundleService");
  }

  /**
   * The auto-dispatch master switch ({@code deliveryline.runner.spec-stage.auto-dispatch}). When
   * {@code false} (the shared test profile, mirroring {@code scheduling.enabled: false}) the submit
   * and reject triggers are no-ops; production enables it. The {@link #onSpecStageSucceeded}
   * callback is NOT gated — it only ever fires for a run a dispatch already started.
   */
  public boolean autoDispatchEnabled() {
    return runnerProperties.specStage().autoDispatch();
  }

  /** Convenience overload — no originating correlationId threaded (AC7 best-effort). */
  public RunnerDispatchResult dispatchSpecGeneration(String workflowRunId) {
    return dispatchSpecGeneration(workflowRunId, null);
  }

  /**
   * AC1 — ensure the run is {@code Investigating}, build the deterministic idempotency key, and
   * dispatch the spec runner. Idempotent: the broker replays a same-key/in-flight dispatch (AC6).
   *
   * @param correlationId originating submit/CLI correlationId (story 1.19 / AC7), threaded into the
   *     dispatch actor so the runner-lifecycle + artifact events carry it; {@code null} is allowed.
   */
  public RunnerDispatchResult dispatchSpecGeneration(String workflowRunId, String correlationId) {
    PublicIdPrefixes.require(workflowRunId, PublicIdPrefixes.WORKFLOW_RUN);
    if (!autoDispatchEnabled()) {
      log.debug(
          "dispatchSpecGeneration skipped (spec-stage.auto-dispatch=false) workflowRunId={}",
          workflowRunId);
      return null;
    }
    String priorRunMdc = MdcKeys.beginScope(MdcKeys.WORKFLOW_RUN_ID, workflowRunId);
    try {
      WorkflowRunSnapshot snapshot = requireRun(workflowRunId);
      log.info(
          "dispatchSpecGeneration entry workflowRunId={} currentState={} specRejectionLoopCount={}",
          workflowRunId,
          snapshot.currentState().value(),
          snapshot.specRejectionLoopCount());

      // AC6 / OQ-4 in-flight guard: a spec execution already pending/running for this run is a
      // no-op returning the existing handle (the broker's key+fingerprint idempotency does NOT
      // dedupe a same-key re-dispatch once the context-bundle version has advanced).
      RunnerDispatchResult inFlight = inFlightSpecDispatch(workflowRunId);
      if (inFlight != null) {
        log.warn(
            "dispatchSpecGeneration in-flight no-op workflowRunId={} runnerExecutionId={}",
            workflowRunId,
            inFlight.handle().runnerExecutionId());
        return inFlight;
      }

      ensureInvestigating(workflowRunId, snapshot, correlationId);

      String idempotencyKey = specDispatchKey(workflowRunId, snapshot.specRejectionLoopCount());
      RunnerDispatchResult result =
          runnerBroker.dispatch(
              workflowRunId, RunnerStage.INVESTIGATION, idempotencyKey, systemActor(correlationId));
      logDispatchOutcome("dispatchSpecGeneration", workflowRunId, idempotencyKey, result);
      return result;
    } finally {
      MdcKeys.endScope(MdcKeys.WORKFLOW_RUN_ID, priorRunMdc);
    }
  }

  /** Convenience overload — no originating correlationId threaded. */
  public RunnerDispatchResult retrySpecGeneration(String workflowRunId) {
    return retrySpecGeneration(workflowRunId, null);
  }

  /**
   * AC5 — re-dispatch the spec runner for a run that is ALREADY {@code Investigating} (the caller —
   * {@code ApprovalService.rejectSpec} or recovery retry — performed any required transition). This
   * method re-dispatches ONLY and MUST NOT re-transition (Trap T8): an {@code INVESTIGATING ->
   * INVESTIGATING} transition would be illegal / a duplicate event. The bumped {@code
   * specRejectionLoopCount} makes the idempotency key distinct from the prior attempt so the broker
   * mints a fresh {@code runnerExecutionId} + context-bundle version.
   */
  public RunnerDispatchResult retrySpecGeneration(String workflowRunId, String correlationId) {
    PublicIdPrefixes.require(workflowRunId, PublicIdPrefixes.WORKFLOW_RUN);
    if (!autoDispatchEnabled()) {
      log.debug(
          "retrySpecGeneration skipped (spec-stage.auto-dispatch=false) workflowRunId={}",
          workflowRunId);
      return null;
    }
    String priorRunMdc = MdcKeys.beginScope(MdcKeys.WORKFLOW_RUN_ID, workflowRunId);
    try {
      WorkflowRunSnapshot snapshot = requireRun(workflowRunId);
      log.info(
          "retrySpecGeneration entry workflowRunId={} currentState={} specRejectionLoopCount={}",
          workflowRunId,
          snapshot.currentState().value(),
          snapshot.specRejectionLoopCount());

      // AC6 / OQ-4: never double-dispatch while a prior spec execution is still in flight.
      RunnerDispatchResult inFlight = inFlightSpecDispatch(workflowRunId);
      if (inFlight != null) {
        log.warn(
            "retrySpecGeneration in-flight no-op workflowRunId={} runnerExecutionId={}",
            workflowRunId,
            inFlight.handle().runnerExecutionId());
        return inFlight;
      }

      String idempotencyKey = specDispatchKey(workflowRunId, snapshot.specRejectionLoopCount());
      RunnerDispatchResult result =
          runnerBroker.dispatch(
              workflowRunId, RunnerStage.INVESTIGATION, idempotencyKey, systemActor(correlationId));
      logDispatchOutcome("retrySpecGeneration", workflowRunId, idempotencyKey, result);
      return result;
    } finally {
      MdcKeys.endScope(MdcKeys.WORKFLOW_RUN_ID, priorRunMdc);
    }
  }

  /**
   * AC2/AC3 — the central new behavior. Invoked by {@code RunnerBroker.handleSuccess} once a spec
   * artifact for an {@code INVESTIGATION}-stage execution has become {@code available}:
   * auto-advance the run {@code Investigating -> WaitingForSpecApproval}. Owning this here (not
   * inline in the broker) keeps the runner-outcome auto-advance in a single place (AC9).
   *
   * <p>Idempotent: a duplicate invocation (e.g. a replayed result) whose run already reached {@code
   * WaitingForSpecApproval} surfaces {@code ILLEGAL_TRANSITION}, which is swallowed so the
   * spec-ready transition fires at most once. An {@code ILLEGAL_TRANSITION} from any other current
   * state is logged at WARN as a probable anomaly (best-effort classification — review finding P3);
   * see {@link #handleSpecReadyIllegalTransition}.
   */
  public void onSpecStageSucceeded(
      String workflowRunId, String runnerExecutionId, String correlationId) {
    PublicIdPrefixes.require(workflowRunId, PublicIdPrefixes.WORKFLOW_RUN);
    PublicIdPrefixes.require(runnerExecutionId, PublicIdPrefixes.RUNNER_EXECUTION);
    String priorRunMdc = MdcKeys.beginScope(MdcKeys.WORKFLOW_RUN_ID, workflowRunId);
    String priorRexMdc = MdcKeys.beginScope(MdcKeys.RUNNER_EXECUTION_ID, runnerExecutionId);
    try {
      String idempotencyKey = "spec-ready:" + runnerExecutionId;
      try {
        workflowTransitionService.transition(
            workflowRunId,
            WorkflowState.WAITING_FOR_SPEC_APPROVAL,
            systemTransitionActor(),
            "spec_ready",
            idempotencyKey,
            Map.of("runnerExecutionId", runnerExecutionId));
        log.info(
            "onSpecStageSucceeded transitioned workflowRunId={} runnerExecutionId={} "
                + "from=Investigating to=WaitingForSpecApproval reason=spec_ready",
            workflowRunId,
            runnerExecutionId);
      } catch (DomainException error) {
        if (error.errorCode() == DomainErrorCode.ILLEGAL_TRANSITION) {
          handleSpecReadyIllegalTransition(workflowRunId, runnerExecutionId, error);
          return;
        }
        throw error;
      }
    } finally {
      MdcKeys.endScope(MdcKeys.RUNNER_EXECUTION_ID, priorRexMdc);
      MdcKeys.endScope(MdcKeys.WORKFLOW_RUN_ID, priorRunMdc);
    }
  }

  /**
   * Review finding P3 — disambiguate the two causes of an {@code ILLEGAL_TRANSITION} on the
   * spec-ready transition, which the prior blanket WARN-swallow conflated:
   *
   * <ul>
   *   <li><b>Benign replay</b> — the run already reached {@code WaitingForSpecApproval} (or
   *       progressed normally beyond it: {@code Executing} / {@code WaitingForReview} / {@code
   *       Completed}). The spec-ready transition is already applied; logged at INFO and swallowed.
   *   <li><b>Probable anomaly</b> — the run is in some OTHER state (taken-over / failed /
   *       reconciled / paused / still-investigating, or vanished) when the spec outcome arrives, so
   *       it cannot legally advance to {@code WaitingForSpecApproval}. Logged at WARN so it
   *       surfaces in observability without raising a hard error.
   * </ul>
   *
   * <p><b>Best-effort classification (review finding P3, resolved Option 1).</b> The branch is
   * chosen from the run's CURRENT state snapshot only, which is NOT authoritative about whether
   * spec-ready was ever applied: a run that genuinely reached {@code WaitingForSpecApproval} and
   * then legitimately moved on — rejected back to {@code Investigating} via the reject&rarr;retry
   * loop, or later taken-over / reconciled / paused / failed — will, on a duplicate/late result
   * harvest, fall into the WARN branch even though the replay is benign. WARN (not ERROR) reflects
   * that the signal is advisory: runtime behavior is identical in both branches (swallow + return).
   *
   * <p>Either way the exception is swallowed (never rethrown): the runner genuinely succeeded, the
   * artifact is ingested, and the execution is already recorded {@code COMPLETED} in the SAME
   * poller transaction ({@code RunnerBroker.onResult}); rethrowing would roll that committed
   * completion back and the poller would re-harvest the result forever for a permanently-diverged
   * run. The diagnostic re-read below is likewise guarded so a read failure can never unwind that
   * committed completion.
   */
  private void handleSpecReadyIllegalTransition(
      String workflowRunId, String runnerExecutionId, DomainException error) {
    WorkflowState current;
    try {
      current =
          workflowRunReadPort
              .findByPublicId(workflowRunId)
              .map(WorkflowRunSnapshot::currentState)
              .orElse(null);
    } catch (RuntimeException readError) {
      // The re-read is purely diagnostic — it only selects the log level. It must never propagate:
      // this runs inside the poller per-item transaction that already committed the runner
      // completion, so letting it escape would roll that back and cause infinite re-harvest.
      log.warn(
          "onSpecStageSucceeded could not re-read run state to classify ILLEGAL_TRANSITION "
              + "workflowRunId={} runnerExecutionId={} reason={} readError={}",
          workflowRunId,
          runnerExecutionId,
          error.getMessage(),
          readError.toString());
      return;
    }
    if (current != null && SPEC_READY_OR_BEYOND.contains(current)) {
      log.info(
          "onSpecStageSucceeded idempotent replay (spec-ready already applied) workflowRunId={} "
              + "runnerExecutionId={} currentState={}",
          workflowRunId,
          runnerExecutionId,
          current.value());
      return;
    }
    log.warn(
        "onSpecStageSucceeded probable anomaly: spec outcome for a run in unexpected state — not "
            + "advancing to WaitingForSpecApproval (best-effort classification from current state) "
            + "workflowRunId={} runnerExecutionId={} currentState={} reason={}",
        workflowRunId,
        runnerExecutionId,
        current == null ? "<not_found>" : current.value(),
        error.getMessage());
  }

  // ===============================================================================================
  // Story 3.11 — plan-stage (EXECUTION) orchestration: the spec-stage methods' twins.
  // ===============================================================================================

  /**
   * Story 3.11 (AC10) — plan-stage auto-dispatch master switch ({@code
   * deliveryline.runner.plan-stage.auto-dispatch}). When {@code false} (the shared test profile)
   * the {@code approveSpec -> dispatchPlanGeneration} trigger is a no-op so the fast tier stays
   * deterministic; production enables it. The {@link #onPlanStageSucceeded} callback is NOT gated —
   * it only ever fires for a run a dispatch already started.
   */
  public boolean planAutoDispatchEnabled() {
    return runnerProperties.planAutoDispatchEnabled();
  }

  /** Convenience overload — no originating correlationId threaded (AC7 best-effort). */
  public RunnerDispatchResult dispatchPlanGeneration(String workflowRunId) {
    return dispatchPlanGeneration(workflowRunId, null);
  }

  /**
   * AC1 — dispatch the implementation-plan runner for a run that the spec-approval already
   * transitioned to {@code Executing}. Unlike {@link #dispatchSpecGeneration} this NEVER
   * transitions (Trap T1): the EXECUTION dispatch presupposes {@code Executing}, and an {@code
   * Executing -> Executing} hop would be illegal / a duplicate event. The broker (story 3.10)
   * composes the implementation-plan bundle, prepares the repo workspace, derives the sub-stage,
   * and dispatches. Idempotent: an in-flight EXECUTION execution is a no-op (AC6); the broker
   * replays a same-key dispatch.
   *
   * @param correlationId originating approveSpec command correlationId (story 1.19 / AC7), threaded
   *     into the dispatch actor so the runner-lifecycle + artifact events carry it; {@code null} is
   *     allowed.
   */
  public RunnerDispatchResult dispatchPlanGeneration(String workflowRunId, String correlationId) {
    return dispatchExecutionInternal(
        "dispatchPlanGeneration",
        workflowRunId,
        correlationId,
        planAutoDispatchEnabled(),
        "plan-stage.auto-dispatch",
        "plan-dispatch",
        ExecutionSubStage.IMPLEMENTATION_PLAN,
        "implementationPlan");
  }

  /** Convenience overload — no originating correlationId threaded. */
  public RunnerDispatchResult retryPlanGeneration(String workflowRunId) {
    return retryPlanGeneration(workflowRunId, null);
  }

  /**
   * AC5 — re-dispatch the implementation-plan runner for a run that is ALREADY {@code Executing}
   * (the caller — the recovery/retry baseline, story 1.18 / OQ-5 — performed any required
   * transition). Like {@link #retrySpecGeneration} it re-dispatches ONLY and never re-transitions
   * (Trap T1/T8). The idempotency-key discriminator is derived from durable run state (the next
   * EXECUTION context-bundle version, Decision D2) so a retry mints a fresh {@code
   * runnerExecutionId} + bundle version while a duplicate in-flight call replays.
   */
  public RunnerDispatchResult retryPlanGeneration(String workflowRunId, String correlationId) {
    return dispatchExecutionInternal(
        "retryPlanGeneration",
        workflowRunId,
        correlationId,
        planAutoDispatchEnabled(),
        "plan-stage.auto-dispatch",
        "plan-dispatch",
        ExecutionSubStage.IMPLEMENTATION_PLAN,
        "implementationPlan");
  }

  // ===============================================================================================
  // Story 3.12 — pr-output (EXECUTION / PR_OUTPUT sub-stage) orchestration: the plan methods'
  // twins.
  // ===============================================================================================

  /**
   * Story 3.12 (AC1 / Task 5) — pr-output auto-dispatch master switch ({@code
   * deliveryline.runner.implementation-stage.auto-dispatch}). When {@code false} (the shared test
   * profile) the {@code dispatchImplementation} / {@code retryImplementation} entry points are
   * no-ops so the fast tier stays deterministic; production enables it. The {@link
   * #onPrOutputStageSucceeded} callback is NOT gated — it only ever fires for a run a dispatch
   * already started.
   */
  public boolean implementationAutoDispatchEnabled() {
    return runnerProperties.implementationAutoDispatchEnabled();
  }

  /** Convenience overload — no originating correlationId threaded (AC7 best-effort). */
  public RunnerDispatchResult dispatchImplementation(String workflowRunId) {
    return dispatchImplementation(workflowRunId, null);
  }

  /**
   * Story 3.12 (AC1) — the pr-output twin of {@link #dispatchPlanGeneration}: dispatch the
   * pr-output runner for a run that is ALREADY {@code Executing} with an approved
   * implementation-plan (so {@code ContextBundleService.deriveExecutionSubStage} yields {@link
   * ExecutionSubStage#PR_OUTPUT} and the broker composes the pr-output bundle). NEVER transitions
   * (Trap T1): the run is already {@code Executing} — the live plan-approval caller (story 3.20's
   * {@code acceptImplementation}) will keep it there; the directly-driven {@code
   * PrOutputOrchestrationIT} seeds it. The broker (story 3.10) prepares the repo workspace
   * (idempotent reuse, story 3.9), derives the sub-stage, composes the pr-output bundle, and
   * dispatches the runner kind resolved for the implementation stage. AC1's separate {@code
   * RepositoryWorkspaceService}/{@code ContextBundleService} calls are ALREADY composed inside the
   * single {@code RunnerBroker.dispatch(EXECUTION)} call, so this method calls the broker ONCE and
   * never those services directly (preserving the ArchUnit boundary). Idempotent: an in-flight
   * pr-output execution is a no-op (AC6, sub-stage-aware guard).
   *
   * @param correlationId originating command correlationId (story 1.19 / AC10), threaded into the
   *     dispatch actor so the runner-lifecycle + artifact events carry it; {@code null} is allowed.
   */
  public RunnerDispatchResult dispatchImplementation(String workflowRunId, String correlationId) {
    return dispatchExecutionInternal(
        "dispatchImplementation",
        workflowRunId,
        correlationId,
        implementationAutoDispatchEnabled(),
        "implementation-stage.auto-dispatch",
        "pr-output-dispatch",
        ExecutionSubStage.PR_OUTPUT,
        "prOutput");
  }

  /** Convenience overload — no originating correlationId threaded. */
  public RunnerDispatchResult retryImplementation(String workflowRunId) {
    return retryImplementation(workflowRunId, null);
  }

  /**
   * Story 3.12 (AC8) — re-dispatch the pr-output runner for a run that is ALREADY {@code Executing}
   * (the caller — the recovery/retry baseline, story 1.18 / OQ-5 — performed any required
   * transition). Re-dispatches ONLY and never re-transitions (Trap T1). The idempotency-key
   * discriminator is the next EXECUTION context-bundle version (Decision D2) so a retry mints a
   * fresh {@code runnerExecutionId} + bundle version while a duplicate in-flight call replays. Adds
   * NOTHING from the Epic-4 deeper-recovery set (AC12, scope-protection ArchUnit rule).
   */
  public RunnerDispatchResult retryImplementation(String workflowRunId, String correlationId) {
    return dispatchExecutionInternal(
        "retryImplementation",
        workflowRunId,
        correlationId,
        implementationAutoDispatchEnabled(),
        "implementation-stage.auto-dispatch",
        "pr-output-dispatch",
        ExecutionSubStage.PR_OUTPUT,
        "prOutput");
  }

  /**
   * Shared body for the EXECUTION-stage dispatch entry points — {@link #dispatchPlanGeneration} /
   * {@link #retryPlanGeneration} (sub-stage {@code IMPLEMENTATION_PLAN}) and {@link
   * #dispatchImplementation} / {@link #retryImplementation} (sub-stage {@code PR_OUTPUT}). All four
   * are pure re-dispatch (no transition, Trap T1), differing only by their auto-dispatch gate, key
   * prefix, expected sub-stage (for the sub-stage-aware in-flight guard), and log labels.
   *
   * <p>The deterministic key is {@code <keyPrefix>:<runId>:<nextExecutionBundleVersion>} (Decision
   * D2): plan and pr-output SHARE the per-{@code (run, EXECUTION)} bundle-version counter, so their
   * keys differ by prefix AND version (the plan dispatch consumes version N, the following
   * pr-output dispatch consumes N+1) — no collision.
   */
  private RunnerDispatchResult dispatchExecutionInternal(
      String op,
      String workflowRunId,
      String correlationId,
      boolean gateEnabled,
      String gateName,
      String keyPrefix,
      ExecutionSubStage expectedSubStage,
      String subStageLabel) {
    PublicIdPrefixes.require(workflowRunId, PublicIdPrefixes.WORKFLOW_RUN);
    if (!gateEnabled) {
      log.debug("{} skipped ({}=false) workflowRunId={}", op, gateName, workflowRunId);
      return null;
    }
    String priorRunMdc = MdcKeys.beginScope(MdcKeys.WORKFLOW_RUN_ID, workflowRunId);
    try {
      WorkflowRunSnapshot snapshot = requireRun(workflowRunId);
      log.info(
          "{} entry workflowRunId={} currentState={} stage=EXECUTION subStage={}",
          op,
          workflowRunId,
          snapshot.currentState().value(),
          subStageLabel);

      // AC6 in-flight guard (Task 2 — sub-stage-aware): an EXECUTION execution already
      // pending/running for this run AND in the SAME sub-stage is a no-op returning the existing
      // handle. With both plan and pr-output dispatch live the guard must distinguish the
      // sub-stages or one would silently no-op against the other's in-flight execution.
      RunnerDispatchResult inFlight = inFlightExecutionDispatch(workflowRunId, expectedSubStage);
      if (inFlight != null) {
        log.warn(
            "{} in-flight no-op workflowRunId={} runnerExecutionId={} subStage={}",
            op,
            workflowRunId,
            inFlight.handle().runnerExecutionId(),
            subStageLabel);
        return inFlight;
      }

      // AC1 (Trap T1): do NOT transition — the run is already Executing (approveSpec/approve-impl/
      // recovery did it). An Executing -> Executing hop would be an illegal / duplicate transition.
      String idempotencyKey =
          executionDispatchKey(
              keyPrefix,
              workflowRunId,
              runnerExecutionRecordPort.nextContextBundleVersion(
                  workflowRunId, RunnerStage.EXECUTION));
      RunnerDispatchResult result =
          runnerBroker.dispatch(
              workflowRunId, RunnerStage.EXECUTION, idempotencyKey, systemActor(correlationId));
      logDispatchOutcome(op, workflowRunId, idempotencyKey, result);
      return result;
    } finally {
      MdcKeys.endScope(MdcKeys.WORKFLOW_RUN_ID, priorRunMdc);
    }
  }

  /**
   * AC2/AC3 — the central new behavior. Invoked by {@code RunnerBroker.onResult} once the
   * implementation-plan artifact for an {@code EXECUTION}-stage (plan sub-stage) execution has been
   * ingested + the execution is {@code COMPLETED}: auto-advance the run {@code Executing ->
   * WaitingForReview}. Owning this here (not inline in the broker) keeps the runner-outcome
   * auto-advance in a single place (AC9).
   *
   * <p>Decision D1: the transition fires on successful artifact INGEST, NOT on a true {@code
   * available} artifact — {@code ArtifactOperationService.markAvailable} has no production caller
   * system-wide ([[markavailable-has-no-production-caller]]); wiring it (checksum/storageRef
   * plumbing) is explicitly out of scope. The implementation-plan artifact stays {@code pending};
   * the auto-advance fires anyway, a faithful mirror of {@link #onSpecStageSucceeded}.
   *
   * <p>Idempotent: a duplicate/late result whose run already reached (or progressed beyond) {@code
   * WaitingForReview} surfaces {@code ILLEGAL_TRANSITION}, swallowed via {@link
   * #handlePlanReadyIllegalTransition}; never rethrown (the call site shares the poller's per-item
   * transaction with {@code recordCompleted}).
   */
  public void onPlanStageSucceeded(
      String workflowRunId, String runnerExecutionId, String correlationId) {
    PublicIdPrefixes.require(workflowRunId, PublicIdPrefixes.WORKFLOW_RUN);
    PublicIdPrefixes.require(runnerExecutionId, PublicIdPrefixes.RUNNER_EXECUTION);
    String priorRunMdc = MdcKeys.beginScope(MdcKeys.WORKFLOW_RUN_ID, workflowRunId);
    String priorRexMdc = MdcKeys.beginScope(MdcKeys.RUNNER_EXECUTION_ID, runnerExecutionId);
    try {
      String idempotencyKey = "plan-ready:" + runnerExecutionId;
      try {
        workflowTransitionService.transition(
            workflowRunId,
            WorkflowState.WAITING_FOR_REVIEW,
            systemTransitionActor(),
            "implementation_plan_ready",
            idempotencyKey,
            Map.of("runnerExecutionId", runnerExecutionId));
        log.info(
            "onPlanStageSucceeded transitioned workflowRunId={} runnerExecutionId={} "
                + "from=Executing to=WaitingForReview reason=implementation_plan_ready",
            workflowRunId,
            runnerExecutionId);
      } catch (DomainException error) {
        if (error.errorCode() == DomainErrorCode.ILLEGAL_TRANSITION) {
          handlePlanReadyIllegalTransition(workflowRunId, runnerExecutionId, error);
          return;
        }
        throw error;
      }
    } finally {
      MdcKeys.endScope(MdcKeys.RUNNER_EXECUTION_ID, priorRexMdc);
      MdcKeys.endScope(MdcKeys.WORKFLOW_RUN_ID, priorRunMdc);
    }
  }

  /**
   * Story 3.11 (AC3) — the plan-stage twin of {@link #handleSpecReadyIllegalTransition}.
   * Disambiguate the two causes of an {@code ILLEGAL_TRANSITION} on the plan-ready transition:
   *
   * <ul>
   *   <li><b>Benign replay</b> — the run already reached {@code WaitingForReview} (or progressed to
   *       {@code Completed}). The plan-ready transition is already applied; logged at INFO and
   *       swallowed.
   *   <li><b>Probable anomaly</b> — the run is in some OTHER state (taken-over / failed /
   *       reconciled / paused / still-executing, or vanished). Logged at WARN so it surfaces in
   *       observability without raising a hard error.
   * </ul>
   *
   * <p>Best-effort classification (mirror of the spec path, resolved Option 1): the branch is
   * chosen from the run's CURRENT state snapshot only, which is NOT authoritative about whether
   * plan-ready was ever applied. Either way the exception is swallowed (never rethrown): the runner
   * genuinely succeeded, the artifact is ingested, and the execution is already recorded {@code
   * COMPLETED} in the SAME poller transaction ({@code RunnerBroker.onResult}); rethrowing would
   * roll that committed completion back and the poller would re-harvest forever. The diagnostic
   * re-read is likewise guarded so a read failure can never unwind that committed completion.
   */
  private void handlePlanReadyIllegalTransition(
      String workflowRunId, String runnerExecutionId, DomainException error) {
    WorkflowState current;
    try {
      current =
          workflowRunReadPort
              .findByPublicId(workflowRunId)
              .map(WorkflowRunSnapshot::currentState)
              .orElse(null);
    } catch (RuntimeException readError) {
      log.warn(
          "onPlanStageSucceeded could not re-read run state to classify ILLEGAL_TRANSITION "
              + "workflowRunId={} runnerExecutionId={} reason={} readError={}",
          workflowRunId,
          runnerExecutionId,
          error.getMessage(),
          readError.toString());
      return;
    }
    if (current != null && EXECUTION_READY_OR_BEYOND.contains(current)) {
      log.info(
          "onPlanStageSucceeded idempotent replay (plan-ready already applied) workflowRunId={} "
              + "runnerExecutionId={} currentState={}",
          workflowRunId,
          runnerExecutionId,
          current.value());
      return;
    }
    log.warn(
        "onPlanStageSucceeded probable anomaly: plan outcome for a run in unexpected state — not "
            + "advancing to WaitingForReview (best-effort classification from current state) "
            + "workflowRunId={} runnerExecutionId={} currentState={} reason={}",
        workflowRunId,
        runnerExecutionId,
        current == null ? "<not_found>" : current.value(),
        error.getMessage());
  }

  /**
   * Story 3.12 (AC5) — the central new behavior, the pr-output twin of {@link
   * #onPlanStageSucceeded}. Invoked by {@code RunnerBroker.onResult} once the pr-output artifact
   * for an {@code EXECUTION}-stage ({@code PR_OUTPUT} sub-stage) execution has been ingested +
   * enriched + the execution is {@code COMPLETED} (and any {@code captureAndPush} push/PR step
   * succeeded — anchored AFTER it in the broker so a push failure routes to {@code Failed} instead
   * of advancing): auto-advance the run {@code Executing -> WaitingForReview} with reason {@code
   * pr_output_ready}. Owning this here (not inline in the broker) keeps the runner-outcome
   * auto-advance in a single place (AC—mirror 3.11 AC9).
   *
   * <p>Decision D1: the transition fires on successful artifact INGEST, NOT on a true {@code
   * available} artifact — {@code ArtifactOperationService.markAvailable} has no production caller
   * system-wide ([[markavailable-has-no-production-caller]]); the pr-output artifact stays {@code
   * pending} and the auto-advance fires anyway, a faithful mirror of {@link #onPlanStageSucceeded}.
   *
   * <p>Idempotent: a duplicate/late result whose run already reached (or progressed beyond) {@code
   * WaitingForReview} surfaces {@code ILLEGAL_TRANSITION}, swallowed via {@link
   * #handlePrOutputReadyIllegalTransition}; never rethrown (the call site shares the poller's
   * per-item transaction with {@code recordCompleted}).
   */
  public void onPrOutputStageSucceeded(
      String workflowRunId, String runnerExecutionId, String correlationId) {
    PublicIdPrefixes.require(workflowRunId, PublicIdPrefixes.WORKFLOW_RUN);
    PublicIdPrefixes.require(runnerExecutionId, PublicIdPrefixes.RUNNER_EXECUTION);
    String priorRunMdc = MdcKeys.beginScope(MdcKeys.WORKFLOW_RUN_ID, workflowRunId);
    String priorRexMdc = MdcKeys.beginScope(MdcKeys.RUNNER_EXECUTION_ID, runnerExecutionId);
    try {
      String idempotencyKey = "pr-output-ready:" + runnerExecutionId;
      try {
        workflowTransitionService.transition(
            workflowRunId,
            WorkflowState.WAITING_FOR_REVIEW,
            systemTransitionActor(),
            "pr_output_ready",
            idempotencyKey,
            Map.of("runnerExecutionId", runnerExecutionId));
        log.info(
            "onPrOutputStageSucceeded transitioned workflowRunId={} runnerExecutionId={} "
                + "from=Executing to=WaitingForReview reason=pr_output_ready",
            workflowRunId,
            runnerExecutionId);
      } catch (DomainException error) {
        if (error.errorCode() == DomainErrorCode.ILLEGAL_TRANSITION) {
          handlePrOutputReadyIllegalTransition(workflowRunId, runnerExecutionId, error);
          return;
        }
        throw error;
      }
    } finally {
      MdcKeys.endScope(MdcKeys.RUNNER_EXECUTION_ID, priorRexMdc);
      MdcKeys.endScope(MdcKeys.WORKFLOW_RUN_ID, priorRunMdc);
    }
  }

  /**
   * Story 3.12 (AC5) — the pr-output twin of {@link #handlePlanReadyIllegalTransition}.
   * Disambiguate the two causes of an {@code ILLEGAL_TRANSITION} on the pr-output-ready transition:
   *
   * <ul>
   *   <li><b>Benign replay</b> — the run already reached {@code WaitingForReview} (or progressed to
   *       {@code Completed}). The transition is already applied; logged at INFO and swallowed.
   *   <li><b>Probable anomaly</b> — the run is in some OTHER state (taken-over / failed /
   *       reconciled / paused / still-executing, or vanished). Logged at WARN so it surfaces in
   *       observability without raising a hard error.
   * </ul>
   *
   * <p>Best-effort classification (mirror of the plan/spec paths): the branch is chosen from the
   * run's CURRENT state snapshot only, which is NOT authoritative about whether pr-output-ready was
   * ever applied. Either way the exception is swallowed (never rethrown): the runner genuinely
   * succeeded, the artifact is ingested, and the execution is already recorded {@code COMPLETED} in
   * the SAME poller transaction ({@code RunnerBroker.onResult}); rethrowing would roll that
   * committed completion back and the poller would re-harvest forever. The diagnostic re-read is
   * likewise guarded so a read failure can never unwind that committed completion.
   */
  private void handlePrOutputReadyIllegalTransition(
      String workflowRunId, String runnerExecutionId, DomainException error) {
    WorkflowState current;
    try {
      current =
          workflowRunReadPort
              .findByPublicId(workflowRunId)
              .map(WorkflowRunSnapshot::currentState)
              .orElse(null);
    } catch (RuntimeException readError) {
      log.warn(
          "onPrOutputStageSucceeded could not re-read run state to classify ILLEGAL_TRANSITION "
              + "workflowRunId={} runnerExecutionId={} reason={} readError={}",
          workflowRunId,
          runnerExecutionId,
          error.getMessage(),
          readError.toString());
      return;
    }
    if (current != null && EXECUTION_READY_OR_BEYOND.contains(current)) {
      log.info(
          "onPrOutputStageSucceeded idempotent replay (pr-output-ready already applied) "
              + "workflowRunId={} runnerExecutionId={} currentState={}",
          workflowRunId,
          runnerExecutionId,
          current.value());
      return;
    }
    log.warn(
        "onPrOutputStageSucceeded probable anomaly: pr-output outcome for a run in unexpected state "
            + "— not advancing to WaitingForReview (best-effort classification from current state) "
            + "workflowRunId={} runnerExecutionId={} currentState={} reason={}",
        workflowRunId,
        runnerExecutionId,
        current == null ? "<not_found>" : current.value(),
        error.getMessage());
  }

  /**
   * AC6 (Task 2 — sub-stage-aware) — return a {@link RunnerDispatchResult.Replayed} for an
   * EXECUTION-stage runner execution that is already {@code pending}/{@code running} for the run
   * AND belongs to {@code expectedSubStage}, or {@code null} if none. With both the
   * implementation-plan and pr-output dispatch entry points live, a stage-only guard would make a
   * legitimate plan dispatch silently no-op against an in-flight pr-output execution (and vice
   * versa). The discriminator is {@code ContextBundleService.deriveExecutionSubStage(runId)}
   * resolved at guard time: the sub-stage is derived from "an approved implementation-plan exists",
   * which does NOT change mid-execution, so the value here matches the one the in-flight execution
   * was dispatched with and the one this dispatch will derive. If the run's current sub-stage
   * differs from {@code expectedSubStage}, the active row is a different sub-stage and is NOT
   * treated as in-flight.
   */
  private RunnerDispatchResult inFlightExecutionDispatch(
      String workflowRunId, ExecutionSubStage expectedSubStage) {
    ExecutionSubStage currentSubStage = contextBundleService.deriveExecutionSubStage(workflowRunId);
    if (currentSubStage != expectedSubStage) {
      return null;
    }
    for (RunnerExecutionSnapshot snapshot :
        runnerExecutionRecordPort.findByWorkflowRunPublicIdAndStatusIn(
            workflowRunId, ACTIVE_STATUSES)) {
      if (snapshot.stage() == RunnerStage.EXECUTION) {
        return new RunnerDispatchResult.Replayed(
            new RunnerExecutionHandle(
                snapshot.publicId(),
                snapshot.workflowRunPublicId(),
                snapshot.stage(),
                snapshot.status(),
                snapshot.timeoutAt()));
      }
    }
    return null;
  }

  /**
   * Deterministic EXECUTION-stage idempotency key (Decision D2): {@code
   * <keyPrefix>:<runId>:<nextExecutionBundleVersion>}. The bundle-version discriminator is shared
   * by the plan and pr-output sub-stages (same per-{@code (run, EXECUTION)} counter), so the prefix
   * (e.g. {@code plan-dispatch} vs {@code pr-output-dispatch}) plus the monotonically-advancing
   * version keep every dispatch's key distinct while a duplicate call within one attempt (same
   * next-version, no row inserted yet) replays.
   */
  private static String executionDispatchKey(
      String keyPrefix, String workflowRunId, int nextExecutionBundleVersion) {
    return keyPrefix + ":" + workflowRunId + ":" + nextExecutionBundleVersion;
  }

  // ---------------------------------------------------------------------------------------------

  private void ensureInvestigating(
      String workflowRunId, WorkflowRunSnapshot snapshot, String correlationId) {
    if (snapshot.currentState() == WorkflowState.INVESTIGATING) {
      log.debug(
          "dispatchSpecGeneration run already Investigating workflowRunId={} (no transition)",
          workflowRunId);
      return;
    }
    // ADR 0004 §Decision-1 / OQ-1: direct Inbox -> Investigating trigger (the table now admits it).
    // Any other source state surfaces ILLEGAL_TRANSITION from the transition service, which is the
    // correct guard against dispatching a spec runner from an unexpected state.
    // TransitionActor carries no correlationId field; the originating correlationId rides the
    // dispatch ActorContext + runner/artifact events. The state-change event correlation is
    // satisfied via the transition idempotency key + the MDC scope established above.
    workflowTransitionService.transition(
        workflowRunId,
        WorkflowState.INVESTIGATING,
        systemTransitionActor(),
        "spec_dispatch",
        "spec-investigating:" + workflowRunId);
    log.info(
        "dispatchSpecGeneration ensured Investigating workflowRunId={} from={} to=Investigating",
        workflowRunId,
        snapshot.currentState().value());
  }

  /**
   * AC6 / OQ-4 — return a {@link RunnerDispatchResult.Replayed} for an INVESTIGATION-stage runner
   * execution that is already {@code pending}/{@code running} for the run, or {@code null} if none.
   */
  private RunnerDispatchResult inFlightSpecDispatch(String workflowRunId) {
    for (RunnerExecutionSnapshot snapshot :
        runnerExecutionRecordPort.findByWorkflowRunPublicIdAndStatusIn(
            workflowRunId, ACTIVE_STATUSES)) {
      if (snapshot.stage() == RunnerStage.INVESTIGATION) {
        return new RunnerDispatchResult.Replayed(
            new RunnerExecutionHandle(
                snapshot.publicId(),
                snapshot.workflowRunPublicId(),
                snapshot.stage(),
                snapshot.status(),
                snapshot.timeoutAt()));
      }
    }
    return null;
  }

  private WorkflowRunSnapshot requireRun(String workflowRunId) {
    return workflowRunReadPort
        .findByPublicId(workflowRunId)
        .orElseThrow(
            () ->
                new DomainException(
                    DomainErrorCode.RUN_NOT_FOUND,
                    "Workflow run not found: " + workflowRunId,
                    Map.of("runId", workflowRunId)));
  }

  private void logDispatchOutcome(
      String op, String workflowRunId, String idempotencyKey, RunnerDispatchResult result) {
    if (result.isReplay()) {
      log.warn(
          "{} replay/in-flight no-op workflowRunId={} runnerExecutionId={} idempotencyKey={}",
          op,
          workflowRunId,
          result.handle().runnerExecutionId(),
          idempotencyKey);
    } else {
      log.info(
          "{} dispatched workflowRunId={} runnerExecutionId={} idempotencyKey={}",
          op,
          workflowRunId,
          result.handle().runnerExecutionId(),
          idempotencyKey);
    }
  }

  /**
   * Deterministic idempotency key incorporating the spec-rejection loop count (Dev Notes
   * §"Idempotency key"): initial submit (count=0) and each rejection-retry get distinct keys
   * (distinct dispatches), while a duplicate call within one attempt replays.
   */
  private static String specDispatchKey(String workflowRunId, int specRejectionLoopCount) {
    return "spec-dispatch:" + workflowRunId + ":" + specRejectionLoopCount;
  }

  private static ActorContext systemActor(String correlationId) {
    if (correlationId == null || correlationId.isBlank()) {
      return ActorContext.SYSTEM;
    }
    return new ActorContext("system", ActorType.SYSTEM, correlationId);
  }

  private static TransitionActor systemTransitionActor() {
    return new TransitionActor("system", ActorType.SYSTEM);
  }
}
