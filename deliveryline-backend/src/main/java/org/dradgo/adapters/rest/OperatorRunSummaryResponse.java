package org.dradgo.adapters.rest;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.dradgo.application.workflow.WorkflowInspectionService.OperatorRunSummary;

/**
 * Story 4.2 (AC1/AC5) — wire shape for {@code GET /api/v1/operator/runs}. Unlike the direct-array
 * {@code GET /api/v1/workflows}, this is an OBJECT carrier because the operator fleet view bundles
 * aggregate ({@code total} + two histograms + {@code oldestEntryAt}) and cursor pagination ({@code
 * nextCursor}) alongside the {@code runs} page (Reconciliation 2). Mirrors the object-carrier
 * mapping style of {@link AllowedActionsResponse} (enums → wire strings), not the row DTO {@link
 * WorkflowSummaryResponse}.
 *
 * <p>The aggregate fields are computed over the FULL matched set independent of the cursor, so they
 * are stable across pages; the UI keeps page 1's aggregate and only appends {@code runs}.
 */
@Schema(name = "OperatorRunSummary", description = "Operator fleet-view summary with a runs page.")
public record OperatorRunSummaryResponse(
    @Schema(description = "Total runs matching the filter (independent of limit).", example = "12")
        int total,
    @Schema(
            description =
                "Histogram of matched runs by current-state wire string (full match set).",
            example = "{\"Failed\":8,\"TakenOver\":2}")
        Map<String, Integer> byState,
    @Schema(
            description =
                "Histogram of currently-Failed runs by failure-category wire string (full match"
                    + " set); empty unless the state filter includes failed/orphaned.",
            example = "{\"orphan\":3,\"runner_build_failed\":2}")
        Map<String, Integer> byFailureCategory,
    @Schema(
            description = "Oldest matched entry timestamp (ISO-8601 UTC), or null when empty.",
            nullable = true)
        OffsetDateTime oldestEntryAt,
    @Schema(description = "The current page of operator rows (lastTransitionAt DESC).")
        List<OperatorRunRowResponse> runs,
    @Schema(
            description =
                "Opaque keyset cursor for the next page, or null on the last page. Echo it back as"
                    + " the cursor query param to fetch more.",
            nullable = true)
        String nextCursor) {

  public static OperatorRunSummaryResponse from(OperatorRunSummary view) {
    Map<String, Integer> byState = new LinkedHashMap<>();
    view.byState().forEach((state, count) -> byState.put(state.value(), count));
    Map<String, Integer> byFailureCategory = new LinkedHashMap<>();
    view.byFailureCategory()
        .forEach((category, count) -> byFailureCategory.put(category.value(), count));
    List<OperatorRunRowResponse> runs =
        view.runs().stream().map(OperatorRunRowResponse::from).toList();
    return new OperatorRunSummaryResponse(
        view.total(),
        byState,
        byFailureCategory,
        toUtc(view.oldestEntryAt()),
        runs,
        view.nextCursor());
  }

  private static OffsetDateTime toUtc(OffsetDateTime value) {
    return value == null ? null : value.withOffsetSameInstant(ZoneOffset.UTC);
  }
}
