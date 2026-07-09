package org.dradgo.application.integration.conflict.spi;

import java.time.Instant;

/**
 * Story 4.17 (AC1/AC9) — the write seam for the {@code integration_conflicts} table. Only {@code
 * application.integration.conflict} may depend on this port (ArchUnit AC9); the {@code @Scheduled}
 * trigger in {@code infrastructure.config} only delegates to the detection service.
 */
public interface IntegrationConflictWritePort {

  /**
   * Insert one conflict row, skipping silently when an UNRESOLVED, non-archived conflict of the
   * same {@code (integration_link_id, conflict_category)} already exists — the {@code
   * uq_integration_conflicts_unresolved} partial-unique dedup index. Implemented with {@code INSERT
   * … ON CONFLICT DO NOTHING} so a standing conflict never poisons the caller's transaction
   * (contrast a caught unique-violation, which does — the poisoned-shared-tx trap).
   *
   * @return {@code true} when a NEW row was inserted (the caller then emits {@code
   *     integration.conflictDetected}); {@code false} when the dedup index skipped the insert.
   */
  boolean insertIfAbsent(NewIntegrationConflict request);

  boolean markResolved(String conflictPublicId, String recoveryActionPublicId, Instant resolvedAt);

  /**
   * Story 4.6 code review (P3) — take a per-run, transaction-scoped advisory lock so concurrent
   * reconciles on the SAME run serialize their last-conflict count→transition decision (closing the
   * D1 race where two reconciles each see the other's conflict as still-unresolved and both skip
   * terminalizing the run). Joins the caller's ambient ({@code MANDATORY}) transaction and releases
   * on commit/rollback; different runs never contend.
   */
  void lockRunForReconcile(String workflowRunId);
}
