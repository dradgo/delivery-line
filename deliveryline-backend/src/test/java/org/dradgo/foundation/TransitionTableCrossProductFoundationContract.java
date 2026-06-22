package org.dradgo.foundation;

import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.dradgo.application.workflow.WorkflowTransitionTable;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.FailureCategory;
import org.dradgo.domain.registry.WorkflowEventType;
import org.dradgo.domain.registry.WorkflowState;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Foundation contract #4 supplement.
 *
 * <p>This contract proves the transition table against an explicit matrix owned by the test, not
 * the production table itself. That keeps the foundation gate useful when the SUT changes
 * incorrectly.
 *
 * <p>It also verifies the two transition modifiers the table enforces:
 *
 * <ol>
 *   <li>{@code failureCategory} is required only for {@code EXECUTING -> FAILED} and only from the
 *       allowed runner-failure subset.
 *   <li>{@code reason} is required for {@code TAKEN_OVER} and {@code RECONCILED} targets.
 * </ol>
 */
@Tag("foundation-gate")
class TransitionTableCrossProductFoundationContract {

  private static final Map<WorkflowState, Set<WorkflowState>> EXPECTED_ALLOWED_TARGETS =
      Map.ofEntries(
          // Story 3a-1 (ADR 0004 §Decision-1 / OQ-1) — Inbox gains a direct Investigating edge.
          Map.entry(
              WorkflowState.INBOX,
              EnumSet.of(
                  WorkflowState.PLANNED,
                  WorkflowState.INVESTIGATING,
                  WorkflowState.TAKEN_OVER,
                  WorkflowState.RECONCILED)),
          Map.entry(
              WorkflowState.PLANNED,
              EnumSet.of(
                  WorkflowState.INVESTIGATING, WorkflowState.TAKEN_OVER, WorkflowState.RECONCILED)),
          // Story 3a-1 (AC4 / AC8) — Investigating gains a Failed edge for spec-stage runner
          // failures.
          // Story 3d-3 (AC2 / R4) — the spec-stage dispatching state parks in
          // WaitingForManualExecution when the resolved runner kind is `manual`.
          Map.entry(
              WorkflowState.INVESTIGATING,
              EnumSet.of(
                  WorkflowState.WAITING_FOR_SPEC_APPROVAL,
                  WorkflowState.WAITING_FOR_MANUAL_EXECUTION,
                  WorkflowState.FAILED,
                  WorkflowState.TAKEN_OVER,
                  WorkflowState.RECONCILED)),
          Map.entry(
              WorkflowState.WAITING_FOR_SPEC_APPROVAL,
              EnumSet.of(
                  WorkflowState.EXECUTING,
                  WorkflowState.INVESTIGATING,
                  WorkflowState.TAKEN_OVER,
                  WorkflowState.RECONCILED)),
          // Story 3d-3 (AC2 / R4) — the execution-stage dispatching state parks in
          // WaitingForManualExecution when the resolved runner kind is `manual`.
          Map.entry(
              WorkflowState.EXECUTING,
              EnumSet.of(
                  WorkflowState.WAITING_FOR_REVIEW,
                  WorkflowState.WAITING_FOR_MANUAL_EXECUTION,
                  WorkflowState.FAILED,
                  WorkflowState.PAUSED,
                  WorkflowState.TAKEN_OVER,
                  WorkflowState.RECONCILED)),
          Map.entry(
              WorkflowState.WAITING_FOR_REVIEW,
              EnumSet.of(
                  WorkflowState.COMPLETED,
                  WorkflowState.EXECUTING,
                  WorkflowState.TAKEN_OVER,
                  WorkflowState.RECONCILED)),
          // Story 3d-3 (AC2 / R4) — a parked manual run leaves only on operator submission (3d-4
          // picks the stage-appropriate target: spec → WaitingForSpecApproval, execution →
          // WaitingForReview) or a recovery/safety edge (Failed / TakenOver / Reconciled).
          Map.entry(
              WorkflowState.WAITING_FOR_MANUAL_EXECUTION,
              EnumSet.of(
                  WorkflowState.WAITING_FOR_SPEC_APPROVAL,
                  WorkflowState.WAITING_FOR_REVIEW,
                  WorkflowState.FAILED,
                  WorkflowState.TAKEN_OVER,
                  WorkflowState.RECONCILED)),
          Map.entry(WorkflowState.COMPLETED, EnumSet.noneOf(WorkflowState.class)),
          Map.entry(
              WorkflowState.FAILED,
              EnumSet.of(
                  WorkflowState.EXECUTING,
                  WorkflowState.INVESTIGATING,
                  WorkflowState.TAKEN_OVER,
                  WorkflowState.RECONCILED)),
          Map.entry(
              WorkflowState.PAUSED,
              EnumSet.of(
                  WorkflowState.EXECUTING, WorkflowState.TAKEN_OVER, WorkflowState.RECONCILED)),
          Map.entry(WorkflowState.TAKEN_OVER, EnumSet.noneOf(WorkflowState.class)),
          Map.entry(WorkflowState.RECONCILED, EnumSet.noneOf(WorkflowState.class)));

