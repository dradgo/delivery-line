package org.dradgo.application.integration.conflict;

import java.time.Duration;

/**
 * Story 4.17 (AC6) / story 4.18 (AC2) — the filter surface for the integration-conflict read paths.
 * Every field is nullable = "no filter on this axis".
 *
 * <ul>
 *   <li>{@code conflictCategory} is an {@code IntegrationConflictCategory} wire value; {@code
 *       integrationType} is {@code linear} / {@code github_pr}; {@code timeSince} bounds {@code
 *       detected_at >= now() - timeSince}; {@code workflowRunId} / {@code ticketReference} narrow
 *       to a run / external ref.
 *   <li>Story 4.18 (AC2) added the pagination + resolved axes for {@code
 *       IntegrationConflictService.listConflicts} (the REST {@code GET
 *       /api/v1/integration-conflicts} read): {@code resolved} is a three-valued filter ({@code
 *       null} = both, {@code false} = unresolved only, {@code true} = resolved only); {@code limit}
 *       is the requested page size (clamped in the service); {@code cursor} is the opaque keyset
 *       token from a prior page.
 * </ul>
 *
 * <p>The legacy {@code listUnresolvedConflicts} path (queue/gate/overlay callers) uses the 5-arg
 * convenience constructor + the {@link #unfiltered()} / {@link #forRun(String)} factories and
 * always filters {@code resolved_at IS NULL} regardless of {@code resolved}. Bad values are
 * rejected by the service ({@code INVALID_COMMAND_PAYLOAD}).
 */
public record ConflictFilter(
    String conflictCategory,
    String integrationType,
    Duration timeSince,
    String workflowRunId,
    String ticketReference,
    Boolean resolved,
    Integer limit,
    String cursor) {

  /**
   * Back-compatible 4.17 constructor (no resolved/limit/cursor). Keeps the queue/gate/overlay call
   * sites + 4.17 tests untouched while 4.18 threads the pagination inputs.
   */
  public ConflictFilter(
      String conflictCategory,
      String integrationType,
      Duration timeSince,
      String workflowRunId,
      String ticketReference) {
    this(
        conflictCategory,
        integrationType,
        timeSince,
        workflowRunId,
        ticketReference,
        null,
        null,
        null);
  }

  /** No-filter query — returns all unresolved conflicts. */
  public static ConflictFilter unfiltered() {
    return new ConflictFilter(null, null, null, null, null);
  }

  public static ConflictFilter forRun(String workflowRunId) {
    return new ConflictFilter(null, null, null, workflowRunId, null);
  }
}
