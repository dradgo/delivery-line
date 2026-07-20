package org.dradgo.domain.registry;

import java.util.Map;

/**
 * Classification axis for integration-drift conflicts detected by the story 4.17
 * IntegrationConflictDetectionService sweep — the shape of the disagreement between internal
 * workflow state and the cached-vs-fresh EXTERNAL (Linear ticket / GitHub PR) state. Distinct from
 * {@link IntegrationFailureCategory}, which classifies the external-API CONVERSATION failure (the
 * how-it-failed); this enum classifies the detected drift OUTCOME (the what-diverged) and is the
 * value persisted to {@code integration_conflicts.conflict_category}.
 *
 * <p>Wire form is the {@code value()} (snake_case). Renaming an enum constant must keep the wire
 * value identical or it is a wire-breaking change AND breaks the {@code
 * ck_integration_conflicts_conflict_category} SQL CHECK alignment. GitHub carries all five
 * categories; Linear reliably carries only {@link #EXTERNAL_RESOURCE_REMOVED} + {@link
 * #LINK_BROKEN} today (state-drift categories are provisional pending a persisted {@code
 * sourceStatusId} baseline).
 */
public enum IntegrationConflictCategory implements RegistryValue {
  EXTERNAL_STATE_ADVANCED("external_state_advanced"),
  EXTERNAL_STATE_REVERTED("external_state_reverted"),
  EXTERNAL_RESOURCE_REMOVED("external_resource_removed"),
  METADATA_DRIFT("metadata_drift"),
  LINK_BROKEN("link_broken");

  private static final Map<String, IntegrationConflictCategory> LOOKUP =
      RegistryParsers.index(values());

  private final String value;

  IntegrationConflictCategory(String value) {
    this.value = value;
  }

  @Override
  public String value() {
    return value;
  }

  static IntegrationConflictCategory fromValue(String rawValue) {
    return fromValue(rawValue, null);
  }

  public static IntegrationConflictCategory fromValue(String rawValue, String field) {
    return RegistryParsers.parse("IntegrationConflictCategory", rawValue, field, LOOKUP);
  }

  public static IntegrationConflictCategory fromNullableValue(String rawValue, String field) {
    return RegistryParsers.parseNullable("IntegrationConflictCategory", rawValue, field, LOOKUP);
  }
}
