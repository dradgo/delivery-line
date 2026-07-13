package org.dradgo.domain.registry;

import java.util.Map;

/**
 * Classification axis for artifact DB/file drift detected by the story 4.15
 * ArtifactDriftDetectionService sweep — the shape of the disagreement between the recorded artifact
 * state and the durable file/DB reality. This is the value persisted to {@code
 * artifact_drift_detected.drift_category}:
 *
 * <ul>
 *   <li>{@link #ORPHAN_OPERATION} — a stale {@code pending} {@code artifact_operations} row past
 *       the reconciliation threshold (the payload never materialized);
 *   <li>{@link #MISSING_PAYLOAD} — an {@code artifacts.status='available'} row whose {@code
 *       storage_ref} the {@code LocalArtifactStore} can no longer resolve (file deleted after
 *       {@code markAvailable});
 *   <li>{@link #CHECKSUM_MISMATCH} — the payload exists but its recomputed checksum differs from
 *       the stored {@code checksum_value} (on-disk corruption).
 * </ul>
 *
 * <p>Wire form is the {@code value()} (snake_case). Renaming an enum constant must keep the wire
 * value identical or it is a wire-breaking change AND breaks the {@code
 * ck_artifact_drift_detected_drift_category} SQL CHECK alignment (asserted by
 * RegistryContractTest). A persisted value → this crosses the persistence boundary, so {@link
 * PersistedRegistryValues#artifactDriftCategory(String)} is the uniform fail-fast read wrapper.
 */
public enum DriftCategory implements RegistryValue {
  ORPHAN_OPERATION("orphan_operation"),
  MISSING_PAYLOAD("missing_payload"),
  CHECKSUM_MISMATCH("checksum_mismatch");

  private static final Map<String, DriftCategory> LOOKUP = RegistryParsers.index(values());

  private final String value;

  DriftCategory(String value) {
    this.value = value;
  }

  @Override
  public String value() {
    return value;
  }

  static DriftCategory fromValue(String rawValue) {
    return fromValue(rawValue, null);
  }

  public static DriftCategory fromValue(String rawValue, String field) {
    return RegistryParsers.parse("DriftCategory", rawValue, field, LOOKUP);
  }

  public static DriftCategory fromNullableValue(String rawValue, String field) {
    return RegistryParsers.parseNullable("DriftCategory", rawValue, field, LOOKUP);
  }
}
