package org.dradgo.application.recovery;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.dradgo.application.artifact.ActorContext;
import org.dradgo.application.artifact.spi.ArtifactOperationPort;
import org.dradgo.application.idempotency.IdempotencyKeyValidator;
import org.dradgo.application.observability.MdcKeys;
import org.dradgo.application.recovery.spi.RecoveryActionRecordPort;
import org.dradgo.application.recovery.spi.RecoveryActionSnapshot;
import org.dradgo.application.recovery.spi.RecoveryActionWriteCommand;
import org.dradgo.application.runner.RunnerBroker;
import org.dradgo.application.runner.RunnerDispatchResult;
import org.dradgo.application.runner.spi.RunnerExecutionRecordPort;
import org.dradgo.application.runner.spi.RunnerExecutionSnapshot;
import org.dradgo.application.workflow.WorkflowCommandService;
import org.dradgo.application.workflow.commands.RetryWorkflowCommand;
import org.dradgo.application.workflow.spi.WorkflowEventReadPort;
import org.dradgo.application.workflow.spi.WorkflowEventRecord;
import org.dradgo.application.workflow.spi.WorkflowEventWritePort;
import org.dradgo.application.workflow.spi.WorkflowRunReadPort;
import org.dradgo.application.workflow.spi.WorkflowRunSnapshot;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.id.PublicIdPrefixes;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.RunnerExecutionStatus;
import org.dradgo.domain.registry.RunnerStage;
import org.dradgo.domain.registry.WorkflowEventDetailKeys;
import org.dradgo.domain.registry.WorkflowEventType;
import org.dradgo.domain.registry.WorkflowState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Application service for CLI minimum-viable recovery (story 1.18 baseline).
 *
 * <p><strong>Scope-protected by ArchUnit:</strong> exposes <em>exactly</em> two public methods —
 * {@link #retry(String, String, ActorContext, String)} and {@link #describeFailure(String)}. The
 * rule {@code RECOVERY_SERVICE_IS_SCOPE_PROTECTED} in {@code ArchitectureRuleCatalog} fails the
 * build if any other public method name is added without an Epic-4 story justifying it.
 *
 * <p>Methods <strong>not</strong> in story 1.18 (Epic 4 will add): {@code resume(...)}, {@code
 * rerun(...)}, {@code reconcile(...)}, {@code pause(...)}, {@code classifyFailure(...)}, {@code
 * takeover(...)}.
 *
 * <p><strong>Idempotency-key namespacing:</strong> the user-supplied {@code idempotencyKey} flows
 * through three idempotency surfaces — {@link WorkflowCommandService#retryWorkflow} (workflow state
 * transition), {@link RunnerBroker#dispatch} (runner dispatch), and {@link
 * RecoveryActionRecordPort} (recovery_actions row). To prevent collisions on the broker's
 * fingerprint check ({@code workflowRunId|stage|contextBundleVersion}), the broker key is derived
 * by appending a stable {@code :runner} suffix. The recovery-action key is the bare key.
 *
 * <p><strong>State-mutation invariant:</strong> all workflow state changes route through {@link
 * WorkflowCommandService#retryWorkflow} (which calls {@code WorkflowTransitionService}). The
 * ArchUnit rule {@code ONLY_WORKFLOW_TRANSITION_SERVICE_MAY_MUTATE_WORKFLOW_STATE} forbids any
 * other path; this service never calls {@code WorkflowRunStatePort.updateCurrentState} directly.
 *
 * <p><strong>Concurrent-retry contract (review F532 + batch-3 B16/A3 + B7/E16):</strong> two
 * retries with the same idempotency key racing past the pre-insert replay check both attempt the
 * prep transaction; the loser sees {@code IDEMPOTENCY_KEY_CONFLICT} from the {@code
 * recovery_actions} unique constraint (or from the inner {@code
 * WorkflowCommandService.retryWorkflow} idempotency record). On that conflict we re-read the prior
 * {@code recovery_actions} row: if it is now bound to the same run AND already {@code
 * result_status='succeeded'} AND carries a non-null {@code resulting_event_id}, we return the
 * documented replay {@link RetryRecoveryResult} instead of surfacing the conflict. Pending / failed
 * prior attempts on the same run propagate the conflict unchanged (matches the same-run,
 * non-succeeded contract from review F2). Different-run prior rows also propagate. Missing or
 * null-event-id rows propagate (defensive — the FK is {@code ON DELETE SET NULL}; a link-less row
 * cannot back a replay).
 *
 * <p><strong>Ordering invariant.</strong> Inside the prep transaction the call sequence is {@code
 * workflowCommandService.retryWorkflow} → {@code workflowEventWritePort.append} → {@code
 * recoveryActionRecordPort.insert}. The inner {@code retryWorkflow} writes its {@code
 * idempotency_records} row first, so if thread B's conflict comes from that surface (rather than
 * the {@code recovery_actions} unique constraint), thread A's {@code recovery_actions} insert has
 * not happened yet — thread B's re-read sees {@code Optional.empty()} and propagates the conflict
 * by design. Operators who want a guaranteed replay should retry the call AFTER the original
 * returns; the conflict surface is a best-effort fast-path for already-completed prior attempts,
 * not a synchronization primitive.
 *
 * <p><strong>Correlation-id echo on replay.</strong> The returned {@link RetryRecoveryResult}
 * carries the CURRENT call's {@code actor.correlationId()}, not the original winning attempt's
 * stored correlation id. Audit-trace joins by correlation id may therefore split across two ids:
 * the current call's id reaches the CLI stdout / structured log, while the persisted {@code
 * recovery.retried} event row carries the winning attempt's id. This is intentional — the current
 * call's id is what the operator's tooling already attached to the request and what structured logs
 * in the loser's call carry. To find the originating attempt's id, follow the returned {@code
 * recoveryRetriedEventPublicId} through the event store.
 *
 * <p><strong>Terminal-result-status contract (review F526):</strong> a successful {@code retry}
 * result implies the {@code recovery_actions} row has reached {@code result_status='succeeded'}
 * before the method returns. If the post-dispatch {@code markSucceeded} flip fails, the row stays
 * at {@code pending} and the runner is already running — we refuse to lie about audit terminality
 * and raise {@code INTERNAL_ERROR} carrying the new-runner id, recovery-action id, and root cause
 * so the operator can reconcile. On the dispatch-failure path the symmetric guarantee is provided
 * by the {@code recovery.dispatchFailed} audit event (it carries {@code compensationFailed=true}
 * when the {@code markFailed} compensation itself fails).
 *
 * <p><strong>Degraded-state contract (review F1 + dispatch-failure audit):</strong> the {@code
 * retry} flow runs the workflow-state transition, the {@code recovery.retried} event append, and
 * the {@code recovery_actions} insert inside a single {@code REQUIRES_NEW} transaction template
 * that commits BEFORE {@link RunnerBroker#dispatch} is invoked. If dispatch then fails, two fresh
 * {@code REQUIRES_NEW} transactions run: (1) the {@code recovery_actions} row is flipped to {@code
 * result_status = failed}; (2) a {@link WorkflowEventType#RECOVERY_DISPATCH_FAILED
 * recovery.dispatchFailed} event is appended carrying {@code errorCode}, {@code errorClass}, {@code
 * failedStage}, {@code recoveryActionId}, {@code recoveryRetriedEventId}, {@code idempotencyKey},
 * and (when present) {@code correlationId} and {@code reason}. The append-only invariant (NFR4) is
 * preserved — the already-committed {@code recovery.retried} event is never mutated; the typed
 * broker error reaches the audit trail through this second append. Failures appending the
 * dispatch-failed event are suppressed onto the original broker error so the caller still sees the
 * real cause. The workflow stays in {@code Executing} without a running runner; the next retry call
 * (or the broker's startup-recovery scan) can correct the dangling state.
 */
@Service
public class RecoveryService {

  private static final Logger log = LoggerFactory.getLogger(RecoveryService.class);

  static final String NEXT_SAFE_ACTION_RETRY = "retry";
  static final String NEXT_SAFE_ACTION_AWAIT_OUTCOME = "await_outcome";
  static final String NEXT_SAFE_ACTION_VIEW_ONLY = "view_only";
  static final String NEXT_SAFE_ACTION_AWAIT_MANUAL_RECONCILIATION = "await_manual_reconciliation";

  static final String ACTION_TYPE_RETRY = "retry";
  static final String RESULT_STATUS_PENDING = "pending";
  static final String RESULT_STATUS_SUCCEEDED = "succeeded";

  private static final List<RunnerExecutionStatus> FAILED_RUNNER_STATUSES =
      List.of(
          RunnerExecutionStatus.FAILED,
          RunnerExecutionStatus.TIMED_OUT,
          RunnerExecutionStatus.ORPHANED);

  private static final List<RunnerExecutionStatus> ALL_RUNNER_STATUSES =
      List.of(
          RunnerExecutionStatus.PENDING,
          RunnerExecutionStatus.RUNNING,
          RunnerExecutionStatus.COMPLETED,
          RunnerExecutionStatus.FAILED,
          RunnerExecutionStatus.TIMED_OUT,
          RunnerExecutionStatus.ORPHANED);

  private static final List<WorkflowState> TERMINAL_STATES =
      List.of(WorkflowState.COMPLETED, WorkflowState.TAKEN_OVER, WorkflowState.RECONCILED);

  private final WorkflowRunReadPort workflowRunReadPort;
  private final WorkflowEventReadPort workflowEventReadPort;
  private final RunnerExecutionRecordPort runnerExecutionRecordPort;
  private final ArtifactOperationPort artifactOperationPort;
  private final WorkflowCommandService workflowCommandService;
  private final RunnerBroker runnerBroker;
  private final WorkflowEventWritePort workflowEventWritePort;
  private final RecoveryActionRecordPort recoveryActionRecordPort;
  private final IdempotencyKeyValidator idempotencyKeyValidator;
  private final Clock clock;
  private final TransactionTemplate retryPrepTransactionTemplate;
  private final TransactionTemplate resultStatusTransactionTemplate;

  @Autowired
  public RecoveryService(
      WorkflowRunReadPort workflowRunReadPort,
      WorkflowEventReadPort workflowEventReadPort,
      RunnerExecutionRecordPort runnerExecutionRecordPort,
      ArtifactOperationPort artifactOperationPort,
      WorkflowCommandService workflowCommandService,
      RunnerBroker runnerBroker,
      WorkflowEventWritePort workflowEventWritePort,
      RecoveryActionRecordPort recoveryActionRecordPort,
      IdempotencyKeyValidator idempotencyKeyValidator,
      PlatformTransactionManager transactionManager) {
    this(
        workflowRunReadPort,
        workflowEventReadPort,
        runnerExecutionRecordPort,
        artifactOperationPort,
        workflowCommandService,
        runnerBroker,
        workflowEventWritePort,
        recoveryActionRecordPort,
        idempotencyKeyValidator,
        Clock.systemUTC(),
        requiresNewTemplate(transactionManager),
        requiresNewTemplate(transactionManager));
  }

  RecoveryService(
      WorkflowRunReadPort workflowRunReadPort,
      WorkflowEventReadPort workflowEventReadPort,
      RunnerExecutionRecordPort runnerExecutionRecordPort,
      ArtifactOperationPort artifactOperationPort,
      WorkflowCommandService workflowCommandService,
      RunnerBroker runnerBroker,
      WorkflowEventWritePort workflowEventWritePort,
      RecoveryActionRecordPort recoveryActionRecordPort,
      IdempotencyKeyValidator idempotencyKeyValidator,
      Clock clock,
      TransactionTemplate retryPrepTransactionTemplate,
      TransactionTemplate resultStatusTransactionTemplate) {
    this.workflowRunReadPort = Objects.requireNonNull(workflowRunReadPort, "workflowRunReadPort");
    this.workflowEventReadPort =
        Objects.requireNonNull(workflowEventReadPort, "workflowEventReadPort");
    this.runnerExecutionRecordPort =
        Objects.requireNonNull(runnerExecutionRecordPort, "runnerExecutionRecordPort");
    this.artifactOperationPort =
        Objects.requireNonNull(artifactOperationPort, "artifactOperationPort");
    this.workflowCommandService =
        Objects.requireNonNull(workflowCommandService, "workflowCommandService");
    this.runnerBroker = Objects.requireNonNull(runnerBroker, "runnerBroker");
    this.workflowEventWritePort =
        Objects.requireNonNull(workflowEventWritePort, "workflowEventWritePort");
    this.recoveryActionRecordPort =
        Objects.requireNonNull(recoveryActionRecordPort, "recoveryActionRecordPort");
    this.idempotencyKeyValidator =
        Objects.requireNonNull(idempotencyKeyValidator, "idempotencyKeyValidator");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.retryPrepTransactionTemplate =
        Objects.requireNonNull(retryPrepTransactionTemplate, "retryPrepTransactionTemplate");
    this.resultStatusTransactionTemplate =
        Objects.requireNonNull(resultStatusTransactionTemplate, "resultStatusTransactionTemplate");
  }

  private static TransactionTemplate requiresNewTemplate(
      PlatformTransactionManager transactionManager) {
    TransactionTemplate template = new TransactionTemplate(transactionManager);
    template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    return template;
  }

  public RetryRecoveryResult retry(
      String workflowRunId, String idempotencyKey, ActorContext actor, String reasonText) {
    PublicIdPrefixes.require(workflowRunId, PublicIdPrefixes.WORKFLOW_RUN);
    Objects.requireNonNull(actor, "actor");
    String priorRunMdc = MdcKeys.beginScope(MdcKeys.WORKFLOW_RUN_ID, workflowRunId);
    try {
      String validatedIdempotencyKey = idempotencyKeyValidator.requireValid(idempotencyKey);
      long start = System.nanoTime();
      log.info(
          "recovery retry start workflowRunId={} idempotencyKey={} actorIdentity={}",
          workflowRunId,
          validatedIdempotencyKey,
          actor.actorIdentity());

      // Step 1 — replay detection. Treat a prior recovery_actions row as a replay only when it
      // belongs to the SAME run AND already reached the `succeeded` terminal. A row for a
      // different run, or a prior `pending` / `failed` attempt on this run, surfaces as
      // IDEMPOTENCY_KEY_CONFLICT so the operator either picks a fresh key or rolls back through
      // the documented degraded-state path (review F2).
      Optional<RecoveryActionSnapshot> priorAction =
          recoveryActionRecordPort.findByIdempotencyKey(validatedIdempotencyKey);
      if (priorAction.isPresent()) {
        RecoveryActionSnapshot prior = priorAction.get();
        if (!workflowRunId.equals(prior.workflowRunPublicId())) {
          Map<String, Object> details = new LinkedHashMap<>();
          details.put("idempotencyKey", validatedIdempotencyKey);
          details.put("requestedRunId", workflowRunId);
          details.put("priorRunId", prior.workflowRunPublicId());
          details.put("reason", "idempotency_key_bound_to_different_run");
          log.warn(
              "recovery retry rejected workflowRunId={} reason=idempotency_key_bound_to_different_run priorRunId={}",
              workflowRunId,
              prior.workflowRunPublicId());
          throw new DomainException(
              DomainErrorCode.IDEMPOTENCY_KEY_CONFLICT,
              "Idempotency key is already bound to a different workflow run (priorRunId="
                  + prior.workflowRunPublicId()
                  + ")",
              details);
        }
        if (!RESULT_STATUS_SUCCEEDED.equals(prior.resultStatus())) {
          Map<String, Object> details = new LinkedHashMap<>();
          details.put("idempotencyKey", validatedIdempotencyKey);
          details.put("runId", workflowRunId);
          details.put("priorResultStatus", prior.resultStatus());
          details.put("reason", "idempotency_key_used_by_non_succeeded_attempt");
          log.warn(
              "recovery retry rejected workflowRunId={} reason=idempotency_key_used_by_non_succeeded_attempt priorResultStatus={}",
              workflowRunId,
              prior.resultStatus());
          throw new DomainException(
              DomainErrorCode.IDEMPOTENCY_KEY_CONFLICT,
              "Idempotency key was used by a prior non-succeeded recovery attempt (resultStatus="
                  + prior.resultStatus()
                  + "); use a fresh key for a new attempt",
              details);
        }
        log.info(
            "recovery retry replay workflowRunId={} recoveryActionId={} idempotencyKey={}",
            workflowRunId,
            prior.publicId(),
            validatedIdempotencyKey);
        return new RetryRecoveryResult(
            prior.publicId(),
            prior.resultingEventPublicId(),
            null,
            null,
            actor.correlationId(),
            true);
      }

      // Step 2 — current-state guard. retry is only meaningful from Failed. Read run snapshot in
      // the prep tx below to keep the read coherent with the transition; this check happens here
      // to short-circuit before consuming any state.
      WorkflowRunSnapshot run =
          workflowRunReadPort
              .findByPublicId(workflowRunId)
              .orElseThrow(() -> runNotFound(workflowRunId));
      if (run.currentState() != WorkflowState.FAILED) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("runId", workflowRunId);
        details.put("currentState", run.currentState().value());
        log.warn(
            "recovery retry rejected workflowRunId={} currentState={} reason=not_failed",
            workflowRunId,
            run.currentState().value());
        throw new DomainException(
            DomainErrorCode.RETRY_NOT_APPLICABLE,
            "Retry is only applicable to runs in state Failed; current state is "
                + run.currentState().value(),
            details);
      }

      // Step 3 — locate the failure event. AC2/AC9 require triggering_event_id to link the audit
      // trail to the workflow.stateChanged → Failed event; a Failed run without that event row
      // can only have arrived there through a malformed fixture, and we refuse to invent a
      // link-less audit entry (review F3).
      WorkflowEventRecord failureEvent =
          workflowEventReadPort
              .findLatestFailureEvent(workflowRunId)
              .orElseThrow(
                  () -> {
                    Map<String, Object> details = new LinkedHashMap<>();
                    details.put("runId", workflowRunId);
                    details.put("reason", "no_failure_event_to_link");
                    return new DomainException(
                        DomainErrorCode.RETRY_NOT_APPLICABLE,
                        "Retry requires a workflow.stateChanged → Failed event to link the audit trail; "
                            + "none found for run "
                            + workflowRunId,
                        details);
                  });

      // Step 4 — locate the most recent failed runner_execution to know which stage to redispatch.
      RunnerExecutionSnapshot lastFailedRunner =
          findLastFailedRunner(workflowRunId)
              .orElseThrow(
                  () -> {
                    Map<String, Object> details = new LinkedHashMap<>();
                    details.put("runId", workflowRunId);
                    details.put("reason", "no_failed_runner_execution_to_redispatch");
                    return new DomainException(
                        DomainErrorCode.RETRY_NOT_APPLICABLE,
                        "Retry requires a prior failed runner_execution to identify the stage to redispatch; "
                            + "none found for run "
                            + workflowRunId,
                        details);
                  });
      RunnerStage failedStage = lastFailedRunner.stage();

      // Step 5 — prep work runs inside a REQUIRES_NEW transaction so the audit trail (state
      // transition + recovery.retried event + recovery_actions row) is durable BEFORE the broker
      // dispatch is attempted. If the broker then fails, the recovery_actions row survives and
      // gets flipped to `failed` in a fresh tx so operators can see the attempt in audit history
      // (review F1).
      String correlationId = actor.correlationId();
      String effectiveReason = resolveReasonText(reasonText, failedStage);
      RetryPrep prep;
      try {
        prep =
            retryPrepTransactionTemplate.execute(
                status ->
                    performRetryPrep(
                        workflowRunId,
                        validatedIdempotencyKey,
                        actor,
                        failedStage,
                        failureEvent,
                        effectiveReason,
                        correlationId));
      } catch (DomainException prepError) {
        // Concurrent-retry race (review F532): the pre-insert replay check at the top of this
        // method saw an empty result, but a sibling thread won the recovery_actions insert
        // (or the inner WorkflowCommandService.retryWorkflow's idempotency record) between
        // our check and our prep tx — surfacing IDEMPOTENCY_KEY_CONFLICT. Re-read the prior
        // row; if it is now bound to the same run AND already succeeded, return the documented
        // replay shape rather than punishing the loser with an idempotency conflict. Pending
        // or failed prior attempts on this run propagate the conflict (the same-run, non-
        // succeeded contract from review F2). Different-run prior rows propagate the conflict
        // unchanged (F2 again).
        if (prepError.errorCode() == DomainErrorCode.IDEMPOTENCY_KEY_CONFLICT) {
          Optional<RetryRecoveryResult> raceReplay =
              resolveConcurrentReplay(workflowRunId, validatedIdempotencyKey, actor);
          if (raceReplay.isPresent()) {
            return raceReplay.get();
          }
        }
        throw prepError;
      }

      // Step 6 — dispatch the runner OUTSIDE any transaction so the audit trail above is durable
      // before any side effects. Namespace the broker key with ":runner" so the broker's
      // idempotency_records row cannot collide with the recovery action's idempotency_records row.
      String dispatchKey = validatedIdempotencyKey + ":runner";
      RunnerDispatchResult dispatchResult;
      try {
        dispatchResult = runnerBroker.dispatch(workflowRunId, failedStage, dispatchKey, actor);
      } catch (RuntimeException error) {
        boolean compensationFailed = false;
        try {
          resultStatusTransactionTemplate.executeWithoutResult(
              status -> recoveryActionRecordPort.markFailed(validatedIdempotencyKey));
        } catch (RuntimeException compensationError) {
          compensationFailed = true;
          error.addSuppressed(compensationError);
        }
        // Append-only audit: stamp the typed broker error into a second event so operators
        // reading `history` see the dispatch failure. Mutating the already-committed
        // `recovery.retried` event would violate NFR4; instead we add `recovery.dispatchFailed`
        // in its own REQUIRES_NEW tx so the audit row survives even when the outer caller
        // unwinds. Failures appending the event are suppressed onto the broker error so the
        // CLI surface still reports the real cause.
        try {
          appendDispatchFailedEvent(
              workflowRunId,
              failedStage,
              prep,
              validatedIdempotencyKey,
              correlationId,
              effectiveReason,
              actor,
              error,
              compensationFailed);
        } catch (RuntimeException auditError) {
          error.addSuppressed(auditError);
          // Audit-event store outage on the dispatch-failure path: the recovery_actions row
          // is flipped (or compensationFailed), the broker error is re-thrown, but the
          // `recovery.dispatchFailed` event never landed — the `history` CLI surface cannot
          // show this attempt. Escalate to ERROR with EVERY audit field stamped into the
          // structured log so operator log-scraping can reconstruct the missing event.
          // (review D2 / 2026-05-16)
          String dispatchErrorCode =
              (error instanceof DomainException d) ? d.errorCode().value() : "RUNTIME_ERROR";
          log.error(
              "recovery retry dispatch-failed event append failed workflowRunId={} "
                  + "recoveryActionId={} recoveryRetriedEventId={} idempotencyKey={} "
                  + "failedStage={} errorCode={} errorClass={} correlationId={} "
                  + "compensationFailed={} auditErrorClass={} — audit event missing; "
                  + "structured log is the only audit trail for this attempt",
              workflowRunId,
              prep.recoveryActionPublicId,
              prep.recoveryRetriedEventPublicId,
              validatedIdempotencyKey,
              failedStage.value(),
              dispatchErrorCode,
              error.getClass().getSimpleName(),
              correlationId,
              compensationFailed,
              auditError.getClass().getSimpleName());
        }
        log.warn(
            "recovery retry dispatch failed workflowRunId={} runnerStage={} errorClass={} — workflow state stays Executing without a runner; operator action required",
            workflowRunId,
            failedStage.value(),
            error.getClass().getSimpleName());
        throw error;
      }

      String newRunnerExecutionId = dispatchResult.handle().runnerExecutionId();
      // Story 3.2a AC10 / Trap T13: surface the runner.dispatched event id (docker path) as the
      // retry audit anchor for the NEW dispatch. Null on the mock path (which emits runner.started
      // inside the dispatch transaction) and on a replayed dispatch.
      String runnerDispatchedEventPublicId =
          (dispatchResult instanceof RunnerDispatchResult.Dispatched dispatched)
              ? dispatched.runnerDispatchedEventPublicId()
              : null;
      try {
        resultStatusTransactionTemplate.executeWithoutResult(
            status -> recoveryActionRecordPort.markSucceeded(validatedIdempotencyKey));
      } catch (RuntimeException completionError) {
        // Story 1.18 AC9 requires recovery_actions.result_status to reach a terminal value
        // (succeeded | failed) — leaving the row at `pending` while reporting CLI success
        // would lie about the audit state. Surface a typed INTERNAL_ERROR so operators see
        // the inconsistency and the CLI exit code reflects the degraded state; the runner is
        // already dispatched, so the suppressed completionError carries the root cause for
        // triage. (review F526)
        log.error(
            "recovery retry completion status update failed workflowRunId={} recoveryActionId={} idempotencyKey={} newRunnerExecutionId={} errorClass={} — runner dispatched but recovery_actions row stays pending; operator reconciliation required",
            workflowRunId,
            prep.recoveryActionPublicId,
            validatedIdempotencyKey,
            newRunnerExecutionId,
            completionError.getClass().getSimpleName());
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("runId", workflowRunId);
        details.put("recoveryActionId", prep.recoveryActionPublicId);
        details.put("idempotencyKey", validatedIdempotencyKey);
        details.put("newRunnerExecutionId", newRunnerExecutionId);
        details.put("reason", "result_status_flip_failed_after_dispatch");
        DomainException terminal =
            new DomainException(
                DomainErrorCode.INTERNAL_ERROR,
                "Runner dispatched but recovery_actions.result_status flip to succeeded failed; "
                    + "row remains pending. Operator reconciliation required.",
                details);
        terminal.addSuppressed(completionError);
        throw terminal;
      }

      long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
      log.info(
          "recovery retry success workflowRunId={} recoveryActionId={} newRunnerExecutionId={} durationMs={}",
          workflowRunId,
          prep.recoveryActionPublicId,
          newRunnerExecutionId,
          elapsedMs);
      return new RetryRecoveryResult(
          prep.recoveryActionPublicId,
          prep.recoveryRetriedEventPublicId,
          runnerDispatchedEventPublicId,
          newRunnerExecutionId,
          correlationId,
          false);
    } finally {
      MdcKeys.endScope(MdcKeys.WORKFLOW_RUN_ID, priorRunMdc);
    }
  }

  private RetryPrep performRetryPrep(
      String workflowRunId,
      String idempotencyKey,
      ActorContext actor,
      RunnerStage failedStage,
      WorkflowEventRecord failureEvent,
      String effectiveReason,
      String correlationId) {
    RetryWorkflowCommand command =
        new RetryWorkflowCommand(
            workflowRunId,
            actor.actorIdentity(),
            actor.actorType(),
            idempotencyKey,
            correlationId,
            effectiveReason);
    workflowCommandService.retryWorkflow(command);

    String recoveryEventPublicId = PublicIdPrefixes.WORKFLOW_EVENT.next();
    Map<String, Object> eventDetails = new LinkedHashMap<>();
    eventDetails.put(WorkflowEventDetailKeys.FAILED_STAGE, failedStage.value());
    eventDetails.put(WorkflowEventDetailKeys.TRIGGERING_EVENT_ID, failureEvent.publicId());
    eventDetails.put(WorkflowEventDetailKeys.IDEMPOTENCY_KEY, idempotencyKey);
    if (correlationId != null) {
      eventDetails.put(WorkflowEventDetailKeys.CORRELATION_ID, correlationId);
    }
    // Surface the operator-supplied reason in the audit details so it survives in
    // workflow_events.details even though `reason` is also stamped on the event row itself
    // (review F6). Truncation matches RetryWorkflowCommand's @Size(max = 512) so a downstream
    // schema gate would catch any drift.
    if (effectiveReason != null && !effectiveReason.isBlank()) {
      eventDetails.put(WorkflowEventDetailKeys.REASON, effectiveReason);
    }
    workflowEventWritePort.append(
        new WorkflowEventRecord(
            recoveryEventPublicId,
            workflowRunId,
            WorkflowEventType.RECOVERY_RETRIED,
            WorkflowState.FAILED,
            WorkflowState.EXECUTING,
            actor.actorIdentity(),
            actor.actorType(),
            effectiveReason,
            null,
            true,
            OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC),
            eventDetails));

    RecoveryActionSnapshot recoveryAction =
        recoveryActionRecordPort.insert(
            new RecoveryActionWriteCommand(
                workflowRunId,
                ACTION_TYPE_RETRY,
                failureEvent.publicId(),
                recoveryEventPublicId,
                actor.actorIdentity(),
                actor.actorType(),
                idempotencyKey,
                RESULT_STATUS_PENDING));

    return new RetryPrep(recoveryAction.publicId(), recoveryEventPublicId);
  }

  private void appendDispatchFailedEvent(
      String workflowRunId,
      RunnerStage failedStage,
      RetryPrep prep,
      String idempotencyKey,
      String correlationId,
      String effectiveReason,
      ActorContext actor,
      RuntimeException error,
      boolean compensationFailed) {
    String errorCode =
        (error instanceof DomainException domainError)
            ? domainError.errorCode().value()
            : "RUNTIME_ERROR";
    String errorClass = error.getClass().getSimpleName();
    String dispatchFailedReason = "broker dispatch failed: " + errorCode;
    String dispatchFailedEventPublicId = PublicIdPrefixes.WORKFLOW_EVENT.next();
    Map<String, Object> eventDetails = new LinkedHashMap<>();
    eventDetails.put(WorkflowEventDetailKeys.FAILED_STAGE, failedStage.value());
    eventDetails.put(WorkflowEventDetailKeys.RECOVERY_ACTION_ID, prep.recoveryActionPublicId);
    eventDetails.put(
        WorkflowEventDetailKeys.RECOVERY_RETRIED_EVENT_ID, prep.recoveryRetriedEventPublicId);
    eventDetails.put(WorkflowEventDetailKeys.IDEMPOTENCY_KEY, idempotencyKey);
    eventDetails.put(WorkflowEventDetailKeys.ERROR_CODE, errorCode);
    eventDetails.put(WorkflowEventDetailKeys.ERROR_CLASS, errorClass);
    if (correlationId != null) {
      eventDetails.put(WorkflowEventDetailKeys.CORRELATION_ID, correlationId);
    }
    if (effectiveReason != null && !effectiveReason.isBlank()) {
      eventDetails.put(WorkflowEventDetailKeys.REASON, effectiveReason);
    }
    if (compensationFailed) {
      eventDetails.put(WorkflowEventDetailKeys.COMPENSATION_FAILED, true);
    }
    resultStatusTransactionTemplate.executeWithoutResult(
        status -> {
          workflowEventWritePort.append(
              new WorkflowEventRecord(
                  dispatchFailedEventPublicId,
                  workflowRunId,
                  WorkflowEventType.RECOVERY_DISPATCH_FAILED,
                  WorkflowState.EXECUTING,
                  WorkflowState.EXECUTING,
                  actor.actorIdentity(),
                  actor.actorType(),
                  dispatchFailedReason,
                  null,
                  true,
                  OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC),
                  eventDetails));
          log.info(
              "recovery dispatch-failed event appended workflowRunId={} dispatchFailedEventId={} errorCode={} errorClass={}",
              workflowRunId,
              dispatchFailedEventPublicId,
              errorCode,
              errorClass);
        });
  }

  private Optional<RetryRecoveryResult> resolveConcurrentReplay(
      String workflowRunId, String idempotencyKey, ActorContext actor) {
    Optional<RecoveryActionSnapshot> prior =
        recoveryActionRecordPort.findByIdempotencyKey(idempotencyKey);
    if (prior.isEmpty()) {
      // The conflict came from somewhere other than recovery_actions (e.g. the inner
      // retryWorkflow's idempotency_records row), and the sibling thread either has not
      // committed yet or rolled back. Surface the original conflict.
      return Optional.empty();
    }
    RecoveryActionSnapshot snapshot = prior.get();
    if (!workflowRunId.equals(snapshot.workflowRunPublicId())) {
      return Optional.empty();
    }
    if (!RESULT_STATUS_SUCCEEDED.equals(snapshot.resultStatus())) {
      return Optional.empty();
    }
    // Defensive: recovery_actions.resulting_event_id FK is `ON DELETE SET NULL`, so a
    // succeeded prior row could carry a null event public id (the linked event was archived /
    // deleted). Treat that as not-a-valid-replay and propagate the conflict — emitting a
    // replay result with a null event id would NPE callers that assume non-null on replay.
    // (review E13 / 2026-05-16)
    if (snapshot.resultingEventPublicId() == null) {
      log.warn(
          "recovery retry replay-on-conflict refused workflowRunId={} recoveryActionId={} reason=missing_resulting_event_id",
          workflowRunId,
          snapshot.publicId());
      return Optional.empty();
    }
    log.info(
        "recovery retry replay-on-conflict workflowRunId={} recoveryActionId={} idempotencyKey={} priorResultStatus={}",
        workflowRunId,
        snapshot.publicId(),
        idempotencyKey,
        snapshot.resultStatus());
    return Optional.of(
        new RetryRecoveryResult(
            snapshot.publicId(),
            snapshot.resultingEventPublicId(),
            null,
            null,
            actor.correlationId(),
            true));
  }

  private static String resolveReasonText(String reasonText, RunnerStage failedStage) {
    if (reasonText != null && !reasonText.isBlank()) {
      return reasonText;
    }
    return "retry from failed " + failedStage.value();
  }

  @Transactional(readOnly = true)
  public FailureDescription describeFailure(String workflowRunId) {
    PublicIdPrefixes.require(workflowRunId, PublicIdPrefixes.WORKFLOW_RUN);
    String priorRunMdc = MdcKeys.beginScope(MdcKeys.WORKFLOW_RUN_ID, workflowRunId);
    try {
      log.info("describe failure workflowRunId={}", workflowRunId);
      WorkflowRunSnapshot run =
          workflowRunReadPort
              .findByPublicId(workflowRunId)
              .orElseThrow(() -> runNotFound(workflowRunId));

      String diagnosticReferenceCorrelationId =
          workflowEventReadPort.findLatestCorrelationId(workflowRunId).orElse(null);

      if (run.currentState() != WorkflowState.FAILED) {
        String safeAction =
            TERMINAL_STATES.contains(run.currentState())
                ? NEXT_SAFE_ACTION_VIEW_ONLY
                : NEXT_SAFE_ACTION_AWAIT_OUTCOME;
        log.info(
            "describe failure resolved workflowRunId={} currentState={} failedStage=null nextSafeAction={}",
            workflowRunId,
            run.currentState().value(),
            safeAction);
        return new FailureDescription(
            workflowRunId,
            run.currentState(),
            null,
            null,
            null,
            null,
            null,
            safeAction,
            diagnosticReferenceCorrelationId);
      }

      Optional<WorkflowEventRecord> failureEvent =
          workflowEventReadPort.findLatestFailureEvent(workflowRunId);
      Optional<RunnerExecutionSnapshot> lastFailedRunner = findLastFailedRunner(workflowRunId);
      Optional<RunnerExecutionSnapshot> latestRunner = findLatestRunner(workflowRunId);

      String failedStage = lastFailedRunner.map(snapshot -> snapshot.stage().value()).orElse(null);
      String lastSuccessfulStage =
          failureEvent
              .map(event -> event.priorState() == null ? null : event.priorState().value())
              .orElse(null);
      // Normalize timestamps to UTC for byte-equal text/JSON output across the CLI surfaces.
      // The recovery.retried event already normalizes via withOffsetSameInstant(ZoneOffset.UTC)
      // at append time; describing a Failed run after the fact should produce the same offset
      // regardless of which session timezone the JPA mapper hands us. (review E8 / 2026-05-16)
      OffsetDateTime failureTimestamp =
          failureEvent
              .map(WorkflowEventRecord::createdAt)
              .map(t -> t.withOffsetSameInstant(ZoneOffset.UTC))
              .orElse(null);
      String failureCategory =
          failureEvent
              .map(
                  event -> event.failureCategory() == null ? null : event.failureCategory().value())
              .orElse(null);
      // AC5: lastActivityTimestamp is the last_activity_at of the most recent runner_executions
      // row for the run REGARDLESS of status, falling back to the failure timestamp when no
      // runner_execution exists (review F5). Also normalized to UTC (review E8).
      OffsetDateTime lastActivityTimestamp =
          latestRunner
              .map(RunnerExecutionSnapshot::lastActivityAt)
              .map(t -> t.withOffsetSameInstant(ZoneOffset.UTC))
              .orElse(failureTimestamp);

      // Match the retry path's preconditions so the CLI never recommends `retry` on a Failed
      // run that retry would itself reject. `retry` throws RETRY_NOT_APPLICABLE when either the
      // failure event OR the last failed runner_execution is missing (Steps 3 and 4); recommend
      // `await_manual_reconciliation` in those branches to surface the inconsistency to the
      // operator instead of telling them to run a command that will error.
      // (review E5 + E6 / 2026-05-16)
      boolean retryWouldBeRejected = failureEvent.isEmpty() || lastFailedRunner.isEmpty();
      String safeAction;
      if (retryWouldBeRejected
          || artifactOperationPort.hasFailedOrFailedOrphanForRun(workflowRunId)) {
        safeAction = NEXT_SAFE_ACTION_AWAIT_MANUAL_RECONCILIATION;
      } else {
        safeAction = NEXT_SAFE_ACTION_RETRY;
      }

      log.info(
          "describe failure resolved workflowRunId={} currentState={} failedStage={} nextSafeAction={}",
          workflowRunId,
          run.currentState().value(),
          failedStage,
          safeAction);
      return new FailureDescription(
          workflowRunId,
          run.currentState(),
          failedStage,
          lastSuccessfulStage,
          failureTimestamp,
          failureCategory,
          lastActivityTimestamp,
          safeAction,
          diagnosticReferenceCorrelationId);
    } finally {
      MdcKeys.endScope(MdcKeys.WORKFLOW_RUN_ID, priorRunMdc);
    }
  }

  private Optional<RunnerExecutionSnapshot> findLastFailedRunner(String workflowRunId) {
    return pickLatest(
        runnerExecutionRecordPort.findByWorkflowRunPublicIdAndStatusIn(
            workflowRunId, FAILED_RUNNER_STATUSES));
  }

  private Optional<RunnerExecutionSnapshot> findLatestRunner(String workflowRunId) {
    return pickLatest(
        runnerExecutionRecordPort.findByWorkflowRunPublicIdAndStatusIn(
            workflowRunId, ALL_RUNNER_STATUSES));
  }

  private static Optional<RunnerExecutionSnapshot> pickLatest(
      List<RunnerExecutionSnapshot> snapshots) {
    return snapshots.stream()
        .max(
            Comparator.comparing(
                    RunnerExecutionSnapshot::createdAt,
                    Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(
                    RunnerExecutionSnapshot::publicId,
                    Comparator.nullsFirst(Comparator.naturalOrder())));
  }

  private static DomainException runNotFound(String workflowRunPublicId) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("runId", workflowRunPublicId);
    return new DomainException(
        DomainErrorCode.RUN_NOT_FOUND, "Workflow run not found: " + workflowRunPublicId, details);
  }

  private record RetryPrep(String recoveryActionPublicId, String recoveryRetriedEventPublicId) {}
}
