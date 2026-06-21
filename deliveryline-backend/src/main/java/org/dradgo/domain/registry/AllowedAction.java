package org.dradgo.domain.registry;

import java.util.Map;

public enum AllowedAction implements RegistryValue {
  APPROVE_SPEC("approve_spec"),
  REJECT_SPEC("reject_spec"),
  ANSWER_CLARIFICATION("answer_clarification"),
  VIEW_ONLY("view_only"),
  AWAIT_OUTCOME("await_outcome"),
  RETRY("retry"),
  VIEW_DIAGNOSTICS("view_diagnostics"),
  CLEAR_ESCALATION_MARKER("clear_escalation_marker"),
  ACCEPT_IMPLEMENTATION("accept_implementation"),
  REJECT_IMPLEMENTATION("reject_implementation"),
  // Story 3.22 (AC9) — developer takeover; canonical executor is DeveloperTakeoverService. Offered
  // (with accept/reject) for state=WAITING_FOR_REVIEW + role=developer. After TakenOver is reached
  // the only allowed action is view_only (already wired).
  TAKEOVER_WORKFLOW("takeover_workflow"),
  // Story 3d-5 (FR65, AC6) — gate for the Step Execution Log Viewer (live + historical runner
  // logs). Role-agnostic: offered in every state where a runner execution exists (EXECUTING,
  // FAILED, PAUSED, WAITING_FOR_REVIEW). The stream endpoint resolves the latest rex and returns a
  // graceful "no-runner-execution" end when none exists, so broad state coverage is safe.
  VIEW_RUNNER_LOGS("view_runner_logs");

  private static final Map<String, AllowedAction> LOOKUP = RegistryParsers.index(values());

  private final String value;

  AllowedAction(String value) {
    this.value = value;
  }

  @Override
  public String value() {
    return value;
  }

  static AllowedAction fromValue(String rawValue) {
    return fromValue(rawValue, null);
  }

  public static AllowedAction fromValue(String rawValue, String field) {
    return RegistryParsers.parse("AllowedAction", rawValue, field, LOOKUP);
  }
}
