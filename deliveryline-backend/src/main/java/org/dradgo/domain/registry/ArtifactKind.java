package org.dradgo.domain.registry;

import java.util.Map;

/**
 * Story 3m-2 (AC7 / DD-3) — the typed BMAD artifact kinds, mirroring the {@link ArtifactStatus}
 * registry shape. Persisted to {@code workflow_definition_steps.produces_artifact_kind} (the {@code
 * ck_workflow_definition_steps_artifact_kind} CHECK, nullable) and surfaced through the API, so it
 * is drift-tested against both the DB CHECK and the {@code artifactKinds} API placeholder.
 *
 * <p><strong>DD-3 — vocabulary only:</strong> 3m-2 registers this value set as the definition-time
 * vocabulary. Reconciling it with the runner's fixed 3-token stage/artifactType dispatch contract
 * (which rejects unknown types with exit 13) is 3m-3/3m-5's runtime problem — 3m-2 does not touch
 * {@code RunnerStage} or the runner contract. Wire values are lowercase / snake_case.
 */
public enum ArtifactKind implements RegistryValue {
  BRIEF("brief"),
  PRD("prd"),
  UX_DESIGN("ux_design"),
  ARCHITECTURE("architecture"),
  EPICS("epics"),
  STORY("story"),
  CODE("code"),
  REVIEW("review"),
  RETRO("retro");

  private static final Map<String, ArtifactKind> LOOKUP = RegistryParsers.index(values());

  private final String value;

  ArtifactKind(String value) {
    this.value = value;
  }

  @Override
  public String value() {
    return value;
  }

  static ArtifactKind fromValue(String rawValue) {
    return fromValue(rawValue, null);
  }

  public static ArtifactKind fromValue(String rawValue, String field) {
    return RegistryParsers.parse("ArtifactKind", rawValue, field, LOOKUP);
  }

  /**
   * Nullable-column parser for {@code workflow_definition_steps.produces_artifact_kind} (a step
   * that produces no typed artifact stores NULL). A non-null unknown value still fails fast with
   * {@code UNKNOWN_REGISTRY_VALUE}.
   */
  public static ArtifactKind fromNullableValue(String rawValue, String field) {
    return RegistryParsers.parseNullable("ArtifactKind", rawValue, field, LOOKUP);
  }
}
