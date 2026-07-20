package org.dradgo.domain.registry;

import java.util.Map;

public enum FailureCategory implements RegistryValue {
  RUNNER_TIMEOUT("runner_timeout"),
  RUNNER_CRASH("runner_crash"),
  RUNNER_CONTRACT_VIOLATION("runner_contract_violation"),
  RUNNER_NON_ZERO_EXIT("runner_non_zero_exit"),
  RUNNER_LATE_RESULT("runner_late_result"),
  RUNNER_DUPLICATE_RESULT("runner_duplicate_result"),
  RUNNER_MALFORMED_OUTPUT("runner_malformed_output"),
  RUNNER_SECRET_LEAK("runner_secret_leak"),
  // Story 3h-1 (AC5, FR75) — the produced code failed the backend-side build gate after the bounded
  // auto-fix loop exhausted its cap. Carried on the terminal FAILED transition (workflow_events
  // row)
  // for Epic-4 recovery; NOT DomainErrorCode-shaped (no ProblemDetails / SQL CHECK / API manifest).
  RUNNER_BUILD_FAILED("runner_build_failed"),
  // Per-run testcontainers dockerd sidecar could not be provisioned (network/sidecar create or
  // readiness timeout) for an opted-in execution run. Carried on the terminal FAILED transition for
  // Epic-4 recovery; retryable. NOT DomainErrorCode-shaped.
  TESTCONTAINERS_INFRA_FAILED("testcontainers_infra_failed"),
  // Story 4.7 [Review D1] — a recovery rerun-from-step re-enqueue failed AFTER its prep tx
  // committed (transition + approval invalidation). RecoveryService compensates by driving the run
  // to FAILED (a legal retry/rerun source) with this category so the stranding is recoverable and
  // the audit trail is honest (NOT a runner/testcontainers failure). Carried on the terminal FAILED
  // transition; NOT DomainErrorCode-shaped (no ProblemDetails / SQL CHECK / API manifest).
  RECOVERY_DISPATCH_FAILED("recovery_dispatch_failed"),
  ORPHAN("orphan");

  private static final Map<String, FailureCategory> LOOKUP = RegistryParsers.index(values());

  private final String value;

  FailureCategory(String value) {
    this.value = value;
  }

  @Override
  public String value() {
    return value;
  }

  static FailureCategory fromValue(String rawValue) {
    return fromValue(rawValue, null);
  }

  public static FailureCategory fromValue(String rawValue, String field) {
    return RegistryParsers.parse("FailureCategory", rawValue, field, LOOKUP);
  }

  public static FailureCategory fromNullableValue(String rawValue, String field) {
    return RegistryParsers.parseNullable("FailureCategory", rawValue, field, LOOKUP);
  }
}
