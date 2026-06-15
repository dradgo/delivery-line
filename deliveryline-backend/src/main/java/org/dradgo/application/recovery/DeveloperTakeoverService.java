package org.dradgo.application.recovery;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.dradgo.application.idempotency.IdempotencyKeyValidator;
import org.dradgo.application.integration.IntegrationLink;
import org.dradgo.application.integration.IntegrationLinkService;
import org.dradgo.application.observability.MdcKeys;
import org.dradgo.application.recovery.spi.RecoveryActionRecordPort;
import org.dradgo.application.recovery.spi.RecoveryActionSnapshot;
import org.dradgo.application.recovery.spi.RecoveryActionWriteCommand;
import org.dradgo.application.runner.spi.RunnerAdapter;
import org.dradgo.application.runner.spi.RunnerExecutionRecordPort;
import org.dradgo.application.runner.spi.RunnerExecutionSnapshot;
import org.dradgo.application.runner.spi.RunnerTakeoverCancellation;
import org.dradgo.application.workflow.WorkflowCommandService;
import org.dradgo.application.workflow.WorkflowStateChangeResult;
import org.dradgo.application.workflow.commands.TakeoverWorkflowCommand;
import org.dradgo.application.workflow.spi.WorkflowEventReadPort;
import org.dradgo.application.workflow.spi.WorkflowEventRecord;
import org.dradgo.application.workflow.spi.WorkflowRunReadPort;
import org.dradgo.application.workflow.spi.WorkflowRunSnapshot;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.id.PublicIdPrefixes;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.RunnerExecutionStatus;
import org.dradgo.domain.registry.WorkflowState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Application service for developer takeover (story 3.22) — the canonical executor for the {@code
 * takeover_workflow} action (FR18 / FR19 / FR33).
 *
 * <p><strong>Takeover twin of {@link RecoveryService#retry}.</strong> This is a deliberate SIBLING
 * service, not a third method on {@link RecoveryService} (whose ArchUnit rule pins its public
 * surface to exactly {@code retry} + {@code describeFailure} — Trap T1). It mirrors {@code retry}'s
 * structure with two differences: it <em>cancels</em> runner dispatch instead of enqueuing a new
 * runner (so NO {@code :runner}-namespaced idempotency key — Trap T6), and it transitions to the
 * <em>terminal</em> {@link WorkflowState#TAKEN_OVER} state (no further dispatch).
 *
 * <p><strong>Stop-dispatch atomicity (FR18).</strong> The {@code → TakenOver} transition, the
 * {@code recovery_actions} insert, and the flip of every {@code {queued, pending, running}}
 * runner_executions row to {@code cancelled_for_takeover} all run inside ONE {@code REQUIRES_NEW}
 * transaction. The DB status flip is the authoritative no-more-dispatch signal: a {@code
 * cancelled_for_takeover} row is invisible to the worker pool's {@code dequeueNext} (leases {@code
 * status='queued'}) AND the broker's {@code ACTIVE_STATUSES = {pending, running}}. Doing the flip
 * atomically with the transition closes the window where a worker could dequeue a row after the run
 * is already TakenOver.
 *
 * <p><strong>Best-effort container stop.</strong> For rows that were {@code running}, the live
 * container is stopped {@code POST-commit} via {@link RunnerAdapter#cancel} ({@code docker stop} —
 * never throws). It is intentionally outside the transaction: it cannot roll back the takeover, and
 * the DB flip already stopped dispatch (Trap T5).
 *
 * <p><strong>Preserved context (FR19 / FR33).</strong> Takeover deletes/supersedes NOTHING. Prior
 * artifacts, the full append-only audit trail, and the active {@code github_pr} integration link
 * are all left untouched — the developer continues on the same PR/branch with normal git tooling.
 *
 * <p><strong>Scope (OQ-1).</strong> Story 3.22 ships SERVICE-ONLY. The existing transition-only
 * {@code POST /takeover-workflow} REST endpoint stays as-is; story 3.25 re-points it to this
 * orchestrator + adds {@code deliveryline takeover}. Until then the REST takeover path does NOT
 * cancel runners (a dormant gap, identical to today's retry CLI/REST asymmetry).
 */
@Service
public class DeveloperTakeoverService {

  private static final Logger log = LoggerFactory.getLogger(DeveloperTakeoverService.class);

  static final String ACTION_TYPE_TAKEOVER = "takeover";
  static final String REVIEWER_ROLE_DEVELOPER = "developer";
  static final String RESULT_STATUS_PENDING = "pending";
  static final String RESULT_STATUS_SUCCEEDED = "succeeded";

  // AC5: the dispatch-visible statuses scanned and flipped to cancelled_for_takeover. Covers the
  // dequeue race window (queued = worker-pool lease target; pending+running = broker
  // ACTIVE_STATUSES).
  private static final List<RunnerExecutionStatus> CANCEL_SCAN_STATUSES =
      List.of(
          RunnerExecutionStatus.QUEUED,
          RunnerExecutionStatus.PENDING,
          RunnerExecutionStatus.RUNNING);

  private final WorkflowRunReadPort workflowRunReadPort;
  private final WorkflowEventReadPort workflowEventReadPort;
  private final RunnerExecutionRecordPort runnerExecutionRecordPort;
  private final WorkflowCommandService workflowCommandService;
  private final RecoveryActionRecordPort recoveryActionRecordPort;
  private final IdempotencyKeyValidator idempotencyKeyValidator;
  private final IntegrationLinkService integrationLinkService;
  // Story 3.22 Task 4 — the cancel SPI is the base RunnerAdapter port (cancel never throws; an
  // implementation is always present: DockerRunnerAdapter under runners.docker, MockRunnerAdapter
  // otherwise). Reached via the application port, never the adapter
  // (application-cannot-import-adapters).
  private final RunnerAdapter runnerAdapter;
  private final Clock clock;
  private final TransactionTemplate takeoverPrepTransactionTemplate;
  private final TransactionTemplate resultStatusTransactionTemplate;

  @Autowired
  public DeveloperTakeoverService(
      WorkflowRunReadPort workflowRunReadPort,
      WorkflowEventReadPort workflowEventReadPort,
      RunnerExecutionRecordPort runnerExecutionRecordPort,
      WorkflowCommandService workflowCommandService,
      RecoveryActionRecordPort recoveryActionRecordPort,
      IdempotencyKeyValidator idempotencyKeyValidator,
      IntegrationLinkService integrationLinkService,
      RunnerAdapter runnerAdapter,
      PlatformTransactionManager transactionManager) {
    this(
        workflowRunReadPort,
        workflowEventReadPort,
        runnerExecutionRecordPort,
        workflowCommandService,
        recoveryActionRecordPort,
        idempotencyKeyValidator,
        integrationLinkService,
        runnerAdapter,
        Clock.systemUTC(),
        requiresNewTemplate(transactionManager),
        requiresNewTemplate(transactionManager));
  }

  DeveloperTakeoverService(
      WorkflowRunReadPort workflowRunReadPort,
      WorkflowEventReadPort workflowEventReadPort,
      RunnerExecutionRecordPort runnerExecutionRecordPort,
      WorkflowCommandService workflowCommandService,
      RecoveryActionRecordPort recoveryActionRecordPort,
      IdempotencyKeyValidator idempotencyKeyValidator,
      IntegrationLinkService integrationLinkService,
      RunnerAdapter runnerAdapter,
      Clock clock,
      TransactionTemplate takeoverPrepTransactionTemplate,
      TransactionTemplate resultStatusTransactionTemplate) {
    this.workflowRunReadPort = Objects.requireNonNull(workflowRunReadPort, "workflowRunReadPort");
    this.workflowEventReadPort =
        Objects.requireNonNull(workflowEventReadPort, "workflowEventReadPort");
    this.runnerExecutionRecordPort =
        Objects.requireNonNull(runnerExecutionRecordPort, "runnerExecutionRecordPort");
    this.workflowCommandService =
        Objects.requireNonNull(workflowCommandService, "workflowCommandService");
    this.recoveryActionRecordPort =
        Objects.requireNonNull(recoveryActionRecordPort, "recoveryActionRecordPort");
    this.idempotencyKeyValidator =
        Objects.requireNonNull(idempotencyKeyValidator, "idempotencyKeyValidator");
    this.integrationLinkService =
        Objects.requireNonNull(integrationLinkService, "integrationLinkService");
    this.runnerAdapter = Objects.requireNonNull(runnerAdapter, "runnerAdapter");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.takeoverPrepTransactionTemplate =
        Objects.requireNonNull(takeoverPrepTransactionTemplate, "takeoverPrepTransactionTemplate");
    this.resultStatusTransactionTemplate =
        Objects.requireNonNull(resultStatusTransactionTemplate, "resultStatusTransactionTemplate");
  }

  private static TransactionTemplate requiresNewTemplate(
      PlatformTransactionManager transactionManager) {
    TransactionTemplate template = new TransactionTemplate(transactionManager);
    template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    return template;
  }

  /**
   * Transition {@code command.workflowRunId()} to {@link WorkflowState#TAKEN_OVER}, cancel all
   * in-flight + queued runner dispatch, record the takeover with developer attribution, and
   * preserve all prior context. Idempotent on {@code command.idempotencyKey()}.
   */
  public TakeoverResult takeoverWorkflow(TakeoverWorkflowCommand command) {
    Objects.requireNonNull(command, "command");
    String workflowRunId = command.workflowRunId();
    PublicIdPrefixes.require(workflowRunId, PublicIdPrefixes.WORKFLOW_RUN);
    requireTakeoverInvariants(command);
    String validatedKey = idempotencyKeyValidator.requireValid(command.idempotencyKey());
    // Stamp the full takeover audit context (run / correlation / actor / idempotency) so EVERY
    // takeover log line and shipped JSON event is attributable to the actor and the idempotency
    // envelope (review finding). recoveryActionId is stamped in an inner scope once the prep tx
    // assigns it. All keys are members of MdcKeys.ALL (the closed contract). The reviewer-supplied
    // reasonText is NEVER promoted to MDC.
    String priorRunMdc = MdcKeys.beginScope(MdcKeys.WORKFLOW_RUN_ID, workflowRunId);
    String priorCorrelationMdc =
        MdcKeys.beginScope(MdcKeys.CORRELATION_ID, command.correlationId());
    String priorActorIdentityMdc =
        MdcKeys.beginScope(MdcKeys.ACTOR_IDENTITY, command.actorIdentity());
    String priorActorTypeMdc = MdcKeys.beginScope(MdcKeys.ACTOR_TYPE, command.actorType().value());
    String priorIdempotencyMdc = MdcKeys.beginScope(MdcKeys.IDEMPOTENCY_KEY, validatedKey);
    try {
      long start = System.nanoTime();
      log.info(
          "developer takeover start workflowRunId={} idempotencyKey={} actorIdentity={} actorType={}",
          workflowRunId,
          validatedKey,
          command.actorIdentity(),
          command.actorType().value());

      // Step 1 — replay detection (mirror RecoveryService.retry). A prior recovery_actions row is a
      // replay only when it is the SAME run AND already `succeeded`. Different run / non-succeeded
      // →
      // IDEMPOTENCY_KEY_CONFLICT. AC11: two backstops apply — this check + uq_recovery_actions_*.
      Optional<RecoveryActionSnapshot> priorAction =
          recoveryActionRecordPort.findByIdempotencyKey(validatedKey);
      if (priorAction.isPresent()) {
        Optional<TakeoverResult> replay = resolveReplay(workflowRunId, validatedKey, command);
        if (replay.isPresent()) {
          return replay.get();
        }
        // resolveReplay throws IDEMPOTENCY_KEY_CONFLICT for different-run / non-succeeded priors,
        // so
        // a present-but-empty result is unreachable; defensive fall-through is impossible.
      }

      // Step 2 — prep work runs inside a single REQUIRES_NEW transaction so the transition, the
      // runner cancel-flips, and the recovery_actions row are durable ATOMICALLY (FR18: the flip is
      // the authoritative stop-dispatch signal and must not commit-skew from the transition).
      TakeoverPrep prep;
      try {
        prep =
            takeoverPrepTransactionTemplate.execute(
                status -> performTakeoverPrep(command, validatedKey));
      } catch (DomainException prepError) {
        // Concurrent-takeover race (mirror RecoveryService.retry F532): a sibling won the
        // recovery_actions insert between our pre-check and our prep tx. If the prior row is now
        // the
        // same run AND succeeded, return the documented replay; otherwise propagate. An
        // ILLEGAL_TRANSITION (terminal run — AC12) also lands here and propagates unchanged.
        if (prepError.errorCode() == DomainErrorCode.IDEMPOTENCY_KEY_CONFLICT) {
          Optional<TakeoverResult> raceReplay =
              resolveConcurrentReplay(workflowRunId, validatedKey, command);
          if (raceReplay.isPresent()) {
            return raceReplay.get();
          }
        } else if (prepError.errorCode() == DomainErrorCode.ILLEGAL_TRANSITION) {
          Optional<TakeoverResult> raceReplay =
              resolveConcurrentReplay(workflowRunId, validatedKey, command);
          if (raceReplay.isPresent()) {
            return raceReplay.get();
          }
          log.warn(
              "developer takeover rejected workflowRunId={} reason=illegal_transition errorCode={}",
              workflowRunId,
              prepError.errorCode().value());
        }
        throw prepError;
      }

      // recoveryActionId is now known — stamp it so the container-stop / completion / success log
      // lines (and shipped JSON events) carry it in MDC (review finding — full audit context).
      String priorRecoveryActionMdc =
          MdcKeys.beginScope(MdcKeys.RECOVERY_ACTION_ID, prep.recoveryActionPublicId);
      try {
        // Step 3 — best-effort container stop for previously-running rows, POST-commit. cancel()
        // never throws; the defensive try/catch + WARN is belt-and-braces. The DB flip already
        // stopped dispatch, so a failure here does not roll back the takeover.
        for (String runningRexId : prep.runningRunnerExecutionIds) {
          try {
            runnerAdapter.cancel(runningRexId);
          } catch (RuntimeException cancelError) {
            log.warn(
                "developer takeover best-effort container-cancel failed workflowRunId={} runnerExecutionId={} cause={} — DB flip already stopped dispatch",
                workflowRunId,
                runningRexId,
                cancelError.getClass().getSimpleName());
          }
        }

        // Step 4 — flip the recovery_actions row to succeeded in a fresh REQUIRES_NEW tx. Leaving
        // it `pending` while reporting success would lie about audit terminality (mirror retry
        // F526).
        try {
          resultStatusTransactionTemplate.executeWithoutResult(
              status -> recoveryActionRecordPort.markSucceeded(validatedKey));
        } catch (RuntimeException completionError) {
          log.error(
              "developer takeover completion status update failed workflowRunId={} recoveryActionId={} idempotencyKey={} errorClass={} — run is TakenOver and runners cancelled but recovery_actions row stays pending; operator reconciliation required",
              workflowRunId,
              prep.recoveryActionPublicId,
              validatedKey,
              completionError.getClass().getSimpleName());
          Map<String, Object> details = new LinkedHashMap<>();
          details.put("runId", workflowRunId);
          details.put("recoveryActionId", prep.recoveryActionPublicId);
          details.put("idempotencyKey", validatedKey);
          details.put("reason", "result_status_flip_failed_after_takeover");
          DomainException terminal =
              new DomainException(
                  DomainErrorCode.INTERNAL_ERROR,
                  "Run taken over and runners cancelled but recovery_actions.result_status flip to "
                      + "succeeded failed; row remains pending. Operator reconciliation required.",
                  details);
          terminal.addSuppressed(completionError);
          throw terminal;
        }

        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        log.info(
            "developer takeover success workflowRunId={} recoveryActionId={} cancelledInFlightCount={} cancelledQueuedCount={} resultingState={} durationMs={}",
            workflowRunId,
            prep.recoveryActionPublicId,
            prep.cancelledInFlightCount,
            prep.cancelledQueuedCount,
            prep.resultingState.value(),
            elapsedMs);
        return new TakeoverResult(
            workflowRunId,
            prep.recoveryActionPublicId,
            prep.resultingState,
            prep.resultingEventPublicId,
            prep.cancelledInFlightCount,
            prep.cancelledQueuedCount,
            prep.preservedPrReference,
            command.correlationId(),
            false);
      } finally {
        MdcKeys.endScope(MdcKeys.RECOVERY_ACTION_ID, priorRecoveryActionMdc);
      }
    } finally {
      MdcKeys.endScope(MdcKeys.IDEMPOTENCY_KEY, priorIdempotencyMdc);
      MdcKeys.endScope(MdcKeys.ACTOR_TYPE, priorActorTypeMdc);
      MdcKeys.endScope(MdcKeys.ACTOR_IDENTITY, priorActorIdentityMdc);
      MdcKeys.endScope(MdcKeys.CORRELATION_ID, priorCorrelationMdc);
      MdcKeys.endScope(MdcKeys.WORKFLOW_RUN_ID, priorRunMdc);
    }
  }

  private TakeoverPrep performTakeoverPrep(TakeoverWorkflowCommand command, String validatedKey) {
    String workflowRunId = command.workflowRunId();

    // OQ-2: triggering_event_id = the latest event at takeover time (pre-transition). Nullable FK.
    String triggeringEventPublicId =
        workflowEventReadPort
            .findLatestByWorkflowRunPublicId(workflowRunId)
            .map(WorkflowEventRecord::publicId)
            .orElse(null);

    // Reuse the command service for the transition (Trap T3) — it owns the → TakenOver transition,
    // its WORKFLOW_STATE_CHANGED event, and its own idempotency envelope. @Transactional(REQUIRED)
    // joins this REQUIRES_NEW prep tx. A Completed/TakenOver/Reconciled run surfaces
    // ILLEGAL_TRANSITION from the transition table here (propagated by the caller — AC12).
    WorkflowStateChangeResult txResult = workflowCommandService.takeoverWorkflow(command);

    // OQ-2/OQ-3 fallback: resulting_event_id = the WORKFLOW_STATE_CHANGED → TakenOver event just
    // appended (now the latest). No dedicated recovery.takenOver event type is added (sanctioned
    // fallback); 3.29 can add one later. The recovery_actions insert resolves this id within this
    // same tx (the adapter looks the event up by public id — identical to retry's flow).
    String resultingEventPublicId =
        workflowEventReadPort
            .findLatestTransitionToState(workflowRunId, WorkflowState.TAKEN_OVER)
            .map(WorkflowEventRecord::publicId)
            .orElse(null);

    // AC5 — cancel dispatch: flip every {queued, pending, running} row to cancelled_for_takeover.
    List<RunnerExecutionSnapshot> dispatchable =
        runnerExecutionRecordPort.findByWorkflowRunPublicIdAndStatusIn(
            workflowRunId, CANCEL_SCAN_STATUSES);
    OffsetDateTime now = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
    List<String> runningRunnerExecutionIds = new ArrayList<>();
    int cancelledQueuedCount = 0;
    int cancelledInFlightCount = 0;
    for (RunnerExecutionSnapshot row : dispatchable) {
      Optional<RunnerTakeoverCancellation> cancellation =
          runnerExecutionRecordPort.markCancelledForTakeover(row.publicId(), now);
      if (cancellation.isEmpty()) {
        log.info(
            "developer takeover runner already terminal workflowRunId={} runnerExecutionId={}",
            workflowRunId,
            row.publicId());
        continue;
      }
      RunnerExecutionStatus from = cancellation.get().previousStatus();
      log.info(
          "developer takeover cancelled runner workflowRunId={} runnerExecutionId={} fromStatus={}",
          workflowRunId,
          row.publicId(),
          from.value());
      if (from == RunnerExecutionStatus.QUEUED) {
        cancelledQueuedCount++;
      } else {
        cancelledInFlightCount++;
      }
      if (from == RunnerExecutionStatus.RUNNING) {
        runningRunnerExecutionIds.add(row.publicId());
      }
    }

    // FR33 — the active github_pr link is LEFT UNTOUCHED (developer continues on the same PR). We
    // only READ its external ref for the result; the row is never mutated.
    String preservedPrReference =
        integrationLinkService
            .findActiveGitHubPrLink(workflowRunId)
            .map(IntegrationLink::externalRef)
            .orElse(null);

    // AC3 — record the takeover with developer attribution. reviewer_role='developer' is the
    // takeover invariant applied HERE (not a field on TakeoverWorkflowCommand — Trap T2).
    RecoveryActionSnapshot recoveryAction =
        recoveryActionRecordPort.insert(
            new RecoveryActionWriteCommand(
                workflowRunId,
                ACTION_TYPE_TAKEOVER,
                triggeringEventPublicId,
                resultingEventPublicId,
                command.actorIdentity(),
                command.actorType(),
                validatedKey,
                RESULT_STATUS_PENDING,
                REVIEWER_ROLE_DEVELOPER));

    return new TakeoverPrep(
        recoveryAction.publicId(),
        resultingEventPublicId,
        txResult.currentState(),
        runningRunnerExecutionIds,
        cancelledInFlightCount,
        cancelledQueuedCount,
        preservedPrReference);
  }

  // Replay path from the pre-prep check: a prior recovery_actions row exists for this key.
  private Optional<TakeoverResult> resolveReplay(
      String workflowRunId, String validatedKey, TakeoverWorkflowCommand command) {
    RecoveryActionSnapshot prior =
        recoveryActionRecordPort
            .findByIdempotencyKey(validatedKey)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "recovery_action vanished between presence check and replay read"));
    if (!workflowRunId.equals(prior.workflowRunPublicId())) {
      Map<String, Object> details = new LinkedHashMap<>();
      details.put("idempotencyKey", validatedKey);
      details.put("requestedRunId", workflowRunId);
      details.put("priorRunId", prior.workflowRunPublicId());
      details.put("reason", "idempotency_key_bound_to_different_run");
      log.warn(
          "developer takeover rejected workflowRunId={} reason=idempotency_key_bound_to_different_run priorRunId={}",
          workflowRunId,
          prior.workflowRunPublicId());
      throw new DomainException(
          DomainErrorCode.IDEMPOTENCY_KEY_CONFLICT,
          "Idempotency key is already bound to a different workflow run (priorRunId="
              + prior.workflowRunPublicId()
              + ")",
          details);
    }
    requireTakeoverReplayRow(prior, validatedKey, workflowRunId);
    if (!RESULT_STATUS_SUCCEEDED.equals(prior.resultStatus())) {
      Map<String, Object> details = new LinkedHashMap<>();
      details.put("idempotencyKey", validatedKey);
      details.put("runId", workflowRunId);
      details.put("priorResultStatus", prior.resultStatus());
      details.put("reason", "idempotency_key_used_by_non_succeeded_attempt");
      log.warn(
          "developer takeover rejected workflowRunId={} reason=idempotency_key_used_by_non_succeeded_attempt priorResultStatus={}",
          workflowRunId,
          prior.resultStatus());
      throw new DomainException(
          DomainErrorCode.IDEMPOTENCY_KEY_CONFLICT,
          "Idempotency key was used by a prior non-succeeded takeover attempt (resultStatus="
              + prior.resultStatus()
              + "); use a fresh key for a new attempt",
          details);
    }
    // Always pass a replay through WorkflowCommandService so the canonical command fingerprint
    // envelope rejects same-key/different-payload calls before we return the richer result.
    workflowCommandService.takeoverWorkflow(command);
    log.warn(
        "developer takeover replay workflowRunId={} recoveryActionId={} idempotencyKey={}",
        workflowRunId,
        prior.publicId(),
        validatedKey);
    return Optional.of(buildReplayResult(workflowRunId, prior, command));
  }

  // Replay-on-conflict path from the prep tx: the conflict came from the recovery_actions unique
  // constraint, which a sibling takeover for the SAME run won. Unlike the pre-check replay path
  // (which treats a `pending` prior as a crashed/ambiguous attempt → IDEMPOTENCY_KEY_CONFLICT), a
  // concurrent-conflict `pending` row CONVERGES on replay: the unique violation can only fire once
  // the winner's prep tx committed (Postgres blocks the loser's insert on the uncommitted sibling
  // row, then fails it on commit). That winner tx is ATOMIC over the → TakenOver transition + the
  // runner cancel-flips + the recovery_actions insert, so by the time the loser observes the
  // committed row the run IS already TakenOver and dispatch IS already stopped — the only work the
  // winner still owns is the out-of-band markSucceeded flip (and the post-commit docker stop). The
  // loser therefore converges deterministically without a busy-wait: it returns the documented
  // replay (no second transition, no second cancel, no markSucceeded) regardless of whether the
  // winner has reached `succeeded` yet. A `failed`/unknown status, a different run, or a
  // non-takeover row still propagates the original conflict.
  private Optional<TakeoverResult> resolveConcurrentReplay(
      String workflowRunId, String validatedKey, TakeoverWorkflowCommand command) {
    Optional<RecoveryActionSnapshot> prior =
        recoveryActionRecordPort.findByIdempotencyKey(validatedKey);
    if (prior.isEmpty()) {
      return Optional.empty();
    }
    RecoveryActionSnapshot snapshot = prior.get();
    if (!workflowRunId.equals(snapshot.workflowRunPublicId())) {
      return Optional.empty();
    }
    if (!ACTION_TYPE_TAKEOVER.equals(snapshot.actionType())
        || !REVIEWER_ROLE_DEVELOPER.equals(snapshot.reviewerRole())) {
      return Optional.empty();
    }
    boolean durablyCommitted =
        RESULT_STATUS_SUCCEEDED.equals(snapshot.resultStatus())
            || RESULT_STATUS_PENDING.equals(snapshot.resultStatus());
    if (!durablyCommitted) {
      return Optional.empty();
    }
    log.info(
        "developer takeover replay-on-conflict workflowRunId={} recoveryActionId={} idempotencyKey={} priorResultStatus={}",
        workflowRunId,
        snapshot.publicId(),
        validatedKey,
        snapshot.resultStatus());
    return Optional.of(buildReplayResult(workflowRunId, snapshot, command));
  }

  private TakeoverResult buildReplayResult(
      String workflowRunId, RecoveryActionSnapshot prior, TakeoverWorkflowCommand command) {
    // AC11 — re-read the run's current state (it is already TakenOver). Counts/PR-ref are not
    // recomputed on replay (the cancels already happened on the original call) — null signals that.
    WorkflowState currentState =
        workflowRunReadPort
            .findByPublicId(workflowRunId)
            .map(WorkflowRunSnapshot::currentState)
            .orElseThrow(() -> runNotFound(workflowRunId));
    return new TakeoverResult(
        workflowRunId,
        prior.publicId(),
        currentState,
        prior.resultingEventPublicId(),
        null,
        null,
        null,
        command.correlationId(),
        true);
  }

  private static DomainException runNotFound(String workflowRunId) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("runId", workflowRunId);
    return new DomainException(
        DomainErrorCode.RUN_NOT_FOUND, "Workflow run not found: " + workflowRunId, details);
  }

  private static void requireTakeoverInvariants(TakeoverWorkflowCommand command) {
    if (command.actorType() != org.dradgo.domain.registry.ActorType.HUMAN) {
      throw invalidCommand(command.workflowRunId(), "actorType", "takeover_requires_human_actor");
    }
    if (command.reasonText() == null || command.reasonText().isBlank()) {
      throw invalidCommand(
          command.workflowRunId(), "reasonText", "takeover_requires_non_blank_reason");
    }
  }

  private static void requireTakeoverReplayRow(
      RecoveryActionSnapshot prior, String idempotencyKey, String workflowRunId) {
    if (ACTION_TYPE_TAKEOVER.equals(prior.actionType())
        && REVIEWER_ROLE_DEVELOPER.equals(prior.reviewerRole())) {
      return;
    }
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("idempotencyKey", idempotencyKey);
    details.put("runId", workflowRunId);
    details.put("priorActionType", prior.actionType());
    details.put("priorReviewerRole", prior.reviewerRole());
    details.put("reason", "idempotency_key_bound_to_non_takeover_recovery_action");
    throw new DomainException(
        DomainErrorCode.IDEMPOTENCY_KEY_CONFLICT,
        "Idempotency key is bound to a non-takeover recovery action",
        details);
  }

  private static DomainException invalidCommand(String workflowRunId, String field, String reason) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("runId", workflowRunId);
    details.put("field", field);
    details.put("reason", reason);
    return new DomainException(
        DomainErrorCode.INVALID_COMMAND_PAYLOAD, "Invalid takeover command: " + field, details);
  }

  private record TakeoverPrep(
      String recoveryActionPublicId,
      String resultingEventPublicId,
      WorkflowState resultingState,
      List<String> runningRunnerExecutionIds,
      int cancelledInFlightCount,
      int cancelledQueuedCount,
      String preservedPrReference) {}
}
