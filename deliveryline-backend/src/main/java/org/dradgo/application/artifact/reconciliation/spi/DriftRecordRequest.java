package org.dradgo.application.artifact.reconciliation.spi;

import java.time.Instant;

/**
 * Story 4.15 — insert request for one detected {@code artifact_drift_detected} row. {@code
 * driftCategory} is a {@code DriftCategory} wire value; exactly one of {@code artifactId} /{@code
 * artifactOperationId} is populated (orphan drift carries the operation id, missing-payload /
 * checksum-mismatch drift carries the artifact id — the {@code
 * ck_artifact_drift_detected_one_target} CHECK enforces it). {@code lastKnownStateJson} is an
 * already-serialized JSON string the adapter casts to {@code jsonb}. {@code resolvedAt} / {@code
 * resolvedByActionId} are UNWRITTEN by this detection-only producer (populated later by story
 * 4.16).
 */
public record DriftRecordRequest(
    String publicId,
    String workflowRunId,
    String artifactId,
    String artifactOperationId,
    String driftCategory,
    String lastKnownStateJson,
    Instant detectedAt) {}
