package org.dradgo.application.compare;

import java.util.List;

/**
 * Story 4.19 (AC2) — the typed delta between two artifact versions of one lineage, produced by
 * {@link RevisionDeltaService#computeDelta(String, String)}. Read-only application view record; the
 * REST layer translates it to {@code RevisionDeltaResponse}.
 *
 * <p>Direction: {@code revisionA} is the baseline/prior, {@code revisionB} is the target/current;
 * {@code changeKind}s are computed B-relative-to-A. The passed ids are honored as-is (never
 * auto-swapped).
 *
 * @param artifactType the shared artifact-type wire value ({@code spec} / {@code
 *     implementationPlan} / {@code prOutput}). Never {@code null}.
 * @param revisionA baseline/prior artifact summary. Never {@code null}.
 * @param revisionB target/current artifact summary. Never {@code null}.
 * @param summary aggregate change counts. Never {@code null}.
 * @param changes the per-region change blocks (variant per {@code artifactType}); empty when {@link
 *     #noMeaningfulDiff()} is {@code true} or the differ found no semantic change. Never {@code
 *     null}.
 * @param noMeaningfulDiff {@code true} when the two artifacts are byte-equal (equal checksum) or
 *     differ only in non-semantic whitespace (spec/plan differ produced zero blocks).
 * @param linkedDiffReferences prOutput only: {@code [artifactIdA, artifactIdB]} so the UI can
 *     lazy-load the full diff via the existing per-artifact read; {@code null} for spec/plan.
 */
public record RevisionDelta(
    String artifactType,
    ArtifactSummary revisionA,
    ArtifactSummary revisionB,
    DeltaSummary summary,
    List<ChangeBlock> changes,
    boolean noMeaningfulDiff,
    List<String> linkedDiffReferences) {}
