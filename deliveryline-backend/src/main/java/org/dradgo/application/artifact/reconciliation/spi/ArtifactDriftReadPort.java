package org.dradgo.application.artifact.reconciliation.spi;

import java.util.List;

/**
 * Story 4.15 (AC5/AC7) — read seam over {@code artifact_drift_detected}. Both methods return only
 * UNRESOLVED, non-archived rows ({@code resolved_at IS NULL AND archived_at IS NULL}); {@code
 * listUnresolved} is {@code detected_at DESC} with a deterministic id tiebreak. Read-only — no
 * mutation crosses this port. The {@code ArtifactReconciliationService.listUnresolvedDrift} read
 * method (in {@code application.artifact}) delegates here and enriches each row with the computed
 * {@code RepairActionHint}.
 */
public interface ArtifactDriftReadPort {

  /** Unresolved drift rows matching {@code query} (all axes optional), newest first. */
  List<DriftRow> listUnresolved(DriftQuery query);

  /**
   * Grouped {@code drift_category} counts of all currently unresolved drift — the gauge snapshot
   * source (AC7). One scan; the binder caches the result per scrape window.
   */
  List<UnresolvedDriftCount> countUnresolvedByCategory();
}
