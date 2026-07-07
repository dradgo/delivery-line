package org.dradgo.application.integration.conflict;

import java.time.Duration;

/**
 * Story 4.17 (AC6) — the filter surface for {@code
 * IntegrationConflictService.listUnresolvedConflicts}. Every field is nullable = "no filter on this
 * axis"; all conflicts returned are always {@code resolved_at IS NULL AND archived_at IS NULL}.
 * {@code conflictCategory} is an {@code IntegrationConflictCategory} wire value; {@code
 * integrationType} is {@code linear} / {@code github_pr}; {@code timeSince} bounds {@code
 * detected_at >= now() - timeSince}; {@code workflowRunId} / {@code ticketReference} narrow to a
 * run / external ref. Bad values are rejected by the service ({@code INVALID_COMMAND_PAYLOAD}).
 */
public record ConflictFilter(
    String conflictCategory,
    String integrationType,
    Duration timeSince,
    String workflowRunId,
    String ticketReference) {

  /** No-filter query — returns all unresolved conflicts. */
  public static ConflictFilter unfiltered() {
    return new ConflictFilter(null, null, null, null, null);
  }
}
