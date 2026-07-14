package org.dradgo.application.artifact;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.dradgo.domain.registry.DriftCategory;

/**
 * Story 4.16 (AC1/AC5/AC6 / Reconciliation 12) — the typed operator repair action selected for a
 * detected artifact drift. Wire form is the snake_case {@link #value()}; surfaced to OpenAPI as an
 * inline {@code @Schema(allowableValues=…)} string (NOT a persisted {@code domain.registry} value —
 * no {@code RegistryContractTest} drift check), and stored only as the {@code repairAction}
 * event-detail token on the single {@code artifact.driftRepaired} event.
 *
 * <p>Category legality mirrors story 4.15's {@code RepairActionHint}: an {@code orphan_operation}
 * drift accepts {@code mark_operation_failed}/{@code mark_operation_complete}; a {@code
 * missing_payload} drift accepts {@code mark_payload_unavailable}/{@code restore_from_backup}; a
 * {@code checksum_mismatch} drift accepts {@code re_verify_checksum}/{@code mark_corrupted}. A
 * repair action that is not legal for the drift's category raises {@code
 * INVALID_REPAIR_ACTION_FOR_DRIFT_CATEGORY}.
 */
public enum RepairAction {
  MARK_OPERATION_FAILED("mark_operation_failed"),
  MARK_OPERATION_COMPLETE("mark_operation_complete"),
  MARK_PAYLOAD_UNAVAILABLE("mark_payload_unavailable"),
  RESTORE_FROM_BACKUP("restore_from_backup"),
  MARK_CORRUPTED("mark_corrupted"),
  RE_VERIFY_CHECKSUM("re_verify_checksum");

  private static final Map<DriftCategory, Set<RepairAction>> LEGAL_BY_CATEGORY;

  static {
    Map<DriftCategory, Set<RepairAction>> legal = new EnumMap<>(DriftCategory.class);
    legal.put(
        DriftCategory.ORPHAN_OPERATION, EnumSet.of(MARK_OPERATION_FAILED, MARK_OPERATION_COMPLETE));
    legal.put(
        DriftCategory.MISSING_PAYLOAD, EnumSet.of(MARK_PAYLOAD_UNAVAILABLE, RESTORE_FROM_BACKUP));
    legal.put(DriftCategory.CHECKSUM_MISMATCH, EnumSet.of(RE_VERIFY_CHECKSUM, MARK_CORRUPTED));
    LEGAL_BY_CATEGORY = Map.copyOf(legal);
  }

  private final String value;

  RepairAction(String value) {
    this.value = value;
  }

  public String value() {
    return value;
  }

  /** Parse a wire token, returning empty for null/blank/unknown values (the service maps this). */
  public static Optional<RepairAction> fromWire(String rawValue) {
    if (rawValue == null || rawValue.isBlank()) {
      return Optional.empty();
    }
    String trimmed = rawValue.trim();
    for (RepairAction action : values()) {
      if (action.value.equals(trimmed)) {
        return Optional.of(action);
      }
    }
    return Optional.empty();
  }

  public static boolean isLegalFor(DriftCategory category, RepairAction action) {
    Set<RepairAction> legal = LEGAL_BY_CATEGORY.get(category);
    return legal != null && legal.contains(action);
  }

  /**
   * The wire tokens legal for {@code category}, for the {@code INVALID_…} error's detail payload.
   */
  public static List<String> legalWireValuesFor(DriftCategory category) {
    Set<RepairAction> legal = LEGAL_BY_CATEGORY.getOrDefault(category, Set.of());
    List<String> out = new ArrayList<>(legal.size());
    for (RepairAction action : values()) {
      if (legal.contains(action)) {
        out.add(action.value);
      }
    }
    return List.copyOf(out);
  }

  /** All wire tokens, in declaration order — the OpenAPI {@code allowableValues} source. */
  public static List<String> allWireValues() {
    List<String> out = new ArrayList<>(values().length);
    for (RepairAction action : values()) {
      out.add(action.value);
    }
    return List.copyOf(out);
  }
}
