package org.dradgo.adapters.rest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import org.dradgo.application.integration.conflict.ConflictFilter;
import org.dradgo.application.integration.conflict.IntegrationConflictService;
import org.dradgo.application.integration.conflict.IntegrationConflictService.ConflictDetail;
import org.dradgo.application.integration.conflict.IntegrationConflictService.ConflictListResult;
import org.dradgo.application.observability.MdcKeys;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.DomainErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Story 4.18 (AC2/AC3) — the integration-conflict inspection REST surface: a keyset-paginated list
 * ({@code GET /api/v1/integration-conflicts}) + a typed detail ({@code GET
 * /api/v1/integration-conflicts/{conflictId}}) over the existing story-4.17 {@link
 * IntegrationConflictService}. The story-4.23 reconciliation dialog consumes both.
 *
 * <p>Deliberately thin (ArchUnit {@code
 * REST_CONTROLLERS_STAY_THIN_AND_AVOID_SPI_OR_PERSISTENCE_OR_RUNNER}): it builds a {@link
 * ConflictFilter} + delegates ALL resolution to {@link IntegrationConflictService}, importing only
 * the service's nested result records — never the {@code application.integration.conflict.spi}
 * ports. Mirrors {@link AuditController}'s cursor conventions ({@code since} as
 * {@code @DateTimeFormat OffsetDateTime}, opaque {@code cursor}, {@code limit} default).
 *
 * <p>Read-only GET: no {@code Idempotency-Key}, no {@code X-Actor-Identity}; {@code
 * X-Correlation-Id} is supplied by {@code CorrelationIdFilter}. Access gating is deferred to E5
 * RBAC (E4 is deferred-RBAC).
 */
@RestController
@Validated
@RequestMapping("/api/v1/integration-conflicts")
@Tag(
    name = "Integration Conflicts",
    description = "Inspection of detected integration conflicts (Linear/GitHub vs internal state).")
public class IntegrationConflictController {

  private static final Logger log = LoggerFactory.getLogger(IntegrationConflictController.class);

  /** Default page size when {@code limit} is omitted (service clamps to {@code [1,200]}). */
  private static final int DEFAULT_LIMIT = 50;

  // Wire vocabulary (AC2): the friendly `github` token maps to the persisted `github_pr`
  // integration_type; `linear` is unchanged. Any other value passes through and the service's
  // filter validation rejects it (INVALID_COMMAND_PAYLOAD 400).
  private static final String INTEGRATION_GITHUB = "github";
  private static final String INTEGRATION_GITHUB_PR = "github_pr";

  private final IntegrationConflictService integrationConflictService;

  public IntegrationConflictController(IntegrationConflictService integrationConflictService) {
    this.integrationConflictService = integrationConflictService;
  }

