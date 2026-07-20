package org.dradgo.domain.registry;

import java.util.Map;

/**
 * Story 3m-2 (AC2/AC7) — the kind of a workflow definition, mirroring the {@link
 * ArtifactStatus}/{@link ReviewOutcome} registry shape. Persisted to {@code
 * workflow_definitions.kind} (the {@code ck_workflow_definitions_kind} CHECK) and surfaced through
 * the API, so it is drift-tested against both the DB CHECK and the {@code definitionKinds} API
 * placeholder.
 *
 * <p>{@code builtin} is the immutable shipped preset (the BMAD-method definition seeded by 3m-5);
 * {@code custom} is a user-authored definition (3m-9). Wire values are lowercase.
 */
public enum DefinitionKind implements RegistryValue {
  BUILTIN("builtin"),
  CUSTOM("custom");

  private static final Map<String, DefinitionKind> LOOKUP = RegistryParsers.index(values());

  private final String value;

  DefinitionKind(String value) {
    this.value = value;
  }

  @Override
  public String value() {
    return value;
  }

  static DefinitionKind fromValue(String rawValue) {
    return fromValue(rawValue, null);
  }

  public static DefinitionKind fromValue(String rawValue, String field) {
    return RegistryParsers.parse("DefinitionKind", rawValue, field, LOOKUP);
  }
}
