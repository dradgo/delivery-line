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
  CLAUDE("claude");

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
