package org.dradgo.adapters.rest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.OffsetDateTime;
import java.util.List;
import org.dradgo.application.audit.AuditQueryService;
import org.dradgo.application.audit.AuditQueryService.AuditQueryFilter;
import org.dradgo.application.audit.AuditQueryService.AuditQueryResult;
import org.dradgo.application.observability.MdcKeys;
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
 * Story 4.3 (AC6/AC10) — the audit-history query REST surface. Two idempotent GET reads over the
 * append-only {@code workflow_events} table: {@code /by-ticket/{ticketRef}} (all runs of a ticket)
 * and {@code /by-run/{workflowRunId}} (a single run), each with the same
 * event-type/actor/since/until/limit/cursor filter set.
 *
 * <p>Deliberately thin (ArchUnit {@code
 * REST_CONTROLLERS_STAY_THIN_AND_AVOID_SPI_OR_PERSISTENCE_OR_RUNNER}): it builds an {@link
 * AuditQueryFilter} and delegates ALL resolution to {@link AuditQueryService}. It imports only the
 * nested application records ({@code AuditQueryFilter}/{@code AuditQueryResult}) — never the {@code
 * application.audit.spi} snapshots (story 4.3 Reconciliation 13).
 *
 * <p>Read-only GET: no {@code Idempotency-Key}, no {@code X-Actor-Identity}; {@code
 * X-Correlation-Id} is supplied automatically by {@code CorrelationIdFilter} for log correlation
 * (AC10). Access gating (a governed operator {@code AllowedAction}) is deferred to E5 RBAC — E4 is
 * deferred-RBAC.
 */
@RestController
@Validated
@RequestMapping("/api/v1/audit")
@Tag(name = "Audit", description = "Audit history query by ticket and by run over workflow_events.")
public class AuditController {

  private static final Logger log = LoggerFactory.getLogger(AuditController.class);

  /** Default page size when {@code limit} is omitted (service clamps to {@code [1,200]}). */
  private static final int DEFAULT_LIMIT = 50;

  private final AuditQueryService auditQueryService;

  public AuditController(AuditQueryService auditQueryService) {
    this.auditQueryService = auditQueryService;
  }

