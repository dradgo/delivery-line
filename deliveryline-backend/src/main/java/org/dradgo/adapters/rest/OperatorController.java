package org.dradgo.adapters.rest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.dradgo.application.workflow.WorkflowInspectionService;
import org.dradgo.application.workflow.WorkflowInspectionService.OperatorRunFilter;
import org.dradgo.application.workflow.WorkflowInspectionService.OperatorRunSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Story 4.2 (Part A) — the operator fleet-view REST surface backing the UI queue. Story 4.1 shipped
 * the read model ({@link WorkflowInspectionService#getOperatorRunSummary}) CLI-only and deferred
 * the REST endpoint here.
 *
 * <p>This controller is deliberately thin (ArchUnit {@code
 * REST_CONTROLLERS_STAY_THIN_AND_AVOID_SPI_OR_PERSISTENCE_OR_RUNNER}): it splits csv query params
 * into raw token lists, builds an {@link OperatorRunFilter}, and delegates ALL
 * token/duration/limit/cursor resolution to the service. It imports only the nested application
 * views ({@code OperatorRunFilter}/{@code OperatorRunSummary}) — never the {@code
 * application.workflow.spi} snapshots — and the operator-view ArchUnit allow-list (4.1) is widened
 * to admit it + {@link OperatorRunSummaryResponse}.
 *
 * <p>Read-only GET: no {@code Idempotency-Key}, no {@code X-Actor-Identity}; {@code
 * X-Correlation-Id} is supplied automatically by {@code CorrelationIdFilter} for log correlation
 * (story 4.2 AC9). Access gating (a governed operator {@code AllowedAction}) is deferred to E5 RBAC
 * — E4 is deferred-RBAC, any local user (story 4.2 Reconciliation 6).
 */
@RestController
@RequestMapping("/api/v1/operator")
@Tag(name = "Operator", description = "Operator fleet-view read surface for failed/stalled runs.")
public class OperatorController {

  private static final Logger log = LoggerFactory.getLogger(OperatorController.class);

  /** Default row page size when {@code limit} is omitted (service clamps to {@code [1,500]}). */
  private static final int DEFAULT_LIMIT = 100;

  private final WorkflowInspectionService workflowInspectionService;

  public OperatorController(WorkflowInspectionService workflowInspectionService) {
    this.workflowInspectionService = workflowInspectionService;
  }

  @GetMapping(path = "/runs", produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      operationId = "listOperatorRuns",
      summary = "List operator fleet-view runs",
      description =
          "Runs in non-happy operator states (failed/stalled/orphaned/takenover/overridden) across"
              + " all workflows, with aggregate histograms and cursor pagination. Backs the UI"
              + " operator queue (story 4.2); the same read model as `deliveryline operator status`"
              + " (story 4.1).")
  @ApiResponse(
      responseCode = "200",
      description = "Operator fleet summary (object carrier with aggregate + runs page + cursor).")
  public OperatorRunSummaryResponse listOperatorRuns(
      @Parameter(
              description =
                  "Operator-state filter tokens (multi-valued: failed/stalled/orphaned/takenover/"
                      + "overridden). Empty defaults to failed,stalled,orphaned.")
          @RequestParam(name = "state", required = false)
          List<String> state,
      @Parameter(
              description =
                  "Failure-category filter tokens (multi-valued, from the registry). Empty disables"
                      + " the filter.")
          @RequestParam(name = "failureCategory", required = false)
          List<String> failureCategory,
      @Parameter(
              description =
                  "Relative recent-activity window token (e.g. 1h, 24h, 7d). Omit for no window.")
          @RequestParam(name = "since", required = false)
          String since,
      @Parameter(
              description =
                  "Runner-kind filter tokens (multi-valued: codex/claude/manual). Empty disables"
                      + " the filter.")
          @RequestParam(name = "runnerKind", required = false)
          List<String> runnerKind,
      @Parameter(description = "Max rows per page (clamped to [1,500]). Defaults to 100.")
          @RequestParam(name = "limit", required = false, defaultValue = "" + DEFAULT_LIMIT)
          int limit,
      @Parameter(description = "Opaque keyset cursor from a prior response's nextCursor.")
          @RequestParam(name = "cursor", required = false)
          String cursor) {
    log.info(
        "REST list operator runs statePresent={} failureCategoryPresent={} sincePresent={}"
            + " runnerKindPresent={} limit={} cursorPresent={}",
        state != null && !state.isEmpty(),
        failureCategory != null && !failureCategory.isEmpty(),
        since != null && !since.isBlank(),
        runnerKind != null && !runnerKind.isEmpty(),
        limit,
        cursor != null && !cursor.isBlank());
    OperatorRunFilter filter =
        new OperatorRunFilter(
            state == null ? List.of() : state,
            since,
            limit,
            runnerKind == null ? List.of() : runnerKind,
            failureCategory == null ? List.of() : failureCategory,
            cursor);
    OperatorRunSummary summary = workflowInspectionService.getOperatorRunSummary(filter);
    log.info(
        "REST list operator runs success total={} returnedRows={} nextCursorPresent={}",
        summary.total(),
        summary.runs().size(),
        summary.nextCursor() != null);
    return OperatorRunSummaryResponse.from(summary);
  }
}
