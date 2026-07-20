package org.dradgo.application.integration.conflict.spi;

/**
 * Story 4.17 (AC1/AC5, Reconciliation 4/5) — a NON-locking, archived-excluded projection of one
 * active {@code integration_links} row, as read by the conflict-detection sweep. The persistence
 * adapter join-fetches {@code workflow_runs} for {@code currentState} (and reads the V17 {@code
 * integration_links.project_id}) so the scheduler thread — which has no OSIV / ambient transaction
 * — never trips a lazy proxy (the mapper-lazy-proxy-on-worker-thread trap).
 *
 * <p>{@code externalMetadata} is the raw {@code jsonb} column as UTF-8 bytes (the same {@code
 * byte[]}↔Map convention the existing {@code IntegrationLinkRecordPort} projections use); the sweep
 * decodes it to read the cached baseline ({@code prState} for GitHub, {@code sourceStatusId} for
 * Linear). {@code projectId} is nullable (surfaced from V17; adapter resolution is still profile-
 * gated singletons for 4.17 MVP — OQ-2). {@code currentState} is captured into the conflict's
 * {@code internal_state_snapshot} so stories 4.6/4.18 can judge severity, but detection is driven
 * by the cached-vs-fresh EXTERNAL drift, not the internal state.
 *
 * <p>{@code linkSeq} is the raw monotonic {@code integration_links.id} — the sweep's
 * keyset-pagination cursor. The scan orders by it and reads rows strictly greater than the per-type
 * cursor so links beyond a single {@code batchLimit} are covered on subsequent ticks (no
 * bare-{@code LIMIT} tail starvation).
 */
public record IntegrationLinkScanRow(
    String integrationLinkPublicId,
    String workflowRunPublicId,
    String integrationType,
    String externalRef,
    byte[] externalMetadata,
    String projectId,
    String currentState,
    long linkSeq) {}
