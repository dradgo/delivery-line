package org.dradgo.adapters.rest;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.dradgo.application.workflow.WorkflowInspectionService.ArtifactDetailView;

/**
 * Single-artifact content for {@code GET /api/v1/workflows/{workflowRunId}/artifacts/{artifactId}}
 * (story 3a-9, Gate 3). Direct resource shape (no envelope), camelCase, ISO-8601 UTC {@code
 * createdAt}, public-prefixed id.
 *
 * <p>{@code body} is the already-redacted payload as a UTF-8 markdown <strong>string</strong> (NOT
 * base64) — the Artifact Review Panel renders it via {@code SafeMarkdownRenderer}. Redaction is
 * applied at write time (stories 1.10 / 2.24); this endpoint adds no redaction and serves only the
 * persisted shareable bytes. {@code title} is intentionally absent — it is a frontend display
 * concern composed in the {@code fetchArtifact} adapter (story 3a-9 Dev Notes D1).
 */
@Schema(name = "ArtifactDetail", description = "Redacted content of a single workflow artifact.")
public record ArtifactDetailResponse(
    @Schema(example = "art_abc123") String artifactId,
    @Schema(example = "spec") String artifactType,
    @Schema(example = "3") int version,
    @Schema(example = "available") String status,
    @Schema(example = "shareable-redacted") String classification,
    OffsetDateTime createdAt,
    @Schema(
            description = "Short-form checksum (<algorithm>:<first 12 hex>); null when unset.",
            example = "SHA-256:9f86d081884c")
        String checksum,
    @Schema(
            description =
                "Redacted artifact payload as a UTF-8 markdown string (not base64). Empty for a"
                    + " prOutput (its content travels in the structured fields below).",
            example = "# Specification\n\n...")
        String body,
    @Schema(
            description =
                "prOutput only: the pushed branch name; null for spec/implementationPlan.",
            example = "feature/x",
            nullable = true)
        String branch,
    @Schema(
            description = "prOutput only: the pushed commit SHA; null otherwise.",
            example = "abcdef1234567890abcdef1234567890abcdef12",
            nullable = true)
        String commitSha,
    @Schema(
            description =
                "prOutput only: the canonical PR reference (org/repo#n) from the active github_pr"
                    + " link (co-present with prState); null when no linked PR.",
            example = "octo/hello#42",
            nullable = true)
        String prReference,
    @Schema(
            description =
                "prOutput only: the PR state from the active github_pr link"
                    + " (draft/open/merged/closed), co-present with prReference; null when no linked"
                    + " PR.",
            example = "open",
            nullable = true)
        String prState,
    @Schema(
            description =
                "prOutput only: the unified diff (size-capped) resolved at ingest; null when the"
                    + " runner produced no resolvable diff.",
            nullable = true)
        String diff) {

  public static ArtifactDetailResponse from(ArtifactDetailView view) {
    return new ArtifactDetailResponse(
        view.artifactId(),
        view.artifactType(),
        view.version(),
        view.status(),
        view.classification(),
        toUtc(view.createdAt()),
        view.checksumShortForm(),
        view.body(),
        view.branch(),
        view.commitSha(),
        view.prReference(),
        view.prState(),
        view.diff());
  }

  private static OffsetDateTime toUtc(OffsetDateTime value) {
    return value == null ? null : value.withOffsetSameInstant(ZoneOffset.UTC);
  }
}
