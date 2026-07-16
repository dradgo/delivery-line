package org.dradgo.application.artifact;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Story 4.16a (AC2 / Reconciliation 10) — the typed operator lineage-recovery action selected for
 * an ambiguous/orphaned artifact. Wire form is the snake_case {@link #value()}; surfaced to OpenAPI
 * as an inline {@code @Schema(allowableValues=…)} string (NOT a persisted {@code domain.registry}
 * value — no {@code RegistryContractTest} drift check), and stored only as the {@code
 * lineageAction} event-detail token on the single {@code artifact.lineageReconciled} event.
 *
 * <p>Unlike {@link RepairAction} there is NO category-legality dimension: every typed action is
 * always applicable to an artifact (the operator, not the drift category, chooses the resolution).
 * An unknown/blank wire token therefore raises {@code INVALID_LINEAGE_RECOVERY_ACTION}; action
 * required-field validation ({@code reattach_to_existing_lineage} needs a chosen parent) raises
 * {@code MISSING_LINEAGE_RECOVERY_FIELD}.
 */
public enum LineageAction {
  REATTACH_TO_EXISTING_LINEAGE("reattach_to_existing_lineage"),
  TERMINATE_AMBIGUOUS_LINEAGE("terminate_ambiguous_lineage"),
  CREATE_EXPLICIT_FORK("create_explicit_fork");

  private final String value;

  LineageAction(String value) {
    this.value = value;
  }

  public String value() {
    return value;
  }

  /** Parse a wire token, returning empty for null/blank/unknown values (the service maps this). */
  public static Optional<LineageAction> fromWire(String rawValue) {
    if (rawValue == null || rawValue.isBlank()) {
      return Optional.empty();
    }
    String trimmed = rawValue.trim();
    for (LineageAction action : values()) {
      if (action.value.equals(trimmed)) {
        return Optional.of(action);
      }
    }
    return Optional.empty();
  }

  /** All wire tokens, in declaration order — the OpenAPI {@code allowableValues} source. */
  public static List<String> allWireValues() {
    List<String> out = new ArrayList<>(values().length);
    for (LineageAction action : values()) {
      out.add(action.value);
    }
    return List.copyOf(out);
  }
}
