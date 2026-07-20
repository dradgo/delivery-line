package org.dradgo.application.integration.conflict;

/**
 * Story 4.17 — per-tick summary of one conflict-detection sweep. {@code scanned} = active links
 * examined across all integration types; {@code conflictsDetected} = NEW rows inserted
 * (first-insert of a {@code (link, category)}); {@code skippedDuplicate} = insert-or-skip dedup
 * hits; {@code batchLimitHit} = a type's scan filled its batch (more may remain, healed next tick —
 * no silent truncation); {@code rateLimited} = a rate/network back-off short-circuited an
 * integration's remaining links this tick (AC8).
 */
public record SweepResult(
    int scanned,
    int conflictsDetected,
    int skippedDuplicate,
    boolean batchLimitHit,
    boolean rateLimited) {}