  @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      operationId = "listIntegrationConflicts",
      summary = "List integration conflicts",
      description =
          "Detected integration conflicts, newest-first with cursor pagination, plus global"
              + " unresolved/resolved counts. Filter by category, integration, run, detection time,"
              + " and resolved state.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Integration-conflict list page."),
    @ApiResponse(
        responseCode = "400",
        description = "Malformed filter or cursor (INVALID_COMMAND_PAYLOAD).",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                schema = @Schema(implementation = ProblemDetailsResponse.class)))
  })
  public IntegrationConflictListResponse listIntegrationConflicts(
      @Parameter(description = "Conflict-category filter.")
          @RequestParam(name = "category", required = false)
          String category,
      @Parameter(
              description = "Integration filter.",
              schema = @Schema(allowableValues = {"linear", "github"}))
          @RequestParam(name = "integration", required = false)
          String integration,
      @Parameter(description = "Owning run public id filter.", example = "run_abc123")
          @RequestParam(name = "workflowRunId", required = false)
          String workflowRunId,
      @Parameter(description = "Lower detection-time bound (inclusive), ISO-8601.")
          @RequestParam(name = "since", required = false)
          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          OffsetDateTime since,
      @Parameter(
              description =
                  "Resolved filter: omitted = both, false = unresolved only, true = resolved only.")
          @RequestParam(name = "resolved", required = false)
          Boolean resolved,
      @Parameter(description = "Max conflicts per page (clamped to [1,200]). Defaults to 50.")
          @RequestParam(name = "limit", required = false, defaultValue = "" + DEFAULT_LIMIT)
          int limit,
      @Parameter(description = "Opaque keyset cursor from a prior response's nextCursor.")
          @RequestParam(name = "cursor", required = false)
          String cursor) {
    long start = System.nanoTime();
    log.info(
        "REST list integration-conflicts operationId=listIntegrationConflicts categoryPresent={}"
            + " integrationPresent={} runPresent={} sincePresent={} resolved={} limit={}"
            + " cursorPresent={}",
        category != null && !category.isBlank(),
        integration != null && !integration.isBlank(),
        workflowRunId != null && !workflowRunId.isBlank(),
        since != null,
        resolved,
        limit,
        cursor != null && !cursor.isBlank());
    ConflictFilter filter =
        new ConflictFilter(
            category,
            normalizeIntegration(integration),
            sinceToDuration(since),
            workflowRunId,
            null,
            resolved,
            limit,
            cursor);
    ConflictListResult result = integrationConflictService.listConflicts(filter);
    log.info(
        "REST list integration-conflicts success operationId=listIntegrationConflicts returnedRows={}"
            + " totalUnresolved={} totalResolved={} nextCursorPresent={} durationMs={}",
        result.conflicts().size(),
        result.totalUnresolved(),
        result.totalResolved(),
        result.nextCursor() != null,
        (System.nanoTime() - start) / 1_000_000L);
    return IntegrationConflictListResponse.from(result);
  }

  @GetMapping(path = "/{conflictId}", produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      operationId = "getIntegrationConflict",
      summary = "Get an integration conflict",
      description =
          "Full conflict detail: both internal + external state snapshots and safety-ranked"
              + " reconciliation decision suggestions.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "The integration conflict."),
    @ApiResponse(
        responseCode = "404",
        description = "No such conflict (CONFLICT_NOT_FOUND).",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                schema = @Schema(implementation = ProblemDetailsResponse.class)))
  })
  public IntegrationConflictDetailResponse getIntegrationConflict(
      @Parameter(description = "Conflict public id.", example = "icf_abc123") @PathVariable
          String conflictId) {
    long start = System.nanoTime();
    log.info(
        "REST get integration-conflict operationId=getIntegrationConflict conflictId={}",
        MdcKeys.sanitizeForLog(conflictId));
    ConflictDetail detail =
        integrationConflictService
            .getConflictDetail(conflictId)
            .orElseThrow(() -> conflictNotFound(conflictId));
    log.info(
        "REST get integration-conflict success operationId=getIntegrationConflict conflictId={}"
            + " resolved={} suggestions={} durationMs={}",
        MdcKeys.sanitizeForLog(conflictId),
        detail.view().resolvedAt() != null,
        detail.suggestedDecisions().size(),
        (System.nanoTime() - start) / 1_000_000L);
    return IntegrationConflictDetailResponse.from(detail);
  }

  private static String normalizeIntegration(String integration) {
    if (integration == null || integration.isBlank()) {
      return null;
    }
    String trimmed = integration.trim();
    return INTEGRATION_GITHUB.equals(trimmed) ? INTEGRATION_GITHUB_PR : trimmed;
  }

  private static Duration sinceToDuration(OffsetDateTime since) {
    if (since == null) {
      return null;
    }
    Duration window = Duration.between(since, OffsetDateTime.now());
    // A future `since` yields a negative window; clamp to zero so the service's non-negative guard
    // treats it as "no matches after now" rather than rejecting it as an invalid filter.
    return window.isNegative() ? Duration.ZERO : window;
  }

  private static DomainException conflictNotFound(String conflictId) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("conflictId", conflictId);
    log.warn(
        "REST get integration-conflict not found conflictId={}",
        MdcKeys.sanitizeForLog(conflictId));
    return new DomainException(
        DomainErrorCode.CONFLICT_NOT_FOUND,
        "No integration conflict for id " + conflictId,
        details);
  }
}
