package org.dradgo.domain.registry;

import java.util.Map;

/**
 * Story 4.7 (AC2/AC3, Reconciliation 4) — the safe step boundaries an operator may rerun a workflow
 * run from via {@code RecoveryService.rerunFromStep}. Deliberately constrained to the two
 * deeper-than-retry rerun targets: {@code investigating} (re-spec) and {@code executing}
 * (re-implement). Rerunning from earlier steps (e.g. {@code Inbox}, which would lose the run, or
 * {@code WaitingForSpecApproval}, which would lose the PM's prior approval) is out of scope — see
 * {@code docs/adr/0034-rerun-safe-boundaries.md}.
 *
 * <p>Shape-identical to {@link ReconciliationDecision}: a flat {@link RegistryValue} enum with a
 * {@code LOOKUP} index and {@code fromValue}/{@code fromNullableValue} parsers. NOT
 * SQL-CHECK-backed and carries NO API-schema placeholder in this story (the OpenAPI enum schema is
 * story 4.12's concern); {@code RecoveryService} maps each value to the matching {@link
 * WorkflowState}.
 */
public enum SafeRerunStep implements RegistryValue {
  INVESTIGATING("investigating"),
  EXECUTING("executing");

  private static final Map<String, SafeRerunStep> LOOKUP = RegistryParsers.index(values());

  private final String value;

  SafeRerunStep(String value) {
    this.value = value;
  }

  @Override
  public String value() {
    return value;
  }

  public static SafeRerunStep fromValue(String rawValue, String field) {
    return RegistryParsers.parse("SafeRerunStep", rawValue, field, LOOKUP);
  }

  public static SafeRerunStep fromNullableValue(String rawValue, String field) {
    if (rawValue == null || rawValue.isBlank()) {
      return null;
    }
    return fromValue(rawValue, field);
  }
}
