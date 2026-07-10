package org.dradgo.application.workflow;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import org.dradgo.application.observability.MdcKeys;
import org.dradgo.application.project.ProjectRuntimeConfigResolver;
import org.dradgo.application.runner.RunnerBroker;
import org.dradgo.application.workflow.WorkflowTransitionService.TransitionActor;
import org.dradgo.application.workflow.commands.ApproveDeliveryCommand;
import org.dradgo.application.workflow.spi.WorkflowEventReadPort;
import org.dradgo.application.workflow.spi.WorkflowEventRecord;
import org.dradgo.application.workflow.spi.WorkflowEventWritePort;
import org.dradgo.domain.id.PublicIdPrefixes;
import org.dradgo.domain.registry.PushMode;
import org.dradgo.domain.registry.WorkflowEventDetailKeys;
import org.dradgo.domain.registry.WorkflowEventType;
import org.dradgo.domain.registry.WorkflowState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Story 3h-4 (AC4, FR78) — the executor for the pre-review delivery-gate operator action {@code
 * approve_delivery}. The structural twin of {@code LintApprovalService.approveLint}: a {@code
 * MANDATORY}-propagation method that participates in {@code WorkflowCommandService}'s
 * idempotency-boundary transaction (so the transition rolls back with any downstream failure), and
 * DEFERS the workspace-coupled side effect post-commit via the 3h-0 afterCommit helper.
 *
 * <p>It resolves the run's {@link PushMode} and:
 *
 * <ul>
 *   <li>transitions {@code WaitingForDelivery -> WaitingForReview} SYNCHRONOUSLY in the command tx
 *       (a wrong-state call is rejected 409 upstream by {@code
 *       WorkflowCommandService.requireParkedAtDeliveryGate}, exactly like the lint gate);
 *   <li><b>{@code APPROVE}</b> → defers the git-push-bearing delivery resume ({@code
 *       RunnerBroker.resumeDeliveryTailFromGate}, REUSED verbatim from 3h-2 — it self-gates on
 *       already-pushed refs, respects {@code autoCreatePullRequest}, enriches + links the PR, and
 *       enqueues the reviewer);
 *   <li><b>{@code MANUAL}</b> → appends a {@code delivery.recordedManually} audit event (in the
 *       command tx, atomic with the transition) and defers the reviewer enqueue-only via {@code
 *       RunnerBroker.recordManualDeliveryAndEnqueueReviewer} — NEVER touching git (Decision 4 /
 *       {@code ingestManualResult} precedent).
 * </ul>
 *
 * <p>Returns {@link WorkflowState#WAITING_FOR_REVIEW} (the resulting state the command reports / a
 * replay pins). Idempotent: the {@code executeIdempotent} boundary short-circuits a replay to the
 * pinned post-state, and the deferred seams self-gate (no double-push / double-PR /
 * double-enqueue).
 */
@Service
public class DeliveryApprovalService {

  private static final Logger log = LoggerFactory.getLogger(DeliveryApprovalService.class);

  private final WorkflowTransitionService transitionService;
  private final ProjectRuntimeConfigResolver projectRuntimeConfigResolver;
  private final AfterCommitSideEffectRunner afterCommit;
  private final WorkflowEventReadPort workflowEventReadPort;
  private final WorkflowEventWritePort workflowEventWritePort;
  private final Supplier<RunnerBroker> brokerSupplier;
  private final Clock clock;

  @Autowired
  public DeliveryApprovalService(
      WorkflowTransitionService transitionService,
      ProjectRuntimeConfigResolver projectRuntimeConfigResolver,
      AfterCommitSideEffectRunner afterCommit,
      WorkflowEventReadPort workflowEventReadPort,
      WorkflowEventWritePort workflowEventWritePort,
      ObjectProvider<RunnerBroker> brokerProvider) {
    this(
        transitionService,
        projectRuntimeConfigResolver,
        afterCommit,
        workflowEventReadPort,
        workflowEventWritePort,
        brokerProvider::getIfAvailable,
        Clock.systemUTC());
  }

