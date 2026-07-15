package org.dradgo.application.compare;

import java.time.OffsetDateTime;

/**
 * Story 4.19 (AC2) — per-revision metadata for one side of a {@link RevisionDelta}. Sourced
 * straight off the artifact's {@code ArtifactRecordSnapshot} (version / createdAt / checksum) plus
 * the producing-actor join through {@code linked_event_id} (Reconciliation 7).
 *
 * @param version the artifact's monotonic per-lineage version.
 * @param createdAt the artifact row's creation timestamp (UTC); {@code null} only for a legacy row
 *     that predates the {@code created_at} column.
 * @param producedByActor the actor identity from the artifact's creation event ({@code
 *     workflow_events.actor_identity} via {@code linked_event_id}); {@code null} when the artifact
 *     has no linked event or a legacy/manual event carried no actor (Reconciliation 7 / OQ-3).
 * @param checksum the short-form checksum ({@code <algorithm>:<first 12 hex>}); never the full
 *     digest. {@code null} when the artifact carries no finalized checksum.
 */
public record ArtifactSummary(
    int version, OffsetDateTime createdAt, String producedByActor, String checksum) {}
