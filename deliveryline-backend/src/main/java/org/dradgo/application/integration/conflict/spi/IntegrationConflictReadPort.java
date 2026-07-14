package org.dradgo.application.integration.conflict.spi;

import java.util.Collection;
import java.util.List;
import java.util.Map;
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

  /**
   * Story 4.18 (AC2) — the keyset-paginated conflict list for {@code GET
   * /api/v1/integration-conflicts}. Ordered {@code detected_at DESC, id DESC}; honors {@code
   * query.resolved()} (three-valued), the filter axes, and the opaque cursor. Returns up to {@code
   * query.limit()} rows (the service passes {@code pageSize + 1} to detect a next page). Unlike
   * {@link #listUnresolved} this may include resolved rows.
   */
  List<ConflictSummary> listConflicts(ConflictListQuery query);

  /**
   * Story 4.18 (AC2) — count of all currently RESOLVED, non-archived conflicts (the {@code
   * totalResolved} response field). The unresolved counts come from {@link
   * #countUnresolvedByCategoryAndIntegration()}.
   */
  long countResolved();

  /**
   * Story 4.18 (AC1) — grouped unresolved-conflict count per run for the operator-queue indicator.
   * ONE query over the page's run ids (never per-row — that would N+1 the hot queue path). Runs
   * with zero unresolved conflicts are absent from the map. An empty/blank {@code workflowRunIds}
   * yields an empty map without touching the DB.
   */
  Map<String, Integer> unresolvedCountByRun(Collection<String> workflowRunIds);
}
