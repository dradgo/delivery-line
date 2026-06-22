package org.dradgo.domain.registry;

import java.util.Map;

/**
 * Identifies the runner-image flavor the Docker adapter dispatches against. The wire value is the
 * single source of truth for the runner-image lookup key in {@code
 * RunnerProperties.Docker.imageTags} and for the {@code deliveryline.runnerKind} container label
 * (operator forensics).
 *
 * <p>Story 3.1 OQ-3: the kind is resolved server-side from {@code RunnerProperties.docker().
 * defaultKind()} and threaded through {@link org.dradgo.application.runner.RunnerDispatchRequest};
 * the adapter never reads it from the (untrusted) context bundle.
 */
public enum RunnerKind implements RegistryValue {
  CODEX("codex"),
  CLAUDE("claude"),
  // Story 3d-3 (AC1, ADR 0024) — the `manual` kind is a new PRODUCER, not a new runner-contracts
  // schema: instead of launching a container the dispatch chokepoint composes the same input bundle
  // and parks the run in WaitingForManualExecution for an operator to run the agent by hand. It
  // must
  // NEVER reach the Docker image lookup ({@code RunnerProperties.Docker.imageTagFor}) — the park
  // path returns before any adapter/image resolution. Selectable per project via the nullable
  // {@code Project.runnerKind} override (else the global per-stage kind applies).
  MANUAL("manual");

  private static final Map<String, RunnerKind> LOOKUP = RegistryParsers.index(values());

  private final String value;

  RunnerKind(String value) {
    this.value = value;
  }

  @Override
  public String value() {
    return value;
  }

  static RunnerKind fromValue(String rawValue) {
    return fromValue(rawValue, null);
  }

  public static RunnerKind fromValue(String rawValue, String field) {
    return RegistryParsers.parse("RunnerKind", rawValue, field, LOOKUP);
  }
}
