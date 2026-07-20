package org.dradgo.domain.registry;

import java.util.Map;

public enum ReconciliationDecision implements RegistryValue {
  ACCEPT_EXTERNAL_STATE("accept_external_state"),
  ACCEPT_INTERNAL_STATE("accept_internal_state"),
  MARK_COMPLETED_EXTERNALLY("mark_completed_externally"),
  MARK_FAILED_EXTERNALLY("mark_failed_externally");

  private static final Map<String, ReconciliationDecision> LOOKUP = RegistryParsers.index(values());

  private final String value;

  ReconciliationDecision(String value) {
    this.value = value;
  }

  @Override
  public String value() {
    return value;
  }

  public static ReconciliationDecision fromValue(String rawValue, String field) {
    return RegistryParsers.parse("ReconciliationDecision", rawValue, field, LOOKUP);
  }

  public static ReconciliationDecision fromNullableValue(String rawValue, String field) {
    if (rawValue == null || rawValue.isBlank()) {
      return null;
    }
    return fromValue(rawValue, field);
  }
}
