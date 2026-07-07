package org.dradgo.application.integration.conflict.spi;

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
}
