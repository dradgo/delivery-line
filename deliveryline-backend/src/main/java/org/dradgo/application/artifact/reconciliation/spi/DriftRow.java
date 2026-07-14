package org.dradgo.application.artifact.reconciliation.spi;

import java.time.Instant;
import org.dradgo.domain.registry.DriftCategory;

/**
 * Story 4.15 (AC5) — one raw unresolved {@code artifact_drift_detected} row read back from the
 * persistence adapter. Exactly one of {@code artifactId} / {@code artifactOperationId} is
 * populated. {@code driftCategory} is parsed from the DB text at the adapter's row mapper via
 * {@code PersistedRegistryValues.artifactDriftCategory} (the persistence boundary). {@code
 * lastKnownStateJson} is the raw JSONB snapshot as text; the service parses it to a map for {@code
 * DriftSummary}. This carries no computed hint — {@code RepairActionHint} is derived in the service
 * (AC6).
 *
 * <p>{@code resolvedAt} (story 4.16 / Reconciliation 6) is {@code null} for the {@code
 * listUnresolved} read (which hard-filters {@code resolved_at IS NULL}) and is populated only by
 * {@code findByPublicId}, which returns EVEN resolved rows so the repair coordinator can
 * distinguish a not-yet-resolved drift from an already-resolved one ({@code
 * DRIFT_ALREADY_RESOLVED}).
 */
public record DriftRow(
    String driftId,
    String workflowRunId,
    String artifactId,
    String artifactOperationId,
    DriftCategory driftCategory,
    Instant detectedAt,
    String lastKnownStateJson,
    Instant resolvedAt) {}
