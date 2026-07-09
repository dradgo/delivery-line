package org.dradgo.application.workflow;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.FailureCategory;
import org.dradgo.domain.registry.WorkflowState;

public final class WorkflowTransitionTable {

  private static final Set<FailureCategory> ALLOWED_RUNNER_FAILURE_CATEGORIES =
      Set.of(
          FailureCategory.RUNNER_TIMEOUT,
          FailureCategory.RUNNER_CRASH,
          FailureCategory.RUNNER_CONTRACT_VIOLATION,
          FailureCategory.RUNNER_NON_ZERO_EXIT,
          // Story 3h-1 (AC5) — the bounded build auto-fix loop fails the run from EXECUTING with
          // RUNNER_BUILD_FAILED once the fix cap is exhausted
          // (BuildStageService.handleBuildFailure).
          FailureCategory.RUNNER_BUILD_FAILED);

  private final Map<WorkflowState, Set<WorkflowState>> allowedTargets;

  private WorkflowTransitionTable(Map<WorkflowState, Set<WorkflowState>> allowedTargets) {
    this.allowedTargets = allowedTargets;
  }

  public static WorkflowTransitionTable defaultTable() {
    Map<WorkflowState, Set<WorkflowState>> rules = new EnumMap<>(WorkflowState.class);
    // Story 3a-1 (ADR 0004 §Decision-1 / OQ-1) — spec-stage orchestration's trigger transition is
    // a single Inbox -> Investigating hop (the ADR's stated wording). PLANNED stays a
    // reserved/unused intermediate (nothing in production advances into or out of it today), so
    // INBOX -> INVESTIGATING is added directly rather than forcing orchestration through a
    // side-effect-free PLANNED hop. The legacy INBOX -> PLANNED edge is retained for compatibility.
    // Story 3f-3 (AC2 / AC5) — a freshly-created run with unmet run-dependency prerequisites parks
    // in WAITING_FOR_DEPENDENCIES from INBOX (instead of dispatching to INVESTIGATING). A
    // zero/satisfied-dependency run keeps the existing INBOX -> INVESTIGATING dispatch edge.
    put(
        rules,
        WorkflowState.INBOX,
        WorkflowState.PLANNED,
        WorkflowState.INVESTIGATING,
        WorkflowState.WAITING_FOR_DEPENDENCIES,
        WorkflowState.TAKEN_OVER,
        WorkflowState.RECONCILED);
    put(
        rules,
        WorkflowState.PLANNED,
        WorkflowState.INVESTIGATING,
        WorkflowState.TAKEN_OVER,
        WorkflowState.RECONCILED);
    // Story 3a-1 (AC4 / AC8) — a spec-stage runner outcome (timeout / crash / contract violation /
    // non-zero exit, or an artifact-type mismatch routed through the contract-violation path) must
    // be able to fail the run while it sits in INVESTIGATING. Before 3a-1 the only source state for
    // FAILED was EXECUTING, so RunnerBroker.driveWorkflowFailed silently swallowed the resulting
    // ILLEGAL_TRANSITION for spec-stage runs and they were stranded in INVESTIGATING. The
    // failure-category guard below now admits both EXECUTING and INVESTIGATING as FAILED sources.
    put(
        rules,
        WorkflowState.INVESTIGATING,
        WorkflowState.WAITING_FOR_SPEC_APPROVAL,
        // Story 3d-3 (AC2 / R4) — the spec-stage dispatching state parks here when the run's
        // resolved runner kind is `manual` (no container launched).
        WorkflowState.WAITING_FOR_MANUAL_EXECUTION,
        WorkflowState.FAILED,
        WorkflowState.TAKEN_OVER,
        WorkflowState.RECONCILED);
    put(
        rules,
        WorkflowState.WAITING_FOR_SPEC_APPROVAL,
        WorkflowState.EXECUTING,
        WorkflowState.INVESTIGATING,
        WorkflowState.SPLIT,
        WorkflowState.TAKEN_OVER,
        WorkflowState.RECONCILED);
    put(
        rules,
        WorkflowState.EXECUTING,
        WorkflowState.WAITING_FOR_REVIEW,
        // Story 3d-3 (AC2 / R4) — the execution-stage (plan + pr-output) dispatching state parks
        // here when the run's resolved runner kind is `manual`.
        WorkflowState.WAITING_FOR_MANUAL_EXECUTION,
        // Story 3h-2 (AC4) — a CRITICAL lint finding parks the run at the pre-review lint gate
        // (entered from EXECUTING when the backend-side LINT stage classifies a critical finding).
        WorkflowState.WAITING_FOR_LINT_APPROVAL,
        WorkflowState.FAILED,
        WorkflowState.PAUSED,
        WorkflowState.TAKEN_OVER,
        WorkflowState.RECONCILED);
    put(
        rules,
        WorkflowState.WAITING_FOR_REVIEW,
        WorkflowState.COMPLETED,
        WorkflowState.EXECUTING,
        WorkflowState.SPLIT,
        WorkflowState.TAKEN_OVER,
        WorkflowState.RECONCILED);
    // Story 3d-3 (AC2 / R4) — a parked manual run leaves only on operator submission (3d-4 picks
    // the stage-appropriate target: spec → WaitingForSpecApproval, execution → WaitingForReview) or
    // a recovery/safety edge (Failed / TakenOver / Reconciled). A parked run has no timeout/
    // auto-progress (ADR 0024 Consequences), so the recovery edges keep it from ever wedging. 3d-3
    // declares all OUT edges (additive); 3d-4 triggers the submission ones.
    put(
        rules,
        WorkflowState.WAITING_FOR_MANUAL_EXECUTION,
        WorkflowState.WAITING_FOR_SPEC_APPROVAL,
        WorkflowState.WAITING_FOR_REVIEW,
        WorkflowState.FAILED,
        WorkflowState.TAKEN_OVER,
        WorkflowState.RECONCILED);
    put(rules, WorkflowState.SPLIT, WorkflowState.COMPLETED, WorkflowState.RECONCILED);
    // Story 3f-3 (AC2 / AC6) — the sole out-edge from the dependency-gating state: when the last
    // prerequisite reaches Completed, RunDependencyReleaseService releases the dependent onward
    // into
    // the normal spec-generation path. No direct edges to approval/review/completed states.
    put(
        rules,
        WorkflowState.WAITING_FOR_DEPENDENCIES,
        WorkflowState.INVESTIGATING,
        WorkflowState.RECONCILED);
    // Story 3h-2 (AC4 / AC5) — the pre-review lint gate. approve_lint resumes the delivery tail
    // (-> WaitingForReview); request_lint_fix re-dispatches the implementation runner (->
    // Executing).
    // No -> Failed edge: the lint fix loop is operator-driven and never auto-fails (Decision 3).
    // The
    // recovery/safety edges (TakenOver / Reconciled) keep a parked run from ever wedging.
    put(
        rules,
        WorkflowState.WAITING_FOR_LINT_APPROVAL,
        WorkflowState.WAITING_FOR_REVIEW,
        WorkflowState.EXECUTING,
        WorkflowState.TAKEN_OVER,
        WorkflowState.RECONCILED);
    put(rules, WorkflowState.COMPLETED);
    put(
        rules,
        WorkflowState.FAILED,
        WorkflowState.EXECUTING,
        WorkflowState.INVESTIGATING,
        WorkflowState.TAKEN_OVER,
        WorkflowState.RECONCILED);
    put(
        rules,
        WorkflowState.PAUSED,
        WorkflowState.EXECUTING,
        WorkflowState.TAKEN_OVER,
        WorkflowState.RECONCILED);
    put(rules, WorkflowState.TAKEN_OVER);
    put(rules, WorkflowState.RECONCILED);
    assertCoversAllStates(rules);
    return new WorkflowTransitionTable(Map.copyOf(rules));
  }