  DeliveryApprovalService(
      WorkflowTransitionService transitionService,
      ProjectRuntimeConfigResolver projectRuntimeConfigResolver,
      AfterCommitSideEffectRunner afterCommit,
      WorkflowEventReadPort workflowEventReadPort,
      WorkflowEventWritePort workflowEventWritePort,
      Supplier<RunnerBroker> brokerSupplier,
      Clock clock) {
    this.transitionService = Objects.requireNonNull(transitionService, "transitionService");
    this.projectRuntimeConfigResolver =
        Objects.requireNonNull(projectRuntimeConfigResolver, "projectRuntimeConfigResolver");
    this.afterCommit = Objects.requireNonNull(afterCommit, "afterCommit");
    this.workflowEventReadPort =
        Objects.requireNonNull(workflowEventReadPort, "workflowEventReadPort");
    this.workflowEventWritePort =
        Objects.requireNonNull(workflowEventWritePort, "workflowEventWritePort");
    this.brokerSupplier = Objects.requireNonNull(brokerSupplier, "brokerSupplier");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * AC4 — {@code approve_delivery}: dismiss the delivery gate and advance to {@code
   * WaitingForReview}. Transitions synchronously in this command tx, then defers the mode-specific
   * side effect (push resume for {@code approve}, reviewer enqueue for {@code manual}) AFTER
   * commit. Returns {@code WaitingForReview} (the resulting state the command reports / a replay
   * pins).
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public WorkflowState approveDelivery(ApproveDeliveryCommand command) {
    PublicIdPrefixes.require(command.workflowRunId(), PublicIdPrefixes.WORKFLOW_RUN);
    String runId = command.workflowRunId();
    String correlationId = normalizeOptional(command.correlationId());
    String priorRun = MdcKeys.beginScope(MdcKeys.WORKFLOW_RUN_ID, runId);
    try {
      PushMode pushMode = resolveParkedPushMode(runId);

      // Transition WaitingForDelivery -> WaitingForReview SYNCHRONOUSLY in this command tx, keeping
      // the workspace-coupled side effect DEFERRED post-commit. The wrong-state guard is UPSTREAM
      // in
      // WorkflowCommandService.requireParkedAtDeliveryGate (executor-as-gate): the transition alone
      // does NOT 409 a wrong-state approve because WaitingForReview is reachable from other
      // sources,
      // so a parked-state precondition is asserted before this executor runs.
      transitionService.transition(
          runId,
          WorkflowState.WAITING_FOR_REVIEW,
          new TransitionActor(command.actorIdentity(), command.actorType()),
          "delivery_approved",
          "delivery-approved:" + runId,
          Map.of("pushMode", pushMode.value()));

      if (pushMode == PushMode.MANUAL) {
        // Manual mode records the out-of-band delivery (the operator pushed by hand) and advances
        // WITHOUT touching git. Append the audit event in THIS command tx (atomic with the
        // transition), then defer the reviewer enqueue-only post-commit.
        appendManualDeliveryEvent(runId, command, correlationId);
        log.info(
            "approveDelivery accepted (manual) + transitioned WaitingForDelivery->WaitingForReview "
                + "workflowRunId={} actorIdentity={} — recorded out-of-band delivery, reviewer "
                + "enqueue deferred post-commit (no git)",
            runId,
            command.actorIdentity());
        afterCommit.runAfterCommit(
            "delivery-approve-manual",
            runId,
            () ->
                afterCommit.runInNewTransaction(
                    "delivery-approve-manual-tx",
                    runId,
                    () -> {
                      RunnerBroker broker = brokerSupplier.get();
                      if (broker != null) {
                        broker.recordManualDeliveryAndEnqueueReviewer(runId, correlationId);
                      } else {
                        log.warn(
                            "approveDelivery (manual) cannot enqueue reviewer (no broker bean) "
                                + "workflowRunId={}",
                            runId);
                      }
                    }));
      } else {
        // APPROVE mode must fail before the gate exit commits when the backend push is rejected.
        // Run the existing delivery-resume seam inside this command transaction with fail-fast push
        // handling; a GitCommandException bubbles out, rolls back the transition above, and leaves
        // the run parked at WaitingForDelivery for retry.
        RunnerBroker broker = brokerSupplier.get();
        if (broker != null) {
          broker.resumeDeliveryTailFromGateOrThrow(runId, correlationId);
        } else {
          log.warn(
              "approveDelivery (approve) cannot resume delivery tail (no broker bean) workflowRunId={}",
              runId);
        }
        log.info(
            "approveDelivery accepted (approve) + transitioned WaitingForDelivery->WaitingForReview "
                + "workflowRunId={} actorIdentity={} - delivery push resume completed",
            runId,
            command.actorIdentity());
      }
      return WorkflowState.WAITING_FOR_REVIEW;
    } finally {
      MdcKeys.endScope(MdcKeys.WORKFLOW_RUN_ID, priorRun);
    }
  }

  /**
   * Append the {@code delivery.recordedManually} governed audit event for a manual-mode delivery.
   * Not itself a workflow-state change ({@code priorState == resultingState == WaitingForReview},
   * {@code interventionMarker = true}) — it records that an operator delivered out-of-band. Runs in
   * the command tx so it is atomic with the transition.
   */
  private PushMode resolveParkedPushMode(String runId) {
    return workflowEventReadPort
        .findLatestTransitionToState(runId, WorkflowState.WAITING_FOR_DELIVERY)
        .map(WorkflowEventRecord::details)
        .map(details -> details == null ? null : details.get("pushMode"))
        .map(String::valueOf)
        .filter(value -> !value.isBlank())
        .map(value -> PushMode.fromValue(value, "workflow_events.details.pushMode"))
        .orElseGet(() -> projectRuntimeConfigResolver.resolvePushMode(runId));
  }

  private void appendManualDeliveryEvent(
      String runId, ApproveDeliveryCommand command, String correlationId) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put(WorkflowEventDetailKeys.WORKFLOW_RUN_ID, runId);
    if (correlationId != null) {
      details.put(WorkflowEventDetailKeys.CORRELATION_ID, correlationId);
    }
    workflowEventWritePort.append(
        new WorkflowEventRecord(
            PublicIdPrefixes.WORKFLOW_EVENT.next(),
            runId,
            WorkflowEventType.DELIVERY_RECORDED_MANUALLY,
            WorkflowState.WAITING_FOR_REVIEW,
            WorkflowState.WAITING_FOR_REVIEW,
            command.actorIdentity(),
            command.actorType(),
            "manual delivery recorded (out-of-band push)",
            null,
            true,
            OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC),
            details));
  }

  private static String normalizeOptional(String value) {
    return (value == null || value.isBlank()) ? null : value.trim();
  }
}
