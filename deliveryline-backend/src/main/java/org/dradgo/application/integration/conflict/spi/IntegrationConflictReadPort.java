package org.dradgo.application.integration.conflict.spi;

import java.util.List;
import java.util.Optional;
import org.dradgo.application.integration.conflict.ConflictFilter;
import org.dradgo.application.integration.conflict.ConflictResolutionView;
import org.dradgo.application.integration.conflict.ConflictSummary;

/**
 * Story 4.17 (AC6/AC7) — read seam over {@code integration_conflicts}. Both methods return only
 * UNRESOLVED, non-archived rows ({@code resolved_at IS NULL AND archived_at IS NULL}); {@code
 * listUnresolved} is {@code detected_at DESC} with a deterministic id tiebreak (mirrors {@code
 * ClarificationReadPort} archived-filtered ordering). Read-only — no mutation crosses this port.
 */
public interface IntegrationConflictReadPort {

  /** Unresolved conflicts matching {@code filter} (all axes optional), newest first. */
  List<ConflictSummary> listUnresolved(ConflictFilter filter);

  Optional<ConflictResolutionView> findByPublicId(String conflictPublicId);

  /**
   * Grouped {@code (category, integration)} counts of all currently unresolved conflicts — the
   * gauge snapshot source (AC7). One scan; the binder caches the result per scrape window.
   */
  List<UnresolvedConflictCount> countUnresolvedByCategoryAndIntegration();
}
