package org.dradgo.adapters.rest;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.dradgo.application.compare.ChangeBlock;
import org.dradgo.application.compare.DeltaSummary;
import org.dradgo.application.compare.FileChangeBlock;
import org.dradgo.application.compare.MarkdownChangeBlock;
import org.dradgo.application.compare.PlanStepChangeBlock;
import org.dradgo.application.compare.RevisionDelta;

/**
 * Story 4.19 (AC7) — wire shape for {@code GET
 * /api/v1/artifacts/{artifactIdA}/compare/{artifactIdB}}. Flat camelCase records; ISO-8601 UTC
 * timestamps; {@code .from(RevisionDelta)} translator. The polymorphic change blocks are flattened
 * into a single {@link ChangeResponse} carrying a {@code blockType} discriminator + all
 * variant-specific nullable fields (keeps the OpenAPI schema a single object — no {@code oneOf} /
 * Jackson polymorphism).
 */
@Schema(
    name = "RevisionDelta",
    description = "Typed delta between two artifact versions of one lineage.")
public record RevisionDeltaResponse(
    @Schema(example = "spec") String artifactType,
    ArtifactRevisionSummaryResponse revisionA,
    ArtifactRevisionSummaryResponse revisionB,
    DeltaSummaryResponse summary,
    List<ChangeResponse> changes,
    @Schema(
            description =
                "True when the two artifacts are byte-equal or differ only in non-semantic"
                    + " whitespace (spec/plan).")
        boolean noMeaningfulDiff,
    @Schema(
            description =
                "prOutput only: [artifactIdA, artifactIdB] so the UI can lazy-load the full diff via"
                    + " the per-artifact read; null for spec/implementationPlan.",
            nullable = true)
        List<String> linkedDiffReferences) {

  public static RevisionDeltaResponse from(RevisionDelta delta) {
    return new RevisionDeltaResponse(
        delta.artifactType(),
        ArtifactRevisionSummaryResponse.from(delta.revisionA()),
        ArtifactRevisionSummaryResponse.from(delta.revisionB()),
        DeltaSummaryResponse.from(delta.summary()),
        delta.changes().stream().map(ChangeResponse::from).toList(),
        delta.noMeaningfulDiff(),
        delta.linkedDiffReferences());
  }

  /** Per-revision artifact metadata (AC2). */
  @Schema(name = "ArtifactRevisionSummary")
  public record ArtifactRevisionSummaryResponse(
      @Schema(example = "3") int version,
      OffsetDateTime createdAt,
      @Schema(
              description = "Creating actor identity; null when unknown.",
              example = "developer",
              nullable = true)
          String producedByActor,
      @Schema(
              description = "Short-form checksum (<algorithm>:<first 12 hex>); null when unset.",
              example = "SHA-256:9f86d081884c",
              nullable = true)
          String checksum) {

    static ArtifactRevisionSummaryResponse from(
        org.dradgo.application.compare.ArtifactSummary summary) {
      return new ArtifactRevisionSummaryResponse(
          summary.version(),
          toUtc(summary.createdAt()),
          summary.producedByActor(),
          summary.checksum());
    }
  }

  /** Aggregate change counts (AC2). */
  @Schema(name = "RevisionDeltaSummary")
  public record DeltaSummaryResponse(
      int changedRegionCount, int addedCount, int removedCount, int modifiedCount) {

    static DeltaSummaryResponse from(DeltaSummary summary) {
      return new DeltaSummaryResponse(
          summary.changedRegionCount(),
          summary.addedCount(),
          summary.removedCount(),
          summary.modifiedCount());
    }
  }

  /**
   * A single change block, flattened over the three artifact-type variants (AC3–AC5). {@code
   * blockType} is {@code markdown} / {@code planStep} / {@code file}; only the fields for that
   * variant are populated (the rest are null).
   */
  @Schema(name = "RevisionDeltaChange")
  public record ChangeResponse(
      @Schema(
              description = "Variant discriminator: markdown | planStep | file.",
              example = "markdown")
          String blockType,
      @Schema(example = "modified") String changeKind,
      // markdown variant
      @Schema(description = "spec only: heading trail.", nullable = true) String sectionPath,
      @Schema(description = "spec only: revision-A section body.", nullable = true)
          String priorText,
      @Schema(description = "spec only: revision-B section body.", nullable = true)
          String currentText,
      // planStep variant
      @Schema(description = "implementationPlan only: positional step id.", nullable = true)
          String stepId,
      @Schema(description = "implementationPlan only: revision-A step text.", nullable = true)
          String priorStepText,
      @Schema(description = "implementationPlan only: revision-B step text.", nullable = true)
          String currentStepText,
      @Schema(description = "implementationPlan only: revision-A step index.", nullable = true)
          Integer priorStepOrder,
      @Schema(description = "implementationPlan only: revision-B step index.", nullable = true)
          Integer currentStepOrder,
      // file variant
      @Schema(description = "prOutput only: changed file path.", nullable = true) String filePath,
      @Schema(description = "prOutput only: added line count.", nullable = true) Integer addedLines,
      @Schema(description = "prOutput only: removed line count.", nullable = true)
          Integer removedLines) {

    static ChangeResponse from(ChangeBlock block) {
      return switch (block) {
        case MarkdownChangeBlock m ->
            new ChangeResponse(
                "markdown",
                m.changeKind(),
                m.sectionPath(),
                m.priorText(),
                m.currentText(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
        case PlanStepChangeBlock p ->
            new ChangeResponse(
                "planStep",
                p.changeKind(),
                null,
                null,
                null,
                p.stepId(),
                p.priorStepText(),
                p.currentStepText(),
                p.priorStepOrder(),
                p.currentStepOrder(),
                null,
                null,
                null);
        case FileChangeBlock f ->
            new ChangeResponse(
                "file",
                f.changeKind(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                f.filePath(),
                f.addedLines(),
                f.removedLines());
      };
    }
  }

  private static OffsetDateTime toUtc(OffsetDateTime value) {
    return value == null ? null : value.withOffsetSameInstant(ZoneOffset.UTC);
  }
}
