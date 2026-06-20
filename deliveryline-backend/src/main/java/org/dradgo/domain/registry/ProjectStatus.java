package org.dradgo.domain.registry;

import java.util.Map;

public enum ProjectStatus implements RegistryValue {
  ACTIVE("active"),
  DISABLED("disabled");

  private static final Map<String, ProjectStatus> LOOKUP = RegistryParsers.index(values());

  private final String value;

  ProjectStatus(String value) {
    this.value = value;
  }

  @Override
  public String value() {
    return value;
  }

  static ProjectStatus fromValue(String rawValue) {
    return fromValue(rawValue, null);
  }

  public static ProjectStatus fromValue(String rawValue, String field) {
    return RegistryParsers.parse("ProjectStatus", rawValue, field, LOOKUP);
  }
}
