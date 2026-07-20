package org.dradgo.application.compare;

/**
 * Story 4.19 (AC2) — aggregate counts over a {@link RevisionDelta}'s {@link ChangeBlock}s.
 *
 * @param changedRegionCount total number of change blocks (the authoritative total; equals the size
 *     of {@link RevisionDelta#changes()}). Includes {@code reordered} plan-step blocks, which are
 *     not folded into any of the three kind-specific counts below.
 * @param addedCount number of {@link ChangeKind#ADDED} blocks.
 * @param removedCount number of {@link ChangeKind#REMOVED} blocks.
 * @param modifiedCount number of {@link ChangeKind#MODIFIED} blocks.
 */
public record DeltaSummary(
    int changedRegionCount, int addedCount, int removedCount, int modifiedCount) {}
