package org.dradgo.application.artifact.reconciliation;

/**
 * Story 4.15 — per-tick summary of one {@link ArtifactDriftDetectionService#detectDrift()} scan.
 * {@code orphanCount} / {@code missingCount} / {@code checksumCount} = genuinely-new drift rows
 * recorded this tick per category (a dedup-skipped standing drift is NOT counted); {@code
 * batchLimitHit} = the available-artifact scan filled its batch (more may remain for the next
 * tick).
 */
public record DriftScanResult(
    int orphanCount, int missingCount, int checksumCount, boolean batchLimitHit) {}