  private static void assertCoversAllStates(Map<WorkflowState, Set<WorkflowState>> rules) {
    for (WorkflowState state : WorkflowState.values()) {
      if (!rules.containsKey(state)) {
        throw new IllegalStateException(
            "WorkflowTransitionTable is missing rules entry for state " + state.value());
      }
    }
  }

  public Set<WorkflowState> canonicalStates() {
    return allowedTargets.keySet();
  }

  public Set<WorkflowState> allowedTargetsFrom(WorkflowState source) {
    return allowedTargets.getOrDefault(Objects.requireNonNull(source, "source"), Set.of());
  }

  public void assertTransitionAllowed(
      String runId,
      WorkflowState sourceState,
      WorkflowState targetState,
      FailureCategory failureCategory,
      String reason) {
    Objects.requireNonNull(runId, "runId");
    Objects.requireNonNull(sourceState, "sourceState");
    Objects.requireNonNull(targetState, "targetState");

    if (!allowedTargetsFrom(sourceState).contains(targetState)) {
      throw illegalTransition(
          runId, sourceState, targetState, failureCategory, "target_not_allowed");
    }

    // Story 3a-1 (AC4) — runner failures fail the run from EXECUTING (implementation stage) OR
    // INVESTIGATING (spec stage); both require an allowed runner failure category.
    if (targetState == WorkflowState.FAILED
        && (sourceState == WorkflowState.EXECUTING || sourceState == WorkflowState.INVESTIGATING)) {
      if (failureCategory == null) {
        throw illegalTransition(
            runId, sourceState, targetState, null, "runner_failure_category_required");
      }
      if (!ALLOWED_RUNNER_FAILURE_CATEGORIES.contains(failureCategory)) {
        throw illegalTransition(
            runId,
            sourceState,
            targetState,
            failureCategory,
            "runner_failure_category_not_allowed");
      }
    } else if (failureCategory != null) {
      throw illegalTransition(
          runId,
          sourceState,
          targetState,
          failureCategory,
          "failure_category_only_valid_for_executing_to_failed");
    }

    if ((targetState == WorkflowState.TAKEN_OVER || targetState == WorkflowState.RECONCILED)
        && (reason == null || reason.isBlank())) {
      throw illegalTransition(
          runId, sourceState, targetState, failureCategory, "intervention_reason_required");
    }
  }

  private static void put(
      Map<WorkflowState, Set<WorkflowState>> rules,
      WorkflowState source,
      WorkflowState... targets) {
    rules.put(source, Set.of(targets));
  }

  private static DomainException illegalTransition(
      String runId,
      WorkflowState sourceState,
      WorkflowState targetState,
      FailureCategory failureCategory,
      String reason) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("runId", runId);
    details.put("sourceState", sourceState.value());
    details.put("targetState", targetState.value());
    details.put("reason", reason);
    if (failureCategory != null) {
      details.put("failureCategory", failureCategory.value());
    }
    return new DomainException(
        DomainErrorCode.ILLEGAL_TRANSITION,
        "Illegal workflow transition from " + sourceState.value() + " to " + targetState.value(),
        details);
  }
}
