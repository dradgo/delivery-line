package org.dradgo.domain.registry;

import java.util.Map;

public enum WorkflowState implements RegistryValue {
  INBOX("Inbox"),
  PLANNED("Planned"),
  INVESTIGATING("Investigating"),
  WAITING_FOR_SPEC_APPROVAL("WaitingForSpecApproval"),
  EXECUTING("Executing"),
  WAITING_FOR_REVIEW("WaitingForReview"),
  // Story 3f-2: non-terminal decomposed state. Reached by split commit (3f-5) from
  // WaitingForSpecApproval/WaitingForReview; sole out-edge Split -> Completed is driven by
  // the 3f-7 completion rollup.
  SPLIT("Split"),
  // Story 3d-3 (AC2, ADR 0024 D2) — a run dispatched under the `manual` runner kind parks HERE
  // instead of launching a container. Reached from the two dispatching states (INVESTIGATING /
  // EXECUTING); it has no timeout/auto-progress, so it leaves only on operator submission (3d-4 →
  // WaitingForSpecApproval / WaitingForReview) or a recovery/safety edge (Failed / TakenOver /
  // Reconciled).
  WAITING_FOR_MANUAL_EXECUTION("WaitingForManualExecution"),
  // Story 3f-3 (AC2 / FR71): non-terminal gating state. A run with unmet run-dependency
  // prerequisites parks HERE instead of dispatching (entered from INBOX). It leaves only when its
  // last prerequisite reaches Completed and RunDependencyReleaseService transitions it onward
  // (WAITING_FOR_DEPENDENCIES -> INVESTIGATING). A run with zero/satisfied prerequisites never
  // enters this state. Wire value is PascalCase, matching the other states.
  WAITING_FOR_DEPENDENCIES("WaitingForDependencies"),
  // Story 3h-2 (AC4 / FR76): non-terminal lint-gate state. A run with a CRITICAL lint finding parks
  // HERE (entered from EXECUTING) BEFORE any LLM review or push runs. It leaves only on the
  // operator
  // gate actions — approve_lint (-> WaitingForReview, resuming the delivery tail) or
  // request_lint_fix
  // (-> Executing, re-dispatching the implementation runner with the findings as feedback) — or a
  // recovery/safety edge (TakenOver / Reconciled). There is NO -> Failed edge: the lint fix loop is
  // operator-driven and never auto-fails (Decision 3). Wire value is PascalCase, matching the other
  // states.
  WAITING_FOR_LINT_APPROVAL("WaitingForLintApproval"),
  COMPLETED("Completed"),
  FAILED("Failed"),
  PAUSED("Paused"),
  TAKEN_OVER("TakenOver"),
  RECONCILED("Reconciled");

  private static final Map<String, WorkflowState> LOOKUP = RegistryParsers.index(values());

  private final String value;

  WorkflowState(String value) {
    this.value = value;
  }

  @Override
  public String value() {
    return value;
  }

  static WorkflowState fromValue(String rawValue) {
    return fromValue(rawValue, null);
  }

  public static WorkflowState fromValue(String rawValue, String field) {
    return RegistryParsers.parse("WorkflowState", rawValue, field, LOOKUP);
  }

  public static WorkflowState fromNullableValue(String rawValue, String field) {
    return RegistryParsers.parseNullable("WorkflowState", rawValue, field, LOOKUP);
  }
}
