package org.dradgo.domain.registry;

import java.util.Map;

/**
 * Story 3m-2 (AC7 / DD-1) — the 9 BMAD-method phase keys, mirroring the {@link ReviewOutcome}
 * registry shape. Used by 3m-5's role-prompt catalog + preset seed to validate step keys against a
 * known set.
 *
 * <p><strong>NOT a DB CHECK (DD-1):</strong> {@code workflow_definition_steps.step_key} is free
 * text (custom definitions author arbitrary keys), so {@code BmadStepKey} exists purely as the
 * preset/catalog vocabulary. It is drift-tested registry-vs-API only (the {@code bmadStepKeys} API
 * placeholder), never against a DB CHECK.
 */
public enum BmadStepKey implements RegistryValue {
  ANALYST("analyst"),
  PM("pm"),
  UX("ux"),
  ARCHITECT("architect"),
  EPICS("epics"),
  STORY("story"),
  DEV("dev"),
  REVIEW("review"),
  RETRO("retro");

  private static final Map<String, BmadStepKey> LOOKUP = RegistryParsers.index(values());

  private final String value;

  BmadStepKey(String value) {
    this.value = value;
  }

  @Override
  public String value() {
    return value;
  }

  static BmadStepKey fromValue(String rawValue) {
    return fromValue(rawValue, null);
  }

  public static BmadStepKey fromValue(String rawValue, String field) {
    return RegistryParsers.parse("BmadStepKey", rawValue, field, LOOKUP);
  }
}