  private static final Set<FailureCategory> ALLOWED_RUNNER_FAILURE_CATEGORIES =
      Set.of(
          FailureCategory.RUNNER_TIMEOUT,
          FailureCategory.RUNNER_CRASH,
          FailureCategory.RUNNER_CONTRACT_VIOLATION,
          FailureCategory.RUNNER_NON_ZERO_EXIT);

  @Test
  void everyIllegalTransitionRaisesIllegalTransitionAndEveryLegalTransitionPasses() {
    WorkflowTransitionTable table = WorkflowTransitionTable.defaultTable();
    List<String> violations = new ArrayList<>();

    for (WorkflowState prior : WorkflowState.values()) {
      for (WorkflowState target : WorkflowState.values()) {
        boolean expectedLegal =
            EXPECTED_ALLOWED_TARGETS.getOrDefault(prior, Set.of()).contains(target);
        // Story 3a-1 (AC4): FAILED is reachable from EXECUTING (impl stage) and INVESTIGATING
        // (spec stage); both require an allowed runner failure category.
        FailureCategory failureCategory =
            (target == WorkflowState.FAILED
                    && (prior == WorkflowState.EXECUTING || prior == WorkflowState.INVESTIGATING))
                ? FailureCategory.RUNNER_CRASH
                : null;
        String reason =
            (target == WorkflowState.TAKEN_OVER || target == WorkflowState.RECONCILED)
                ? "foundation-gate cross-product probe"
                : null;
        try {
          table.assertTransitionAllowed(
              "run_foundation_gate_probe", prior, target, failureCategory, reason);
          if (!expectedLegal) {
            violations.add(
                prior.value()
                    + " -> "
                    + target.value()
                    + " was accepted but is not in the explicit legal matrix");
          }
        } catch (DomainException domainException) {
          if (expectedLegal) {
            violations.add(
                prior.value()
                    + " -> "
                    + target.value()
                    + " is legal but raised "
                    + domainException.errorCode().value()
                    + ": "
                    + domainException.getMessage());
          } else if (domainException.errorCode() != DomainErrorCode.ILLEGAL_TRANSITION) {
            violations.add(
                prior.value()
                    + " -> "
                    + target.value()
                    + " raised "
                    + domainException.errorCode().value()
                    + ", expected ILLEGAL_TRANSITION");
          }
        }
      }
    }

    if (!violations.isEmpty()) {
      fail(
          FoundationGateAssertions.tagged(
              "1.5",
              "legal-table cross-product mismatch ("
                  + violations.size()
                  + " violation"
                  + (violations.size() == 1 ? "" : "s")
                  + "): "
                  + String.join("; ", violations)));
    }
  }

