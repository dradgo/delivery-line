package org.dradgo.adapters.rest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.dradgo.application.compare.RevisionDeltaService;
import org.dradgo.application.observability.MdcKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Story 4.19 (AC7) — the FIRST top-level {@code /api/v1/artifacts} REST resource: the Compare Mode
 * read that computes a typed {@link org.dradgo.application.compare.RevisionDelta} between two
 * artifact versions of one lineage. Sound as a top-level (non run-scoped) route because {@code
 * art_…} public ids are globally unique and loadable without a run.
 *
 * <p>Thin transport: parses the two path ids → {@code revisionDeltaService.computeDelta(...)} →
 * {@code RevisionDeltaResponse.from(...)}. Idempotent read — NO {@code Idempotency-Key}, NO {@code
 * X-Actor-Identity}. Typed Problem Details surface: {@code INVALID_ID_PREFIX} / {@code
 * ARTIFACT_LINEAGE_MISMATCH} (400), {@code ARTIFACT_RECORD_NOT_FOUND} (404), {@code
 * ARTIFACT_PAYLOAD_UNAVAILABLE} (503).
 */
@RestController
@Validated
@RequestMapping("/api/v1/artifacts")
@Tag(
    name = "Artifacts",
    description = "Read-only cross-run artifact operations (Compare Mode / revision delta).")
public class ArtifactCompareController {

  private static final Logger log = LoggerFactory.getLogger(ArtifactCompareController.class);

  private final RevisionDeltaService revisionDeltaService;

  public ArtifactCompareController(RevisionDeltaService revisionDeltaService) {
    this.revisionDeltaService = revisionDeltaService;
  }

  @GetMapping(
      value = "/{artifactIdA}/compare/{artifactIdB}",
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      operationId = "compareArtifacts",
      summary = "Compute the typed revision delta between two artifact versions of one lineage",
      description =
          "Returns a typed delta (spec section diff / implementation-plan step diff / prOutput "
              + "file-level summary) between two artifacts of the same lineage. A = baseline/prior, "
              + "B = target/current. Idempotent read; no Idempotency-Key. Backs Compare Mode "
              + "(UX-DR13); full prOutput diff content is lazy-loaded via the per-artifact read "
              + "using linkedDiffReferences (story 4.20).")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Typed revision delta."),
    @ApiResponse(
        responseCode = "400",
        description =
            "Malformed artifact id (INVALID_ID_PREFIX) or the two ids are not on one lineage "
                + "(ARTIFACT_LINEAGE_MISMATCH).",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                schema = @Schema(implementation = ProblemDetailsResponse.class))),
    @ApiResponse(
        responseCode = "404",
        description = "No such artifact for either id (ARTIFACT_RECORD_NOT_FOUND).",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                schema = @Schema(implementation = ProblemDetailsResponse.class))),
    @ApiResponse(
        responseCode = "503",
        description =
            "One of the artifacts is not available / its payload could not be read "
                + "(ARTIFACT_PAYLOAD_UNAVAILABLE).",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                schema = @Schema(implementation = ProblemDetailsResponse.class)))
  })
  public RevisionDeltaResponse compareArtifacts(
      @Parameter(description = "Baseline/prior artifact public id.", example = "art_abc123")
          @PathVariable
          String artifactIdA,
      @Parameter(description = "Target/current artifact public id.", example = "art_def456")
          @PathVariable
          String artifactIdB) {
    log.info(
        "REST compare artifacts received artifactIdA={} artifactIdB={}",
        MdcKeys.sanitizeForLog(artifactIdA),
        MdcKeys.sanitizeForLog(artifactIdB));
    RevisionDeltaResponse response =
        RevisionDeltaResponse.from(revisionDeltaService.computeDelta(artifactIdA, artifactIdB));
    log.info(
        "REST compare artifacts success artifactIdA={} artifactIdB={} artifactType={}"
            + " changedRegionCount={} noMeaningfulDiff={}",
        MdcKeys.sanitizeForLog(artifactIdA),
        MdcKeys.sanitizeForLog(artifactIdB),
        response.artifactType(),
        response.summary().changedRegionCount(),
        response.noMeaningfulDiff());
    return response;
  }
}
