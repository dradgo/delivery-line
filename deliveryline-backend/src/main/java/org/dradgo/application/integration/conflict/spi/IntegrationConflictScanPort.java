package org.dradgo.application.integration.conflict.spi;

import java.util.List;

/**
 * Story 4.17 (AC1/AC5, Reconciliation 4/5) — the read seam the conflict-detection sweep uses to
 * discover active {@code integration_links} of one type without taking the PESSIMISTIC_WRITE lock
 * the enrich/sync writers hold (there is no bulk "list active links by type" method on {@code
 * IntegrationLinkRecordPort}, which carries only single-link lookups). Kept as a dedicated port so
 * the {@code IntegrationLinkPersistenceAdapter} constructor never fans out.
 */
public interface IntegrationConflictScanPort {

  /**
   * Take the sweep-wide PG advisory transaction lock ({@code pg_advisory_xact_lock}) so two
   * schedulers (or app instances) never run the conflict-detection sweep concurrently. Transaction-
   * scoped — auto-released when the caller's transaction commits/rolls back; the caller MUST invoke
   * this as the FIRST statement inside the phase-1 lock+scan transaction (mirrors {@code
   * RunDependencyPersistenceAdapter.lockDependencyGraph}). The sweep releases this lock (short-tx
   * commit) BEFORE any external HTTP, so it is held only across the scan, never across adapter I/O.
   */
  void acquireSweepLock();

  /**
   * Return up to {@code batchLimit} active links of {@code integrationType} (archived-excluded,
   * {@code sync_status <> 'superseded'}) whose {@code id > afterSeq}, ordered by {@code id}
   * ascending, as non-locking projections with the workflow run's {@code current_state}
   * join-fetched and the V17 {@code project_id} surfaced. Keyset-paginated so the sweep advances
   * past a single batch across ticks (no bare-{@code LIMIT} tail starvation). Pass {@code afterSeq
   * = 0} to start from the oldest link.
   */
  List<IntegrationLinkScanRow> scanActiveLinksByType(
      String integrationType, int batchLimit, long afterSeq);

  /**
   * Story 4.17 — snapshot a link's first-seen external baseline into {@code external_metadata}
   * without changing {@code sync_status} (the state machine forbids a self-transition, and
   * rewriting status here would fight the polling host). Used for the Linear {@code sourceStatusId}
   * baseline (OQ-1 provisional) and the GitHub {@code prState} first-seen baseline so a later
   * merge/close is not silently missed. Runs in its OWN transaction ({@code REQUIRES_NEW}) so a
   * best-effort baseline write can never poison the caller's flow. Returns {@code true} when a row
   * was updated.
   */
  boolean snapshotExternalMetadataBaseline(String integrationLinkPublicId, byte[] externalMetadata);
}
