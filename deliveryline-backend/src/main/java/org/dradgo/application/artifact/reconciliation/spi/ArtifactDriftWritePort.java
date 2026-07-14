package org.dradgo.application.artifact.reconciliation.spi;

import java.time.Instant;

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

  /**
   * Story 4.16 (AC2 / Reconciliation 6, AC9) — resolve one drift row, setting {@code resolved_at}
   * and {@code resolved_by_action_id} = the {@code rcv_} public id of the repair's recovery action.
   * Only flips an UNRESOLVED, non-archived row (idempotent-safe {@code resolved_at IS NULL} guard).
   * {@code resolved_by_action_id} FKs {@code recovery_actions(public_id)} RESTRICT, so callers MUST
   * insert the {@code recovery_actions} row FIRST. Restricted by ArchUnit to {@code
   * ArtifactReconciliationService} (AC9).
   *
   * @return {@code true} when a row was resolved; {@code false} when no unresolved row matched.
   */
  boolean resolveDrift(String driftId, String resolvedByActionPublicId, Instant resolvedAt);

  /**
   * Story 4.16 (AC1 / Reconciliation 6, 9) — refresh an UNRESOLVED drift's {@code last_known_state}
   * JSON snapshot without resolving it, used by the {@code reVerifyChecksum} still-mismatched path
   * (the drift is left open, its snapshot updated with the freshly-recomputed digest). Restricted
   * by ArchUnit to {@code ArtifactReconciliationService} (AC9).
   *
   * @return {@code true} when a row was updated; {@code false} when no unresolved row matched.
   */
  boolean updateLastKnownState(String driftId, String lastKnownStateJson);
}
