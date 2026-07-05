package org.dradgo.application.workflow.spi;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * Story 4.1 (AC2/AC5) — the fleet aggregate over the FULL matching set (independent of {@code
 * --limit}, which caps only the {@code runs[]} page — story 4.1 Reconciliation 5). Returned by
 * {@link OperatorRunReadPort#loadOperatorRunAggregate}.
 *
 * <p>The two histograms are keyed by the raw wire strings the database groups on ({@code
 * current_state} / {@code failure_category}); the service turns them into {@code
 * EnumMap<WorkflowState,Integer>} / {@code EnumMap<FailureCategory,Integer>}. {@code
 * countsByFailureCategory} is empty unless the query's state filter includes {@code failed} or
 * {@code orphaned}.
 *
 * @param total number of runs matching the filter
 * @param countsByState count of matched runs grouped by their actual {@code current_state} wire
 *     string
 * @param countsByFailureCategory count of matched runs grouped by non-null {@code failure_category}
 *     wire string (empty when the filter excludes failed/orphaned)
 * @param oldestEntryAt {@code MIN} over the matched runs' oldest event timestamps, or {@code null}
 *     when the matched set is empty (or none of the matched runs have any events)
 */
public record OperatorRunAggregate(
    int total,
    Map<String, Integer> countsByState,
    Map<String, Integer> countsByFailureCategory,
    OffsetDateTime oldestEntryAt) {

  /** An empty aggregate — the query matched nothing. */
  public static OperatorRunAggregate empty() {
    return new OperatorRunAggregate(0, Map.of(), Map.of(), null);
  }
}
