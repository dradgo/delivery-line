package org.dradgo.application.artifact.reconciliation.spi;

/**
 * Story 4.15 (AC1/AC9) — the write seam for the {@code artifact_drift_detected} table. Only {@code
 * application.artifact.reconciliation} may depend on this port (ArchUnit AC9); the
 * {@code @Scheduled} trigger in {@code infrastructure.config} only delegates to the detection
 * service.
 */
public interface ArtifactDriftWritePort {

  /**
   * Insert one drift row, skipping silently when an UNRESOLVED, non-archived drift of the same
   * {@code (drift_category, artifact_id, artifact_operation_id)} already exists — the {@code
   * uq_artifact_drift_detected_active} partial-unique dedup index. Implemented with {@code INSERT …
   * ON CONFLICT DO NOTHING} so a standing drift never poisons the caller's transaction (contrast a
   * caught unique-violation, which does — the poisoned-shared-tx trap).
   *
   * @return {@code true} when a NEW row was inserted (the caller then increments the counter +
   *     emits {@code artifact.driftDetected}); {@code false} when the dedup index skipped the
   *     insert.
   */
  boolean recordIfAbsent(DriftRecordRequest request);
}