  @Test
  void failureCategoryEnforcementIsExhaustiveAcrossEveryLegalTransition() {
    WorkflowTransitionTable table = WorkflowTransitionTable.defaultTable();
    List<String> violations = new ArrayList<>();

    for (WorkflowState prior : WorkflowState.values()) {
      for (WorkflowState target : EXPECTED_ALLOWED_TARGETS.getOrDefault(prior, Set.of())) {
        // Story 3a-1 (AC4): both EXECUTING -> FAILED and INVESTIGATING -> FAILED carry a runner
        // failure category; every other legal transition must reject any category.
        boolean isRunnerFailureTransition =
            target == WorkflowState.FAILED
                && (prior == WorkflowState.EXECUTING || prior == WorkflowState.INVESTIGATING);
        String edge = prior.value() + " -> " + target.value();
        String reason =
            (target == WorkflowState.TAKEN_OVER || target == WorkflowState.RECONCILED)
                ? "foundation-gate failure-category probe"
                : null;

        if (!isRunnerFailureTransition) {
          for (FailureCategory category : FailureCategory.values()) {
            try {
              table.assertTransitionAllowed(
                  "run_foundation_gate_probe", prior, target, category, reason);
              violations.add(
                  prior.value()
                      + " -> "
                      + target.value()
                      + " accepted non-null failureCategory="
                      + category.value()
                      + " - only EXECUTING/INVESTIGATING -> FAILED may carry one");
            } catch (DomainException expected) {
              if (expected.errorCode() != DomainErrorCode.ILLEGAL_TRANSITION) {
                violations.add(
                    prior.value()
                        + " -> "
                        + target.value()
                        + " with failureCategory="
                        + category.value()
                        + " raised "
                        + expected.errorCode().value()
                        + ", expected ILLEGAL_TRANSITION");
              }
            }
          }
        }

        if (isRunnerFailureTransition) {
          try {
            table.assertTransitionAllowed("run_foundation_gate_probe", prior, target, null, reason);
            violations.add(edge + " accepted null failureCategory");
          } catch (DomainException expected) {
            if (expected.errorCode() != DomainErrorCode.ILLEGAL_TRANSITION) {
              violations.add(
                  edge
                      + " with null failureCategory raised "
                      + expected.errorCode().value()
                      + ", expected ILLEGAL_TRANSITION");
            }
          }
          for (FailureCategory category : FailureCategory.values()) {
            boolean expectedAllowed = ALLOWED_RUNNER_FAILURE_CATEGORIES.contains(category);
            try {
              table.assertTransitionAllowed(
                  "run_foundation_gate_probe", prior, target, category, reason);
              if (!expectedAllowed) {
                violations.add(
                    edge
                        + " accepted non-runner failureCategory="
                        + category.value()
                        + " - only "
                        + ALLOWED_RUNNER_FAILURE_CATEGORIES
                        + " are valid here");
              }
            } catch (DomainException expected) {
              if (expectedAllowed) {
                violations.add(
                    edge
                        + " rejected allowed failureCategory="
                        + category.value()
                        + " with "
                        + expected.errorCode().value());
              } else if (expected.errorCode() != DomainErrorCode.ILLEGAL_TRANSITION) {
                violations.add(
                    edge
                        + " with failureCategory="
                        + category.value()
                        + " raised "
                        + expected.errorCode().value()
                        + ", expected ILLEGAL_TRANSITION");
              }
            }
          }
        }
      }
    }

    if (!violations.isEmpty()) {
      fail(
          FoundationGateAssertions.tagged(
              "1.5",
              "failureCategory enforcement gaps ("
                  + violations.size()
                  + "): "
                  + String.join("; ", violations)));
    }
  }

  @Test
  void interventionReasonIsRequiredForTakeoverAndReconciledTargets() {
    WorkflowTransitionTable table = WorkflowTransitionTable.defaultTable();
    List<String> violations = new ArrayList<>();

    for (WorkflowState prior : WorkflowState.values()) {
      for (WorkflowState target : EXPECTED_ALLOWED_TARGETS.getOrDefault(prior, Set.of())) {
        if (target != WorkflowState.TAKEN_OVER && target != WorkflowState.RECONCILED) {
          continue;
        }
        for (String reason : new String[] {null, "", "   "}) {
          try {
            table.assertTransitionAllowed("run_foundation_gate_probe", prior, target, null, reason);
            violations.add(
                prior.value()
                    + " -> "
                    + target.value()
                    + " accepted blank reason="
                    + (reason == null ? "null" : "'" + reason + "'"));
          } catch (DomainException expected) {
            if (expected.errorCode() != DomainErrorCode.ILLEGAL_TRANSITION) {
              violations.add(
                  prior.value()
                      + " -> "
                      + target.value()
                      + " with reason="
                      + (reason == null ? "null" : "'" + reason + "'")
                      + " raised "
                      + expected.errorCode().value()
                      + ", expected ILLEGAL_TRANSITION");
            }
          }
        }
      }
    }

    if (!violations.isEmpty()) {
      fail(
          FoundationGateAssertions.tagged(
              "1.5",
              "intervention-reason enforcement gaps ("
                  + violations.size()
                  + "): "
                  + String.join("; ", violations)));
    }
  }

  @Test
  void everyWorkflowEventTypeValueParsesAndStateChangeEventIsRepresented() {
    List<String> violations = new ArrayList<>();
    for (WorkflowEventType eventType : WorkflowEventType.values()) {
      WorkflowEventType parsed = WorkflowEventType.fromValue(eventType.value(), "eventType");
      if (parsed != eventType) {
        violations.add(
            "WorkflowEventType."
                + eventType.name()
                + " (value="
                + eventType.value()
                + ") round-trips to "
                + parsed);
      }
    }
    boolean hasStateChangingTransition =
        EXPECTED_ALLOWED_TARGETS.values().stream().anyMatch(targets -> !targets.isEmpty());
    if (!hasStateChangingTransition) {
      violations.add(
          "WorkflowEventType."
              + WorkflowEventType.WORKFLOW_STATE_CHANGED.name()
              + " has no legal transition in the explicit matrix");
    }
    if (!violations.isEmpty()) {
      fail(
          FoundationGateAssertions.tagged(
              "1.5",
              "WorkflowEventType registry coverage gaps ("
                  + violations.size()
                  + "): "
                  + String.join("; ", violations)));
    }
  }
}