  @GetMapping(path = "/by-ticket/{ticketRef}", produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      operationId = "queryAuditByTicket",
      summary = "Query audit history by ticket",
      description =
          "Events across ALL workflow runs linked to a ticket (including retried runs), newest-first"
              + " with cursor pagination. The same read model as `deliveryline audit query"
              + " --ticket`.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Audit events page for the ticket."),
    @ApiResponse(
        responseCode = "400",
        description =
            "Malformed filter (INVALID_AUDIT_FILTER) or bad time range (INVALID_TIME_RANGE).",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                schema = @Schema(implementation = ProblemDetailsResponse.class)))
  })
  public AuditQueryResponse queryAuditByTicket(
      @Parameter(description = "Ticket external ref, e.g. LIN-123.", example = "LIN-123")
          @PathVariable
          String ticketRef,
      @Parameter(description = "Event-type filter (multi-valued). Empty disables the filter.")
          @RequestParam(name = "eventType", required = false)
          List<String> eventType,
      @Parameter(description = "Actor identity exact-match filter.")
          @RequestParam(name = "actor", required = false)
          String actor,
      @Parameter(description = "Lower time bound (inclusive), ISO-8601.")
          @RequestParam(name = "since", required = false)
          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          OffsetDateTime since,
      @Parameter(description = "Upper time bound (inclusive), ISO-8601.")
          @RequestParam(name = "until", required = false)
          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          OffsetDateTime until,
      @Parameter(description = "Max events per page (clamped to [1,200]). Defaults to 50.")
          @RequestParam(name = "limit", required = false, defaultValue = "" + DEFAULT_LIMIT)
          int limit,
      @Parameter(description = "Opaque keyset cursor from a prior response's nextCursor.")
          @RequestParam(name = "cursor", required = false)
          String cursor) {
    long start = System.nanoTime();
    log.info(
        "REST audit query by-ticket operationId=queryAuditByTicket eventTypePresent={}"
            + " actorPresent={} sincePresent={} untilPresent={} limit={} cursorPresent={}",
        eventType != null && !eventType.isEmpty(),
        actor != null && !actor.isBlank(),
        since != null,
        until != null,
        limit,
        cursor != null && !cursor.isBlank());
    AuditQueryFilter filter = toFilter(eventType, actor, since, until, limit, cursor);
    AuditQueryResult result = auditQueryService.queryByTicket(ticketRef, filter);
    log.info(
        "REST audit query by-ticket success operationId=queryAuditByTicket totalCount={}"
            + " returnedRows={} nextCursorPresent={} durationMs={}",
        result.totalCount(),
        result.events().size(),
        result.nextCursor() != null,
        (System.nanoTime() - start) / 1_000_000L);
    return AuditQueryResponse.from(result);
  }

  @GetMapping(path = "/by-run/{workflowRunId}", produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      operationId = "queryAuditByRun",
      summary = "Query audit history by run",
      description =
          "Events for a single workflow run, newest-first with cursor pagination. The same read"
              + " model as `deliveryline audit query --run`.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Audit events page for the run."),
    @ApiResponse(
        responseCode = "400",
        description =
            "Malformed run id (INVALID_ID_PREFIX), filter (INVALID_AUDIT_FILTER), or time range"
                + " (INVALID_TIME_RANGE).",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                schema = @Schema(implementation = ProblemDetailsResponse.class))),
    @ApiResponse(
        responseCode = "404",
        description = "No such run (RUN_NOT_FOUND).",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                schema = @Schema(implementation = ProblemDetailsResponse.class)))
  })
  public AuditQueryResponse queryAuditByRun(
      @Parameter(description = "Run public id, e.g. run_abc123.", example = "run_abc123")
          @PathVariable
          String workflowRunId,
      @Parameter(description = "Event-type filter (multi-valued). Empty disables the filter.")
          @RequestParam(name = "eventType", required = false)
          List<String> eventType,
      @Parameter(description = "Actor identity exact-match filter.")
          @RequestParam(name = "actor", required = false)
          String actor,
      @Parameter(description = "Lower time bound (inclusive), ISO-8601.")
          @RequestParam(name = "since", required = false)
          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          OffsetDateTime since,
      @Parameter(description = "Upper time bound (inclusive), ISO-8601.")
          @RequestParam(name = "until", required = false)
          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          OffsetDateTime until,
      @Parameter(description = "Max events per page (clamped to [1,200]). Defaults to 50.")
          @RequestParam(name = "limit", required = false, defaultValue = "" + DEFAULT_LIMIT)
          int limit,
      @Parameter(description = "Opaque keyset cursor from a prior response's nextCursor.")
          @RequestParam(name = "cursor", required = false)
          String cursor) {
    long start = System.nanoTime();
    log.info(
        "REST audit query by-run operationId=queryAuditByRun workflowRunId={} eventTypePresent={}"
            + " actorPresent={} sincePresent={} untilPresent={} limit={} cursorPresent={}",
        MdcKeys.sanitizeForLog(workflowRunId),
        eventType != null && !eventType.isEmpty(),
        actor != null && !actor.isBlank(),
        since != null,
        until != null,
        limit,
        cursor != null && !cursor.isBlank());
    AuditQueryFilter filter = toFilter(eventType, actor, since, until, limit, cursor);
    AuditQueryResult result = auditQueryService.queryByRun(workflowRunId, filter);
    log.info(
        "REST audit query by-run success operationId=queryAuditByRun workflowRunId={} totalCount={}"
            + " returnedRows={} nextCursorPresent={} durationMs={}",
        MdcKeys.sanitizeForLog(workflowRunId),
        result.totalCount(),
        result.events().size(),
        result.nextCursor() != null,
        (System.nanoTime() - start) / 1_000_000L);
    return AuditQueryResponse.from(result);
  }

  private static AuditQueryFilter toFilter(
      List<String> eventType,
      String actor,
      OffsetDateTime since,
      OffsetDateTime until,
      int limit,
      String cursor) {
    return new AuditQueryFilter(
        eventType == null ? List.of() : eventType, actor, since, until, limit, cursor);
  }
}
