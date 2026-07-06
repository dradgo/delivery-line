package org.dradgo.application.workflow;

import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import org.dradgo.application.observability.MdcKeys;
import org.dradgo.application.runner.RunnerBroker;
import org.dradgo.application.workflow.WorkflowTransitionService.TransitionActor;
import org.dradgo.application.workflow.commands.ApproveLintCommand;
import org.dradgo.application.workflow.commands.RequestLintFixCommand;
import org.dradgo.application.workflow.spi.WorkflowRunRejectionLoopPort;
import org.dradgo.domain.id.PublicIdPrefixes;
import org.dradgo.domain.registry.WorkflowState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Story 3h-2 (AC5, FR76) — the executors for the two pre-review lint-gate operator actions. Mirrors
 * {@code TechnicalApprovalService}: {@code MANDATORY}-propagation methods that participate in
 * {@code WorkflowCommandService}'s idempotency-boundary transaction (so the counter bump +
 * transition roll back together on any downstream failure).
 *
 * <ul>
 *   <li>{@link #approveLint} — dismiss the gate: transition {@code WaitingForLintApproval ->
 *       WaitingForReview} SYNCHRONOUSLY in the command tx (a wrong-state call is rejected 409
 *       upstream by {@code WorkflowCommandService.requireParkedAtLintGate}), then DEFER the
 *       git-push-bearing delivery resume (push + pr-output enrich + reviewer enqueue) post-commit
 *       (via the 3h-0 afterCommit helper) because it must not hold the command's DB transaction —
 *       the exact posture of the build/lint stage tails. Idempotent (the seam self-gates on every
 *       side effect).
 *   <li>{@link #requestLintFix} — feed the findings back to the implementation runner: bump {@code
 *       lint_fix_loop_count}, flip the shared escalation marker ONCE at cap (VISIBILITY only — no
 *       FAILED transition, Decision 3), transition {@code WaitingForLintApproval -> Executing},
 *       then re-dispatch EXECUTION (Trap T1 — AFTER the transition). The re-dispatched run re-runs
 *       BUILD→LINT through the existing chain; the findings flow back by reference via {@code
 *       ContextBundleService}.
 * </ul>
 */
@Service
public class LintApprovalService {

  private static final Logger log = LoggerFactory.getLogger(LintApprovalService.class);

  private final WorkflowTransitionService transitionService;
  private final WorkflowRunRejectionLoopPort rejectionLoopPort;
  private final LintFixEscalationThresholdProvider thresholdProvider;
  private final AfterCommitSideEffectRunner afterCommit;
  // Lazy: the broker is a heavy central bean; resolve it lazily (absent-tolerant) to stay clear of
  // any construction-order surprises, mirroring the broker's own lazy-supplier idiom.
  private final Supplier<RunnerBroker> brokerSupplier;
  private final Supplier<WorkflowOrchestrationService> orchestrationSupplier;

  public LintApprovalService(
      WorkflowTransitionService transitionService,
      WorkflowRunRejectionLoopPort rejectionLoopPort,
      LintFixEscalationThresholdProvider thresholdProvider,
      AfterCommitSideEffectRunner afterCommit,
      ObjectProvider<RunnerBroker> brokerProvider,
      ObjectProvider<WorkflowOrchestrationService> orchestrationProvider) {
    this.transitionService = Objects.requireNonNull(transitionService, "transitionService");
    this.rejectionLoopPort = Objects.requireNonNull(rejectionLoopPort, "rejectionLoopPort");
    this.thresholdProvider = Objects.requireNonNull(thresholdProvider, "thresholdProvider");
    this.afterCommit = Objects.requireNonNull(afterCommit, "afterCommit");
    this.brokerSupplier = brokerProvider::getIfAvailable;
    this.orchestrationSupplier = orchestrationProvider::getIfAvailable;
  }

