package org.dradgo.domain.registry;

import java.util.Map;

public enum ConnectorKind implements RegistryValue {
  LINEAR("linear"),
  GITHUB("github");

  private static final Map<String, ConnectorKind> LOOKUP = RegistryParsers.index(values());

  private final String value;

  ConnectorKind(String value) {
    this.value = value;
  }

  @Override
  public String value() {
    return value;
  }

  static ConnectorKind fromValue(String rawValue) {
    return fromValue(rawValue, null);
  }

  public static ConnectorKind fromValue(String rawValue, String field) {
    return RegistryParsers.parse("ConnectorKind", rawValue, field, LOOKUP);
  }
}
