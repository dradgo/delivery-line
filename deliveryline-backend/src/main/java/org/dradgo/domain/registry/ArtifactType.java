package org.dradgo.domain.registry;

import java.util.Map;

public enum ArtifactType implements RegistryValue {
  SPEC("spec", DataClassification.SHAREABLE_REDACTED),
  IMPLEMENTATION_PLAN("implementationPlan", DataClassification.SHAREABLE_REDACTED),
  PR_OUTPUT("prOutput", DataClassification.SHAREABLE_REDACTED);

  private static final Map<String, ArtifactType> LOOKUP = RegistryParsers.index(values());

  private final String value;
  private final DataClassification defaultClassification;

  ArtifactType(String value, DataClassification defaultClassification) {
    this.value = value;
    this.defaultClassification = defaultClassification;
  }

  @Override
  public String value() {
    return value;
  }

  public DataClassification defaultClassification() {
    return defaultClassification;
  }

  static ArtifactType fromValue(String rawValue) {
    return fromValue(rawValue, null);
  }

  public static ArtifactType fromValue(String rawValue, String field) {
    return RegistryParsers.parse("ArtifactType", rawValue, field, LOOKUP);
  }
}
