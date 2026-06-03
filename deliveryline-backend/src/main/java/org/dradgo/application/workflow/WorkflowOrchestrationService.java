package org.dradgo.application.workflow;

import java.util.Map;
import java.util.Objects;
import org.dradgo.application.artifact.ActorContext;
import org.dradgo.application.observability.MdcKeys;
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

  private final RunnerBroker runnerBroker;
  private final WorkflowTransitionService workflowTransitionService;
  private final WorkflowRunReadPort workflowRunReadPort;
  private final RunnerExecutionRecordPort runnerExecutionRecordPort;
  private final RunnerProperties runnerProperties;

  public WorkflowOrchestrationService(
      RunnerBroker runnerBroker,
      WorkflowTransitionService workflowTransitionService,
      WorkflowRunReadPort workflowRunReadPort,
      RunnerExecutionRecordPort runnerExecutionRecordPort,
      RunnerProperties runnerProperties) {
    this.runnerBroker = Objects.requireNonNull(runnerBroker, "runnerBroker");
    this.workflowTransitionService =
        Objects.requireNonNull(workflowTransitionService, "workflowTransitionService");
    this.workflowRunReadPort = Objects.requireNonNull(workflowRunReadPort, "workflowRunReadPort");
    this.runnerExecutionRecordPort =
        Objects.requireNonNull(runnerExecutionRecordPort, "runnerExecutionRecordPort");
    this.runnerProperties = Objects.requireNonNull(runnerProperties, "runnerProperties");
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
   * <p>Idempotent: a duplicate invocation (e.g. a replayed result) whose run already left {@code
   * Investigating} surfaces {@code ILLEGAL_TRANSITION}, which is swallowed so the spec-ready
   * transition fires at most once.
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
          log.warn(
              "onSpecStageSucceeded swallowed ILLEGAL_TRANSITION workflowRunId={} "
                  + "runnerExecutionId={} reason={}",
              workflowRunId,
              runnerExecutionId,
              error.getMessage());
          return;
        }
        throw error;
      }
    } finally {
      MdcKeys.endScope(MdcKeys.RUNNER_EXECUTION_ID, priorRexMdc);
      MdcKeys.endScope(MdcKeys.WORKFLOW_RUN_ID, priorRunMdc);
    }
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