  /**
   * AC5 — {@code approve_lint}: dismiss the gate and resume the delivery tail. Registers the
   * (git-push-bearing) resume to run AFTER this command's idempotency transaction commits; returns
   * {@code WaitingForReview} (the resulting state the command reports / a replay pins).
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public WorkflowState approveLint(ApproveLintCommand command) {
    PublicIdPrefixes.require(command.workflowRunId(), PublicIdPrefixes.WORKFLOW_RUN);
    String runId = command.workflowRunId();
    String correlationId = normalizeOptional(command.correlationId());
    String priorRun = MdcKeys.beginScope(MdcKeys.WORKFLOW_RUN_ID, runId);
    try {
      // Transition WaitingForLintApproval -> WaitingForReview SYNCHRONOUSLY in this command tx
      // (mirrors requestLintFix), keeping the git-push-bearing delivery resume DEFERRED post-commit
      // (it must not hold the DB tx). The wrong-state guard is UPSTREAM in
      // WorkflowCommandService.requireParkedAtLintGate (code-review 2026-07-06 re-review): the
      // transition alone does NOT 409 a wrong-state approve because EXECUTING -> WaitingForReview
      // is
      // a legal delivery-tail edge, so a parked-state precondition is asserted before this executor
      // runs. By the time we reach here the run IS parked at the gate and this is the intended
      // edge.
      transitionService.transition(
          runId,
          WorkflowState.WAITING_FOR_REVIEW,
          new TransitionActor(command.actorIdentity(), command.actorType()),
          "lint_approved",
          "lint-approved:" + runId,
          Map.of());
      log.info(
          "approveLint accepted + transitioned WaitingForLintApproval->WaitingForReview "
              + "workflowRunId={} actorIdentity={} — delivery resume deferred post-commit",
          runId,
          command.actorIdentity());
      afterCommit.runAfterCommit(
          "lint-approve-resume",
          runId,
          () ->
              // Layer B (REQUIRES_NEW): the resume does JPA work — pr-output enrichment via
              // ArtifactOperationService (REQUIRED propagation). A bare afterCommit callback has no
              // live tx, so a plain REQUIRED would join the DYING command tx and throw "No active
              // transaction" (the 3f post-commit trap, ADR 0032). Run the whole resume in a fresh
              // REQUIRES_NEW tx.
              afterCommit.runInNewTransaction(
                  "lint-approve-resume-tx",
                  runId,
                  () -> {
                    RunnerBroker broker = brokerSupplier.get();
                    if (broker != null) {
                      broker.resumeDeliveryTailFromGate(runId, correlationId);
                    } else {
                      log.warn(
                          "approveLint cannot resume delivery tail (no broker bean) workflowRunId={}",
                          runId);
                    }
                  }));
      return WorkflowState.WAITING_FOR_REVIEW;
    } finally {
      MdcKeys.endScope(MdcKeys.WORKFLOW_RUN_ID, priorRun);
    }
  }

  /**
   * AC5 — {@code request_lint_fix}: bump the loop counter, flip the shared escalation marker once
   * at cap (visibility only — never FAILED, Decision 3), transition {@code WaitingForLintApproval
   * -> Executing}, then re-dispatch EXECUTION (Trap T1 — after the transition). Returns {@code
   * Executing} (the resulting state the command reports / a replay pins).
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public WorkflowState requestLintFix(RequestLintFixCommand command) {
    PublicIdPrefixes.require(command.workflowRunId(), PublicIdPrefixes.WORKFLOW_RUN);
    String runId = command.workflowRunId();
    String correlationId = normalizeOptional(command.correlationId());
    String priorRun = MdcKeys.beginScope(MdcKeys.WORKFLOW_RUN_ID, runId);
    try {
      int loopCount = rejectionLoopPort.incrementAndReadLintFixLoopCount(runId);
      int cap = thresholdProvider.get();
      if (loopCount >= cap) {
        boolean flipped = rejectionLoopPort.markEscalationOnce(runId) == 1;
        log.warn(
            "lint fix loop reached cap workflowRunId={} loopCount={} cap={} escalationFlipped={} "
                + "(visibility only — run re-parks, never FAILED)",
            runId,
            loopCount,
            cap,
            flipped);
      } else {
        log.warn("lint fix loop attempt {}/{} workflowRunId={}", loopCount, cap, runId);
      }

      // Transition WaitingForLintApproval -> Executing (idempotency-keyed per attempt), then
      // re-dispatch. Trap T1: transition FIRST, re-dispatch AFTER (a pure re-dispatch — no second
      // transition). The re-dispatch shares this @Transactional boundary (the enqueue opens a
      // REQUIRED tx + afterCommit NOTIFY), exactly like rejectImplementation / retryWorkflow.
      transitionService.transition(
          runId,
          WorkflowState.EXECUTING,
          new TransitionActor(command.actorIdentity(), command.actorType()),
          fallbackReason(command.reasonText(), "request lint fix"),
          "lint-fix:" + runId + ":" + loopCount,
          Map.of("lintFixLoopCount", loopCount));

      WorkflowOrchestrationService orchestration = orchestrationSupplier.get();
      if (orchestration != null) {
        log.info(
            "requestLintFix re-dispatching EXECUTION workflowRunId={} correlationId={} loopCount={}",
            runId,
            correlationId,
            loopCount);
        orchestration.retryImplementation(runId, correlationId);
      } else {
        log.warn(
            "requestLintFix cannot re-dispatch (no orchestration bean) workflowRunId={}", runId);
      }
      return WorkflowState.EXECUTING;
    } finally {
      MdcKeys.endScope(MdcKeys.WORKFLOW_RUN_ID, priorRun);
    }
  }

  private static String normalizeOptional(String value) {
    return (value == null || value.isBlank()) ? null : value.trim();
  }

  private static String fallbackReason(String reasonText, String fallback) {
    return (reasonText == null || reasonText.isBlank()) ? fallback : reasonText.trim();
  }
}
