package org.dradgo.application.workflow;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.dradgo.application.approval.ApprovalSnapshot;
import org.dradgo.application.approval.spi.ApprovalReadPort;
import org.dradgo.application.artifact.ActorContext;
import org.dradgo.application.artifact.ArtifactRecordSnapshot;
import org.dradgo.application.artifact.spi.ArtifactRecordPort;
import org.dradgo.application.clarification.spi.ClarificationReadPort;
import org.dradgo.application.integration.IntegrationLink;
import org.dradgo.application.integration.IntegrationLinkService;
import org.dradgo.application.integration.ticketsource.TicketSourceAdapter;
import org.dradgo.application.integration.ticketsource.TicketSourceAdapterException;
import org.dradgo.application.observability.MdcKeys;
import org.dradgo.application.project.ProjectConnectorResolver;
import org.dradgo.application.project.ProjectRuntimeConfigResolver;
import org.dradgo.application.runner.ContextBundleService;
import org.dradgo.application.runner.ExecutionSubStage;
import org.dradgo.application.runner.RunnerDispatchResult;
import org.dradgo.application.runner.RunnerExecutionHandle;
import org.dradgo.application.runner.RunnerProperties;
import org.dradgo.application.runner.spi.RunnerExecutionRecordPort;
import org.dradgo.application.runner.spi.RunnerExecutionSnapshot;
import org.dradgo.application.security.RedactionPolicyService;
import org.dradgo.application.security.RedactionResult;
import org.dradgo.application.workflow.WorkflowTransitionService.TransitionActor;
import org.dradgo.application.workflow.spi.WorkflowEventReadPort;
import org.dradgo.application.workflow.spi.WorkflowEventRecord;
import org.dradgo.application.workflow.spi.WorkflowEventWritePort;
import org.dradgo.application.workflow.spi.WorkflowRunReadPort;
import org.dradgo.application.workflow.spi.WorkflowRunSnapshot;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.id.PublicIdPrefixes;
import org.dradgo.domain.integration.ticketsource.CommentResult;
import org.dradgo.domain.integration.ticketsource.GovernedRunComment;
import org.dradgo.domain.integration.ticketsource.TicketRef;
import org.dradgo.domain.integration.ticketsource.TicketSourceCapabilities;
import org.dradgo.domain.project.Project;
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.ArtifactType;
import org.dradgo.domain.registry.DataClassification;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.RunnerStage;
import org.dradgo.domain.registry.WorkflowEventDetailKeys;
import org.dradgo.domain.registry.WorkflowEventType;
import org.dradgo.domain.registry.WorkflowState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

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

  // Story 3.17b (Trap T1) — the in-flight guard set now includes QUEUED. With the queue activated,
  // a
  // dispatch that has only been enqueued (not yet leased by a worker) sits in status QUEUED; if the
  // guard ignored it, a second submit/approve would enqueue a DUPLICATE execution for the same run.
  private static final java.util.List<org.dradgo.domain.registry.RunnerExecutionStatus>
      ACTIVE_STATUSES =
          java.util.List.of(
              org.dradgo.domain.registry.RunnerExecutionStatus.QUEUED,
              org.dradgo.domain.registry.RunnerExecutionStatus.PENDING,
              org.dradgo.domain.registry.RunnerExecutionStatus.RUNNING,
              org.dradgo.domain.registry.RunnerExecutionStatus.AWAITING_MANUAL);

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

  // Story 3.17b (AC3/D1) — the dispatch callers now enqueue onto the RunnerExecutionQueue instead
  // of
  // calling RunnerBroker.dispatch synchronously; the worker pool runs the real dispatch later. This
  // service no longer depends on RunnerBroker at all, which also fully breaks the former
  // broker↔orchestration constructor coupling (the broker still calls back lazily via a Supplier).
  private final org.dradgo.application.runner.queue.RunnerExecutionQueue runnerExecutionQueue;
  // Story 3d-3 (AC3/AC6) — the manual-kind park sink. The enqueueDispatch chokepoint resolves the
  // run's runner kind and, when it is `manual`, routes here instead of runnerExecutionQueue (no
  // container, no queue row). Injected exactly as runnerExecutionQueue is; no DI cycle (the
  // dispatcher's broker/transition deps are themselves lazy back-edges).
  private final org.dradgo.application.runner.ManualExecutionDispatcher manualExecutionDispatcher;
  private final WorkflowTransitionService workflowTransitionService;
  private final WorkflowRunReadPort workflowRunReadPort;
  private final RunnerExecutionRecordPort runnerExecutionRecordPort;
  private final RunnerProperties runnerProperties;
  private final ContextBundleService contextBundleService;
  // Story 3.16 / 3c-7 (AC4) — completion-sync collaborators. The ticket-source adapter is now
  // resolved PER-PROJECT: the run's Project (via projectRuntimeConfigResolver) selects the adapter
  // by kind (via projectConnectorResolver.findTicketSource). R3 — findTicketSource returns
  // Optional.empty() (NOT a thrown UNSUPPORTED_CONNECTOR_KIND) when no adapter is registered for
  // the
  // kind in this context (a profile-gated @SpringBootTest with no linear profile), preserving the
  // SKIPPED_NO_LINEAR_PROFILE skip. Both resolvers are always-present application.project
  // @Components depending only on ProjectStore/adapter lists — no DI cycle with this service.
  private final ProjectRuntimeConfigResolver projectRuntimeConfigResolver;
  private final ProjectConnectorResolver projectConnectorResolver;
  private final RedactionPolicyService redactionPolicyService;
  private final WorkflowProperties workflowProperties;
  private final IntegrationLinkService integrationLinkService;
  private final ArtifactRecordPort artifactRecordPort;
  private final ApprovalReadPort approvalReadPort;
  private final WorkflowEventReadPort workflowEventReadPort;
  private final WorkflowEventWritePort workflowEventWritePort;
  private final ClarificationReadPort clarificationReadPort;

  public WorkflowOrchestrationService(
      org.dradgo.application.runner.queue.RunnerExecutionQueue runnerExecutionQueue,
      org.dradgo.application.runner.ManualExecutionDispatcher manualExecutionDispatcher,
      WorkflowTransitionService workflowTransitionService,
      WorkflowRunReadPort workflowRunReadPort,
      RunnerExecutionRecordPort runnerExecutionRecordPort,
      RunnerProperties runnerProperties,
      ContextBundleService contextBundleService,
      ProjectRuntimeConfigResolver projectRuntimeConfigResolver,
      ProjectConnectorResolver projectConnectorResolver,
      RedactionPolicyService redactionPolicyService,
      WorkflowProperties workflowProperties,
      IntegrationLinkService integrationLinkService,
      ArtifactRecordPort artifactRecordPort,
      ApprovalReadPort approvalReadPort,
      WorkflowEventReadPort workflowEventReadPort,
      WorkflowEventWritePort workflowEventWritePort,
      ClarificationReadPort clarificationReadPort) {
    this.runnerExecutionQueue =
        Objects.requireNonNull(runnerExecutionQueue, "runnerExecutionQueue");
    this.manualExecutionDispatcher =
        Objects.requireNonNull(manualExecutionDispatcher, "manualExecutionDispatcher");
    this.workflowTransitionService =
        Objects.requireNonNull(workflowTransitionService, "workflowTransitionService");
    this.workflowRunReadPort = Objects.requireNonNull(workflowRunReadPort, "workflowRunReadPort");
    this.runnerExecutionRecordPort =
        Objects.requireNonNull(runnerExecutionRecordPort, "runnerExecutionRecordPort");
    this.runnerProperties = Objects.requireNonNull(runnerProperties, "runnerProperties");
    this.contextBundleService =
        Objects.requireNonNull(contextBundleService, "contextBundleService");
    this.projectRuntimeConfigResolver =
        Objects.requireNonNull(projectRuntimeConfigResolver, "projectRuntimeConfigResolver");
    this.projectConnectorResolver =
        Objects.requireNonNull(projectConnectorResolver, "projectConnectorResolver");
    this.redactionPolicyService =
        Objects.requireNonNull(redactionPolicyService, "redactionPolicyService");
    this.workflowProperties = Objects.requireNonNull(workflowProperties, "workflowProperties");
    this.integrationLinkService =
        Objects.requireNonNull(integrationLinkService, "integrationLinkService");
    this.artifactRecordPort = Objects.requireNonNull(artifactRecordPort, "artifactRecordPort");
    this.approvalReadPort = Objects.requireNonNull(approvalReadPort, "approvalReadPort");
    this.workflowEventReadPort =
        Objects.requireNonNull(workflowEventReadPort, "workflowEventReadPort");
    this.workflowEventWritePort =
        Objects.requireNonNull(workflowEventWritePort, "workflowEventWritePort");
    this.clarificationReadPort =
        Objects.requireNonNull(clarificationReadPort, "clarificationReadPort");
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
          enqueueDispatch(
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
          enqueueDispatch(
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
      // Story 3e-3 (AC1/AC6) — fire the SAME advisory reviewer (3d-2) at the spec gate, exactly as
      // onPlanStageSucceeded/onPrOutputStageSucceeded do at the execution gate. Async +
      // non-blocking
      // on WaitingForSpecApproval entry; shares the poller's per-item transaction with the
      // transition above so a reviewer is enqueued iff the spec-ready transition commits. The
      // reviewer binding is run-level (no per-stage field), the reviewed-artifact derivation falls
      // through to the spec (resolveReviewedArtifact), and every failure mode degrades to "no
      // verdict" inside the helper (the spec gate is never blocked, AC6/AC7).
      //
      // Story 3e-3 review follow-up — DEFER the reviewer while the just-produced spec still carries
      // open/unresolved clarifications. Such a spec is not approvable: the story-2.12
      // pending-clarification gate (countPendingByWorkflowRun) already blocks approve_spec, so
      // reviewing it only produces a misleading "fail" on questions the user has not answered yet.
      // The reviewer re-fires on the REGENERATED spec — regenerateSpecWithClarifications (3e-2)
      // re-runs the spec runner, which lands back here via onSpecStageSucceeded — where the
      // accepted
      // clarifications are incorporated (pending == 0) and the spec is finally the one being
      // approved. Read failure degrades to "not pending" (enqueue), so a transient fault never
      // silently drops the advisory review; the gate only SUPPRESSES on a confirmed positive count.
      int pendingClarifications = pendingClarificationCountForSpecGate(workflowRunId);
      if (pendingClarifications > 0) {
        log.info(
            "spec-phase advisory reviewer deferred for run {} runnerExecutionId={} — {} pending "
                + "clarification(s); it will run on the regenerated spec once they are resolved",
            workflowRunId,
            runnerExecutionId,
            pendingClarifications);
      } else {
        enqueueReviewerIfConfigured(workflowRunId, runnerExecutionId, correlationId);
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

  /** Convenience overload — no originating correlationId threaded. */
  public RunnerDispatchResult redispatchAfterRetry(String workflowRunId) {
    return redispatchAfterRetry(workflowRunId, null);
  }

  /**
   * RC2 (rerun re-dispatch) — re-dispatch the EXECUTION-stage runner for a run the REST/CLI {@code
   * retry-workflow} command just transitioned {@code Failed -> Executing}. The bare {@code
   * WorkflowCommandService.retryWorkflow} transition enqueues NOTHING, so without this hop the run
   * sits in {@code Executing} with no queued/active runner and wedges forever (the worker pool
   * keeps reporting "dequeue found no queued row"). Mirrors the recovery/retry baseline ({@code
   * RecoveryService.retry} Step 6) but routes through the existing sub-stage-aware {@link
   * #retryPlanGeneration} / {@link #retryImplementation} so the in-flight guard,
   * fresh-bundle-version idempotency key, and auto-dispatch gate are all reused.
   *
   * <p>The EXECUTION sub-stage is the run-level discriminator ("does an approved
   * implementation-plan exist"): a run that failed BEFORE plan approval re-dispatches the plan
   * runner; one that failed at pr-output re-dispatches the pr-output runner. NEVER transitions
   * (Trap T1) — the caller already did. No-op (returns {@code null}) when the relevant
   * auto-dispatch gate is off (the shared test profile), so the bare {@code Failed -> Executing}
   * transition stays observable without a runner.
   */
  public RunnerDispatchResult redispatchAfterRetry(String workflowRunId, String correlationId) {
    PublicIdPrefixes.require(workflowRunId, PublicIdPrefixes.WORKFLOW_RUN);
    ExecutionSubStage subStage = contextBundleService.deriveExecutionSubStage(workflowRunId);
    return subStage == ExecutionSubStage.IMPLEMENTATION_PLAN
        ? retryPlanGeneration(workflowRunId, correlationId)
        : retryImplementation(workflowRunId, correlationId);
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
          enqueueDispatch(
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
      // Story 3d-2 (AC1/AC5/AC6, DD-4) — fire the advisory reviewer ASYNC + non-blocking on
      // WaitingForReview entry. The human can review immediately; the verdict arrives later. This
      // call shares the poller's per-item transaction with the transition above (the transition is
      // NOT yet committed — see this method's javadoc); the enqueue is intentionally part of that
      // same tx so a reviewer is enqueued iff the transition commits, and it never throws into this
      // success path (faults degrade to "no verdict", logged WARN inside the helper).
      enqueueReviewerIfConfigured(workflowRunId, runnerExecutionId, correlationId);
    } finally {
      MdcKeys.endScope(MdcKeys.RUNNER_EXECUTION_ID, priorRexMdc);
      MdcKeys.endScope(MdcKeys.WORKFLOW_RUN_ID, priorRunMdc);
    }
  }

  /**
   * Story 3d-2 (AC1/AC5/AC6, Task 4) — enqueue an advisory reviewer invocation ({@link
   * RunnerStage#REVIEW}) over the just-produced output artifact when (and only when) the run's
   * project carries a reviewer binding. No binding ⇒ no enqueue ⇒ byte-identical to pre-3d (AC5,
   * the hot path). The reviewer rides the existing worker pool exactly like any runner; it is NOT a
   * manual park. A set-but-invalid binding (misconfig / missing credential) is still enqueued so
   * the WORKER records the failed reviewer execution (the panel "unavailable" state) — every
   * reviewer failure mode funnels through that single graceful-degradation path. The enqueue itself
   * can never block or fail the run's review entry (AC6): a fault here degrades to "no verdict",
   * logged WARN.
   */
  /**
   * Story 3e-3 review follow-up — the pending-clarification count that gates the spec-phase
   * advisory reviewer, mirroring the story-2.12 {@code approve_spec} gate exactly (non-terminal,
   * non-archived clarifications). A positive count means the just-produced spec is not approvable
   * and a review of it would only mislead, so the caller defers. Best-effort: a read failure
   * returns {@code 0} (fail OPEN — enqueue the review) so a transient DB fault never silently drops
   * the advisory review; only a confirmed positive count suppresses it.
   */
  private int pendingClarificationCountForSpecGate(String workflowRunId) {
    try {
      return clarificationReadPort.countPendingByWorkflowRun(workflowRunId);
    } catch (RuntimeException readError) {
      log.warn(
          "pending-clarification read failed for run {} at the spec gate cause={} — enqueuing the"
              + " advisory reviewer anyway (fail-open; never silently drop the review)",
          workflowRunId,
          readError.toString());
      return 0;
    }
  }

  private void enqueueReviewerIfConfigured(
      String workflowRunId, String producingRunnerExecutionId, String correlationId) {
    boolean hasBinding;
    try {
      hasBinding = projectRuntimeConfigResolver.hasReviewerBinding(workflowRunId);
    } catch (RuntimeException resolveError) {
      log.warn(
          "reviewer binding resolution failed for run {} cause={} — no advisory review enqueued,"
              + " run remains human-reviewable",
          workflowRunId,
          resolveError.toString());
      return;
    }
    if (!hasBinding) {
      log.debug(
          "reviewer not configured for run {} — no advisory review enqueued (parity)",
          workflowRunId);
      return;
    }
    try {
      String reviewIdempotencyKey = "review:" + producingRunnerExecutionId;
      org.dradgo.application.runner.queue.QueuedRunnerExecution queued =
          runnerExecutionQueue.enqueue(
              workflowRunId,
              RunnerStage.REVIEW,
              reviewIdempotencyKey,
              systemActor(correlationId),
              DEFAULT_QUEUE_PRIORITY);
      // Story 3d-2 (code-review D1) — resolve the reviewed artifact ONCE here and pin it on the
      // reviewer execution so the harvest reuses the exact artifact the reviewer saw (the compose
      // and harvest no longer independently re-derive `latestAvailable`, which could drift the
      // verdict FK to a newer artifact). Best-effort: a pin failure leaves the row unpinned and the
      // worker/harvest fall back to re-deriving — never blocks the review entry (AC6).
      pinReviewedArtifactBestEffort(workflowRunId, queued.runnerExecutionPublicId());
      log.info(
          "enqueuing reviewer review for run {} producingRunnerExecutionId={} reviewerExec={}",
          workflowRunId,
          producingRunnerExecutionId,
          queued.runnerExecutionPublicId());
    } catch (RuntimeException enqueueError) {
      log.warn(
          "reviewer enqueue failed for run {} producingRunnerExecutionId={} cause={} — run remains"
              + " human-reviewable",
          workflowRunId,
          producingRunnerExecutionId,
          enqueueError.toString());
    }
  }

  /**
   * Story 3f-4 — enqueue the advisory split-proposal dispatch through the single allowed enqueue
   * seam (ArchUnit {@code only_orchestration_recovery_and_worker_pool_may_enqueue}). It is an
   * ordinary split-mode {@code RunnerStage.REVIEW} execution; the split-vs-verdict distinction
   * lives entirely in the {@code split-proposal:<runId>:<loop>} idempotency key minted by {@code
   * SplitProposalService}. Unlike {@link #enqueueReviewerIfConfigured} it does NOT pin a reviewed
   * artifact — the split compose/harvest key off the dispatch key, not a pinned artifact.
   */
  public org.dradgo.application.runner.queue.QueuedRunnerExecution enqueueSplitProposalDispatch(
      String workflowRunId, String splitDispatchKey, String correlationId) {
    return runnerExecutionQueue.enqueue(
        workflowRunId,
        RunnerStage.REVIEW,
        splitDispatchKey,
        systemActor(correlationId),
        DEFAULT_QUEUE_PRIORITY);
  }

  /**
   * Story 3d-2 (code-review D1) — best-effort pin of the reviewed artifact (id+version+type) onto
   * the freshly-enqueued reviewer execution. Resolved via the SAME derivation the compose uses
   * ({@code latestAvailable(prOutput).or(implementationPlan)}), but ONCE, at enqueue, so the
   * harvest reuses it. A resolve/pin fault never strands or blocks the run (AC6): the worker +
   * harvest fall back to re-deriving when the pin is absent.
   */
  private void pinReviewedArtifactBestEffort(String workflowRunId, String reviewerExecutionId) {
    try {
      org.dradgo.application.artifact.ArtifactRecordSnapshot reviewed =
          contextBundleService.resolveReviewedArtifact(workflowRunId);
      runnerExecutionRecordPort.pinReviewedArtifact(
          reviewerExecutionId,
          reviewed.publicId(),
          reviewed.version(),
          reviewed.artifactType().value());
    } catch (RuntimeException pinFailure) {
      log.warn(
          "reviewer reviewed-artifact pin skipped run={} reviewerExec={} cause={} — harvest will"
              + " re-derive",
          workflowRunId,
          reviewerExecutionId,
          pinFailure.getClass().getSimpleName());
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
      // Story 3d-2 (AC1/AC5/AC6, DD-4) — advisory reviewer over the pr-output artifact, async +
      // non-blocking on WaitingForReview entry (same contract as the plan-stage seam).
      enqueueReviewerIfConfigured(workflowRunId, runnerExecutionId, correlationId);
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
   * Story 3h-2 (AC5; code-review 2026-07-06) — enqueue the advisory reviewer over the (now
   * push-enriched) pr-output artifact after an operator {@code approve_lint} dismissed the lint
   * gate. The {@code WaitingForLintApproval -> WaitingForReview} transition is owned by {@code
   * LintApprovalService.approveLint} (synchronous, in the command tx — a wrong-state approve 409s
   * instead of the old deferred/swallowed posture, P2). Called by {@code
   * RunnerBroker.resumeDeliveryTailFromGate} AFTER the (idempotent) captureAndPush + pr-output
   * enrichment + producing-execution finalize, so the reviewer sees the pushed, enriched artifact.
   * Enqueue-only + idempotency-keyed, so a replayed approval never double-enqueues.
   */
  public void enqueueReviewerAfterLintApproval(
      String workflowRunId, String runnerExecutionId, String correlationId) {
    PublicIdPrefixes.require(workflowRunId, PublicIdPrefixes.WORKFLOW_RUN);
    PublicIdPrefixes.require(runnerExecutionId, PublicIdPrefixes.RUNNER_EXECUTION);
    String priorRunMdc = MdcKeys.beginScope(MdcKeys.WORKFLOW_RUN_ID, workflowRunId);
    String priorRexMdc = MdcKeys.beginScope(MdcKeys.RUNNER_EXECUTION_ID, runnerExecutionId);
    try {
      // The WaitingForLintApproval -> WaitingForReview transition is owned by
      // LintApprovalService.approveLint (SYNCHRONOUS, in the command tx, so a wrong-state approve
      // 409s — code-review 2026-07-06 P2). By the time this runs post-commit the run is already
      // WaitingForReview, so this ONLY enqueues the reviewer over the (now push-enriched) pr-output
      // artifact. The enqueue is idempotency-keyed, so a replayed approval never double-enqueues.
      log.info(
          "enqueueReviewerAfterLintApproval workflowRunId={} runnerExecutionId={} "
              + "(run already WaitingForReview — enqueue only)",
          workflowRunId,
          runnerExecutionId);
      enqueueReviewerIfConfigured(workflowRunId, runnerExecutionId, correlationId);
    } finally {
      MdcKeys.endScope(MdcKeys.RUNNER_EXECUTION_ID, priorRexMdc);
      MdcKeys.endScope(MdcKeys.WORKFLOW_RUN_ID, priorRunMdc);
    }
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

  // Story 3.17b — default dequeue priority for orchestration-driven enqueues (matches the V12
  // queue_priority column default). No caller distinguishes priorities yet; batch/priority tuning
  // is
  // story 3.18's concern.
  private static final int DEFAULT_QUEUE_PRIORITY = 100;

  /**
   * Story 3.17b (AC3 / Trap T1) — enqueue a dispatch onto the {@link
   * org.dradgo.application.runner.queue.RunnerExecutionQueue} (the worker pool runs the real
   * dispatch later) and adapt the {@link org.dradgo.application.runner.queue.QueuedRunnerExecution}
   * into a {@link RunnerDispatchResult.Queued} so the method's return type + its result-ignoring
   * callers are unchanged. A {@code RUNNER_QUEUE_FULL} from the queue propagates unchanged: it
   * rolls back the caller's submit/approve transaction (the run stays in its prior state) and
   * surfaces as a retryable 503 — it never drives a FAILED transition (Trap T6).
   */
  private RunnerDispatchResult enqueueDispatch(
      String workflowRunId,
      org.dradgo.domain.registry.RunnerStage stage,
      String idempotencyKey,
      ActorContext actor) {
    // Story 3d-3 (AC1/AC3/AC6) — resolve the run's effective runner kind at THIS chokepoint (the
    // single orchestration enqueue funnel). When it is `manual`, park the run (compose+write the
    // bundle, record an awaiting_manual row, transition to WaitingForManualExecution) instead of
    // enqueuing container work — no queue row, no worker slot, no adapter dispatch. A default
    // (non-overridden) project resolves the global per-stage kind ⇒ the unchanged enqueue path
    // below
    // (R7 byte-identical parity).
    org.dradgo.domain.registry.RunnerKind kind =
        projectRuntimeConfigResolver.resolveRunnerKind(
            workflowRunId,
            stage,
            stage == RunnerStage.EXECUTION
                ? contextBundleService.deriveExecutionSubStage(workflowRunId)
                : null);
    if (kind == org.dradgo.domain.registry.RunnerKind.MANUAL) {
      RunnerDispatchResult parked =
          manualExecutionDispatcher.park(workflowRunId, stage, idempotencyKey, actor);
      log.info(
          "enqueueDispatch parked for manual execution workflowRunId={} stage={} runnerExecutionId={} idempotencyKey={}",
          workflowRunId,
          stage.value(),
          parked.handle().runnerExecutionId(),
          idempotencyKey);
      return parked;
    }
    org.dradgo.application.runner.queue.QueuedRunnerExecution queued =
        runnerExecutionQueue.enqueue(
            workflowRunId, stage, idempotencyKey, actor, DEFAULT_QUEUE_PRIORITY);
    // The queued row's real timeout starts when a worker dispatches it; the handle's timeoutAt is
    // an
    // informational estimate (now + the stage timeout) — the handle requires a non-null value.
    java.time.OffsetDateTime estimatedTimeoutAt =
        java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC)
            .plus(runnerProperties.timeoutFor(stage));
    RunnerExecutionHandle handle =
        new RunnerExecutionHandle(
            queued.runnerExecutionPublicId(),
            queued.workflowRunPublicId(),
            queued.stage(),
            org.dradgo.domain.registry.RunnerExecutionStatus.QUEUED,
            estimatedTimeoutAt);
    return new RunnerDispatchResult.Queued(handle, queued.queuedEventPublicId());
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
    } else if (result.isParked()) {
      // Story 3d-3 — the run was parked for manual execution (no container/queue). Reporting
      // "queued" here would misstate the run's progress (it never enqueued).
      log.info(
          "{} parked for manual execution workflowRunId={} runnerExecutionId={} idempotencyKey={}",
          op,
          workflowRunId,
          result.handle().runnerExecutionId(),
          idempotencyKey);
    } else {
      // Story 3.17b (Trap T1) — the outcome is now "queued" (worker dispatches later), not
      // "dispatched". Reporting "dispatched" here would silently misstate the run's progress.
      log.info(
          "{} queued workflowRunId={} runnerExecutionId={} idempotencyKey={}",
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

  // ===============================================================================================
  // Story 3.16 — Linear completion-sync: write a merge-ready summary back to the source ticket.
  // ===============================================================================================

  /** Outcome of {@link #syncCompletionToLinear} (review 2026-06-11 — for the manual CLI). */
  public enum SyncCompletionOutcome {
    /** The summary was composed and handed to the Linear adapter. */
    POSTED,
    /** {@code linear-completion-sync.enabled=false} — nothing attempted. */
    SKIPPED_DISABLED,
    /** The run has no active {@code linear_ticket} link — nothing to post to. */
    SKIPPED_NO_TICKET_LINK,
    /** No {@code linear-mock|linear-real} profile active — no adapter to post with. */
    SKIPPED_NO_LINEAR_PROFILE,
    /**
     * The active ticket source declares {@code supportsCommentOnTicket=false} (story 3.32 AC3) —
     * the write-back is gracefully skipped with a {@code linear.completionSyncSkipped} WARN log.
     */
    SKIPPED_NO_COMMENT_CAPABILITY,
    /** The Linear post threw; recorded as a {@code linear.completionSyncFailed} event. */
    POST_FAILED
  }

  /**
   * Story 3.16 (AC2/AC3/AC5/AC6) — compose a merge-ready completion summary and post it back to the
   * run's source Linear ticket. Invoked (a) automatically by the {@link WorkflowTransitionService}
   * post-commit hook when a run transitions to {@code Completed} (Task 2), and (b) manually via the
   * {@code deliveryline sync-completion} CLI command (Task 7).
   *
   * <p>Best-effort and after-the-fact (AC4): a missing optional datum degrades to a documented
   * fallback (Decision D5); a missing {@code linear_ticket} link short-circuits with a WARN
   * (nothing to post to); any post failure is recorded as a {@code linear.completionSyncFailed}
   * event and swallowed — this method NEVER throws, so it can never roll back the (already
   * committed, since it runs post-commit) {@code Completed} transition. Idempotent via the
   * adapter's fingerprint-marker dedup (Decision D3): the {@code fingerprint} is a stable SHA-256
   * of a run-identity basis that EXCLUDES volatile fields (cycle time, PR URL), so a re-sync after
   * the run's event timeline advances is still a no-op at the adapter (review 2026-06-11).
   *
   * <p>The summary body is composed, then passed through {@link RedactionPolicyService} claiming
   * {@code shareable-full} (AC2/AC6): redaction scrubs any secret/local-path that leaked from a
   * dirty source datum, keeping the effective classification shareable rather than leaking it.
   *
   * @return the outcome (posted / skipped-with-reason / post-failed) so the manual {@code
   *     sync-completion} CLI can report it; the post-commit hook ignores it (best-effort).
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public SyncCompletionOutcome syncCompletionToLinear(String workflowRunId) {
    PublicIdPrefixes.require(workflowRunId, PublicIdPrefixes.WORKFLOW_RUN);
    if (!workflowProperties.linearCompletionSync().enabled()) {
      log.debug(
          "syncCompletionToLinear skipped (linear-completion-sync.enabled=false) workflowRunId={}",
          workflowRunId);
      return SyncCompletionOutcome.SKIPPED_DISABLED;
    }
    String correlationId =
        workflowEventReadPort.findLatestCorrelationId(workflowRunId).orElse(null);
    String priorCorrelationMdc = MdcKeys.beginScope(MdcKeys.CORRELATION_ID, correlationId);
    String priorRunMdc = MdcKeys.beginScope(MdcKeys.WORKFLOW_RUN_ID, workflowRunId);
    try {
      Optional<IntegrationLink> ticketLink =
          integrationLinkService.findActiveLinearTicketLink(workflowRunId);
      if (ticketLink.isEmpty()) {
        log.warn(
            "syncCompletionToLinear no_linear_ticket_link workflowRunId={} (nothing to post to)",
            workflowRunId);
        return SyncCompletionOutcome.SKIPPED_NO_TICKET_LINK;
      }
      String ticketRef = ticketLink.get().externalRef();
      log.info(
          "syncCompletionToLinear entry workflowRunId={} ticketRef={}",
          workflowRunId,
          MdcKeys.sanitizeForLog(ticketRef));

      String prUrl = resolvePrUrl(workflowRunId);
      Map<String, String> values = templateValues(workflowRunId, prUrl);
      String canonicalBody =
          LinearCompletionTemplate.render(
              workflowProperties.linearCompletionSync().template(), values);
      String fingerprint = fingerprint(stableFingerprintBasis(values));

      RedactionResult redaction =
          redactionPolicyService.redact(canonicalBody, DataClassification.SHAREABLE_FULL.value());
      if (redaction.redacted()) {
        log.warn(
            "syncCompletionToLinear summary_redacted workflowRunId={} effectiveClassification={} "
                + "detectedCategories={}",
            workflowRunId,
            redaction.effectiveClassification().value(),
            redaction.detectedCategories());
      }
      String body = redaction.sanitizedText();

      // Story 3c-7 (AC4 / R3) — resolve the ticket-source adapter via the run's Project. R3:
      // findTicketSource returns empty (NOT a thrown UNSUPPORTED_CONNECTOR_KIND) when no adapter is
      // registered for the project's kind in this context (profile-gated), preserving the
      // SKIPPED_NO_LINEAR_PROFILE skip exactly as the pre-3c-7 getIfAvailable()==null path did.
      Project project;
      Optional<TicketSourceAdapter> adapterOpt;
      try {
        project = projectRuntimeConfigResolver.resolveForRun(workflowRunId);
        adapterOpt = projectConnectorResolver.findTicketSource(project);
      } catch (DomainException error) {
        // Story 3c-7 review (P1) — honor this method's best-effort, never-throws contract (it runs
        // in
        // a post-commit hook and must not roll back the already-committed Completed transition). An
        // unresolvable project (e.g. the default project missing — PROJECT_NOT_FOUND) degrades to
        // the
        // same skip as a missing linear profile rather than propagating out of the sync.
        log.warn(
            "syncCompletionToLinear project_resolution_failed workflowRunId={} reason={}; skipping post",
            workflowRunId,
            error.errorCode().value());
        return SyncCompletionOutcome.SKIPPED_NO_LINEAR_PROFILE;
      }
      if (adapterOpt.isEmpty()) {
        log.warn(
            "syncCompletionToLinear linear_adapter_unavailable workflowRunId={} projectId={} "
                + "ticketKind={} (no adapter registered for the project's kind); skipping post",
            workflowRunId,
            project.publicId(),
            project.ticketSourceKind().value());
        return SyncCompletionOutcome.SKIPPED_NO_LINEAR_PROFILE;
      }
      TicketSourceAdapter adapter = adapterOpt.get();
      log.info(
          "syncCompletionToLinear linear sync adapter resolved workflowRunId={} projectId={} ticketKind={}",
          workflowRunId,
          project.publicId(),
          project.ticketSourceKind().value());

      // Story 3.32 AC3 — gate the optional write-back on the source's declared capability. A source
      // that cannot post comments degrades gracefully with a structured WARN (no new
      // WorkflowEventType — R6) and the SKIPPED_NO_COMMENT_CAPABILITY outcome. The capability probe
      // is guarded so a misbehaving future adapter (null or throwing getCapabilities()) cannot
      // break
      // this method's best-effort, never-throws contract: a thrown probe is recorded as POST_FAILED
      // and a null declaration is treated conservatively as "no comment capability" (skip).
      TicketSourceCapabilities capabilities;
      try {
        capabilities = adapter.getCapabilities();
      } catch (TicketSourceAdapterException error) {
        recordSyncFailure(
            workflowRunId, ticketRef, correlationId, error.failureCategory().value(), error);
        return SyncCompletionOutcome.POST_FAILED;
      } catch (RuntimeException error) {
        recordSyncFailure(workflowRunId, ticketRef, correlationId, "unknown", error);
        return SyncCompletionOutcome.POST_FAILED;
      }
      if (capabilities == null || !capabilities.supportsCommentOnTicket()) {
        log.warn(
            "event=linear.completionSyncSkipped reason=ticket_source_does_not_support_comments "
                + "workflowRunId={} ticketRef={}",
            workflowRunId,
            MdcKeys.sanitizeForLog(ticketRef));
        return SyncCompletionOutcome.SKIPPED_NO_COMMENT_CAPABILITY;
      }

      try {
        CommentResult result =
            adapter.postGovernedRunComment(
                TicketRef.of(ticketRef),
                new GovernedRunComment(
                    workflowRunId, fingerprint, body, DataClassification.SHAREABLE_FULL));
        // SKIPPED_DUPLICATE is an idempotent replay (the summary is already on the ticket) — log at
        // INFO, still report POSTED to the caller since the write-back is durably present.
        log.info(
            "syncCompletionToLinear posted workflowRunId={} ticketRef={} fingerprint={} result={}",
            workflowRunId,
            MdcKeys.sanitizeForLog(ticketRef),
            fingerprint,
            result);
        return SyncCompletionOutcome.POSTED;
      } catch (TicketSourceAdapterException error) {
        recordSyncFailure(
            workflowRunId, ticketRef, correlationId, error.failureCategory().value(), error);
        return SyncCompletionOutcome.POST_FAILED;
      } catch (RuntimeException error) {
        recordSyncFailure(workflowRunId, ticketRef, correlationId, "unknown", error);
        return SyncCompletionOutcome.POST_FAILED;
      }
    } finally {
      MdcKeys.endScope(MdcKeys.WORKFLOW_RUN_ID, priorRunMdc);
      MdcKeys.endScope(MdcKeys.CORRELATION_ID, priorCorrelationMdc);
    }
  }

  /**
   * Story 4.7 (AC7 / Reconciliation 6) — best-effort reverse notification posted to the run's
   * source ticket when a rerun-from-step reopens the run. Mirrors {@link #syncCompletionToLinear}'s
   * gating (active ticket link + resolvable adapter + {@code supportsCommentOnTicket}) and posts a
   * governed "run reopened" comment with a DISTINCT fingerprint basis, appending {@code
   * linear.runReopenedNotification} on success. It NEVER throws and never rolls back the
   * already-committed rerun: a missing link / missing capability / adapter failure logs + skips.
   * Runs in its own {@code REQUIRES_NEW} transaction (called by {@code
   * RecoveryService.rerunFromStep} after the prep tx commits, OUTSIDE any tx).
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void notifyLinearRunReopened(
      String workflowRunId, String targetStep, String correlationId) {
    PublicIdPrefixes.require(workflowRunId, PublicIdPrefixes.WORKFLOW_RUN);
    String priorRunMdc = MdcKeys.beginScope(MdcKeys.WORKFLOW_RUN_ID, workflowRunId);
    try {
      Optional<IntegrationLink> ticketLink =
          integrationLinkService.findActiveLinearTicketLink(workflowRunId);
      if (ticketLink.isEmpty()) {
        log.warn(
            "notifyLinearRunReopened no_linear_ticket_link workflowRunId={} (nothing to post to)",
            workflowRunId);
        return;
      }
      String ticketRef = ticketLink.get().externalRef();
      Project project;
      Optional<TicketSourceAdapter> adapterOpt;
      try {
        project = projectRuntimeConfigResolver.resolveForRun(workflowRunId);
        adapterOpt = projectConnectorResolver.findTicketSource(project);
      } catch (DomainException error) {
        log.warn(
            "notifyLinearRunReopened project_resolution_failed workflowRunId={} reason={}; skipping",
            workflowRunId,
            error.errorCode().value());
        return;
      }
      if (adapterOpt.isEmpty()) {
        log.warn(
            "notifyLinearRunReopened linear_adapter_unavailable workflowRunId={}; skipping",
            workflowRunId);
        return;
      }
      TicketSourceAdapter adapter = adapterOpt.get();
      TicketSourceCapabilities capabilities;
      try {
        capabilities = adapter.getCapabilities();
      } catch (RuntimeException error) {
        log.warn(
            "notifyLinearRunReopened capability_probe_failed workflowRunId={} errorClass={}; skipping",
            workflowRunId,
            error.getClass().getSimpleName());
        return;
      }
      if (capabilities == null || !capabilities.supportsCommentOnTicket()) {
        log.warn(
            "event=linear.runReopenedSkipped reason=ticket_source_does_not_support_comments "
                + "workflowRunId={} ticketRef={}",
            workflowRunId,
            MdcKeys.sanitizeForLog(ticketRef));
        return;
      }
      String canonicalBody =
          "DeliveryLine run "
              + workflowRunId
              + " was reopened via rerun-from-step (target step: "
              + targetStep
              + "). The prior stage decision has been invalidated and the run will be re-run.";
      RedactionResult redaction =
          redactionPolicyService.redact(canonicalBody, DataClassification.SHAREABLE_FULL.value());
      String body = redaction.sanitizedText();
      // Distinct fingerprint basis from the completion comment so the reopen notification is its
      // own
      // at-most-once post per (run, targetStep).
      String reopenFingerprint =
          fingerprint("rerun-reopened\nrunId=" + workflowRunId + "\ntargetStep=" + targetStep);
      try {
        CommentResult result =
            adapter.postGovernedRunComment(
                TicketRef.of(ticketRef),
                new GovernedRunComment(
                    workflowRunId, reopenFingerprint, body, DataClassification.SHAREABLE_FULL));
        log.info(
            "notifyLinearRunReopened posted workflowRunId={} ticketRef={} fingerprint={} result={}",
            workflowRunId,
            MdcKeys.sanitizeForLog(ticketRef),
            reopenFingerprint,
            result);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put(WorkflowEventDetailKeys.LINEAR_TICKET_REFERENCE, ticketRef);
        details.put(WorkflowEventDetailKeys.TARGET_STEP, targetStep);
        if (correlationId != null && !correlationId.isBlank()) {
          details.put(WorkflowEventDetailKeys.CORRELATION_ID, correlationId);
        }
        workflowEventWritePort.append(
            new WorkflowEventRecord(
                PublicIdPrefixes.WORKFLOW_EVENT.next(),
                workflowRunId,
                WorkflowEventType.LINEAR_RUN_REOPENED_NOTIFICATION,
                null,
                null,
                ActorContext.SYSTEM.actorIdentity(),
                ActorType.SYSTEM,
                "linear_run_reopened_notification",
                null,
                false,
                OffsetDateTime.now(java.time.ZoneOffset.UTC),
                details));
      } catch (RuntimeException error) {
        log.warn(
            "notifyLinearRunReopened post_failed workflowRunId={} ticketRef={} errorClass={}",
            workflowRunId,
            MdcKeys.sanitizeForLog(ticketRef),
            error.getClass().getSimpleName());
      }
    } catch (RuntimeException error) {
      log.warn(
          "notifyLinearRunReopened unexpected_failure workflowRunId={} errorClass={}",
          workflowRunId,
          error.getClass().getSimpleName());
    } finally {
      MdcKeys.endScope(MdcKeys.WORKFLOW_RUN_ID, priorRunMdc);
    }
  }

  /**
   * Resolve template placeholder values defensively (Decision D5) — every optional datum that
   * cannot be resolved renders as a documented fallback ({@code n/a} / {@code unknown}) rather than
   * failing the sync.
   */
  private Map<String, String> templateValues(String workflowRunId, String prUrl) {
    Map<String, String> values = new LinkedHashMap<>();
    values.put("runId", workflowRunId);
    values.put("prUrl", prUrl == null ? "n/a" : prUrl);
    Optional<ArtifactRecordSnapshot> spec =
        artifactRecordPort.findLatestByWorkflowRunIdAndArtifactType(
            workflowRunId, ArtifactType.SPEC.value());
    // OQ-2: the artifact read port exposes no human-readable spec summary; surface a short fixed
    // pointer when a spec exists and a fallback otherwise. specVersion is the artifact version.
    values.put("specSummary", spec.isPresent() ? "see DeliveryLine" : "n/a");
    values.put("specVersion", spec.map(a -> String.valueOf(a.version())).orElse("n/a"));
    values.put("pmReviewer", latestReviewer(workflowRunId, ArtifactType.SPEC.value()));
    values.put("devReviewer", devReviewer(workflowRunId));
    values.put("durationFormatted", resolveDurationFormatted(workflowRunId));
    return values;
  }

  private String latestReviewer(String workflowRunId, String artifactType) {
    return approvalReadPort
        .findLatestApprovedForArtifactLineage(workflowRunId, artifactType)
        .map(ApprovalSnapshot::actorIdentity)
        .orElse("unknown");
  }

  /** Dev reviewer = the pr-output approval, falling back to the implementation-plan approval. */
  private String devReviewer(String workflowRunId) {
    Optional<ApprovalSnapshot> prApproval =
        approvalReadPort.findLatestApprovedForArtifactLineage(
            workflowRunId, ArtifactType.PR_OUTPUT.value());
    if (prApproval.isPresent()) {
      return prApproval.get().actorIdentity();
    }
    return latestReviewer(workflowRunId, ArtifactType.IMPLEMENTATION_PLAN.value());
  }

  /**
   * Resolve {@code {prUrl}} from the active {@code github_pr} link's canonical {@code external_ref}
   * ({@code owner/repo#number}), reconstructed to {@code
   * https://github.com/owner/repo/pull/number}. Returns {@code null} (→ {@code n/a} fallback) when
   * no PR link exists (3.15 linkage is best-effort) or the ref is unparseable. Reconstruction
   * avoids a metadata round-trip; the single github.com pilot host (1:1 RepoConfig) makes it
   * deterministic.
   */
  private String resolvePrUrl(String workflowRunId) {
    Optional<IntegrationLink> prLink = integrationLinkService.findActiveGitHubPrLink(workflowRunId);
    if (prLink.isEmpty()) {
      log.warn(
          "syncCompletionToLinear no_github_pr_link workflowRunId={} ({prUrl} renders as fallback)",
          workflowRunId);
      return null;
    }
    String externalRef = prLink.get().externalRef();
    String url = githubPrUrl(externalRef);
    if (url == null) {
      log.warn(
          "syncCompletionToLinear unparseable_github_pr_ref workflowRunId={} externalRef={}",
          workflowRunId,
          MdcKeys.sanitizeForLog(externalRef));
    }
    return url;
  }

  private static String githubPrUrl(String externalRef) {
    if (externalRef == null) {
      return null;
    }
    int hash = externalRef.lastIndexOf('#');
    if (hash <= 0 || hash == externalRef.length() - 1) {
      return null;
    }
    String repo = externalRef.substring(0, hash);
    String number = externalRef.substring(hash + 1);
    if (!number.chars().allMatch(Character::isDigit)) {
      return null;
    }
    return "https://github.com/" + repo + "/pull/" + number;
  }

  /**
   * Cycle time = the run's latest event timestamp − its first event timestamp, formatted as a human
   * duration. Best-effort (Decision D5): any read failure (including {@code HISTORY_TOO_LARGE} on a
   * very long run) degrades to {@code n/a} rather than failing the sync.
   */
  private String resolveDurationFormatted(String workflowRunId) {
    try {
      Optional<WorkflowEventRecord> latest =
          workflowEventReadPort.findLatestByWorkflowRunPublicId(workflowRunId);
      List<WorkflowEventRecord> all =
          workflowEventReadPort.listByWorkflowRunPublicId(workflowRunId, null);
      if (latest.isEmpty() || all.isEmpty()) {
        return "n/a";
      }
      OffsetDateTime first = all.get(0).createdAt();
      OffsetDateTime last = latest.get().createdAt();
      if (first == null || last == null || last.isBefore(first)) {
        return "n/a";
      }
      return formatDuration(Duration.between(first, last));
    } catch (RuntimeException error) {
      log.warn(
          "syncCompletionToLinear duration_resolution_failed workflowRunId={} cause={}",
          workflowRunId,
          error.getClass().getSimpleName());
      return "n/a";
    }
  }

  private static String formatDuration(Duration duration) {
    long totalSeconds = Math.max(0L, duration.getSeconds());
    long hours = totalSeconds / 3600L;
    long minutes = (totalSeconds % 3600L) / 60L;
    long seconds = totalSeconds % 60L;
    StringBuilder out = new StringBuilder();
    if (hours > 0L) {
      out.append(hours).append("h ");
    }
    if (hours > 0L || minutes > 0L) {
      out.append(minutes).append("m ");
    }
    out.append(seconds).append('s');
    return out.toString();
  }

  /**
   * Idempotency basis (review 2026-06-11): the stable subset of the template values, EXCLUDING the
   * volatile {@code durationFormatted} (cycle time advances with every appended event) and {@code
   * prUrl} (the {@code github_pr} link can arrive late via best-effort 3.15 linkage).
   * Fingerprinting this basis instead of the full rendered body makes a re-sync robustly
   * at-most-once per run, rather than re-posting a duplicate whenever a volatile field drifts.
   * Order-stable via the {@link java.util.LinkedHashMap} insertion order in {@link
   * #templateValues}.
   */
  private static String stableFingerprintBasis(Map<String, String> values) {
    StringBuilder basis = new StringBuilder();
    values.forEach(
        (key, value) -> {
          if (!"durationFormatted".equals(key) && !"prUrl".equals(key)) {
            basis.append(key).append('=').append(value).append('\n');
          }
        });
    return basis.toString();
  }

  /**
   * Stable idempotency fingerprint (Decision D3): SHA-256 hex of {@link #stableFingerprintBasis}.
   */
  private static String fingerprint(String basis) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(basis.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException error) {
      // SHA-256 is mandatory in every Java distribution; defensive only.
      throw new IllegalStateException("SHA-256 not available", error);
    }
  }

  /**
   * Record a {@code linear.completionSyncFailed} event (AC4). The integration-scoped {@code
   * failureCategory} rides the {@code failureCategory} detail key (the event record's typed
   * failureCategory field is runner-scoped {@code FailureCategory}, so it stays {@code null} here —
   * mirroring {@code INTEGRATION_LINKED}). Reuses the existing allow-listed detail keys; no new
   * key/schema is introduced. Written within this method's (post-commit) transaction — it cannot
   * touch the already-committed {@code Completed} transition (AC4 / Trap T13).
   */
  private void recordSyncFailure(
      String workflowRunId,
      String ticketRef,
      String correlationId,
      String failureCategory,
      RuntimeException error) {
    log.warn(
        "syncCompletionToLinear post_failed workflowRunId={} ticketRef={} failureCategory={} "
            + "errorClass={}",
        workflowRunId,
        MdcKeys.sanitizeForLog(ticketRef),
        failureCategory,
        error.getClass().getSimpleName());
    Map<String, Object> details = new LinkedHashMap<>();
    details.put(WorkflowEventDetailKeys.LINEAR_TICKET_REFERENCE, ticketRef);
    details.put(WorkflowEventDetailKeys.FAILURE_CATEGORY, failureCategory);
    details.put(WorkflowEventDetailKeys.ERROR_CLASS, error.getClass().getSimpleName());
    if (correlationId != null && !correlationId.isBlank()) {
      details.put(WorkflowEventDetailKeys.CORRELATION_ID, correlationId);
    }
    try {
      workflowEventWritePort.append(
          new WorkflowEventRecord(
              PublicIdPrefixes.WORKFLOW_EVENT.next(),
              workflowRunId,
              WorkflowEventType.LINEAR_COMPLETION_SYNC_FAILED,
              null,
              null,
              ActorContext.SYSTEM.actorIdentity(),
              ActorType.SYSTEM,
              "linear_completion_sync_failed",
              null,
              false,
              OffsetDateTime.now(java.time.ZoneOffset.UTC),
              details));
    } catch (RuntimeException appendError) {
      // A double-fault (the failure-event write itself fails) must not escape the best-effort sync
      // (review 2026-06-11). The original post failure is already logged at WARN above; swallow the
      // append error with its own WARN so the contract "syncCompletionToLinear never throws" holds
      // end-to-end (the manual CLI path catches it too, but the auto-hook relies on it).
      log.warn(
          "syncCompletionToLinear failure_event_write_failed workflowRunId={} cause={}",
          workflowRunId,
          appendError.getClass().getSimpleName());
    }
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
