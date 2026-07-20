package org.dradgo.domain.integration.ticketsource;

/**
 * Vendor-neutral capability declaration for a {@code TicketSourceAdapter} (story 3.32 AC3). Not
 * every ticket source supports every operation - e.g. a source may not expose comment-posting or
 * ticket-creation via its API. Consuming services check the relevant flag before invoking an
 * optional operation and gracefully degrade when it is unsupported.
 *
 * <ul>
 *   <li>{@code supportsCommentOnTicket} - the source can write a governed run comment back to a
 *       ticket ({@code postGovernedRunComment}).
 *   <li>{@code supportsPolling} - the source can be polled for tickets updated since an instant
 *       ({@code pollNewTickets}).
 *   <li>{@code supportsTicketStateUpdates} - the source exposes a ticket workflow-state the adapter
 *       can surface (drives status-gated behavior such as auto-ingest).
 *   <li>{@code supportsTicketCreation} - the source can create linked child/sub-tickets.
 *   <li>{@code supportsSourceTicketUrl} - the source can build a link-back URL to the originating
 *       ticket ({@code buildSourceTicketUrl}), snapshotted into the run's origin metadata (story
 *       3g-1).
 *   <li>{@code supportsTicketQuery} - the source can be browsed with a filter (assignee /
 *       components / state) for candidate tickets ({@code queryTickets}), story 3i-2. Only JIRA
 *       advertises this today; a connector that reports {@code false} throws {@link
 *       UnsupportedOperationException} from {@code queryTickets} and the intake surface is
 *       capability-gated off for it.
 * </ul>
 */
public record TicketSourceCapabilities(
    boolean supportsCommentOnTicket,
    boolean supportsPolling,
    boolean supportsTicketStateUpdates,
    boolean supportsTicketCreation,
    boolean supportsSourceTicketUrl,
    boolean supportsTicketQuery) {

  /**
   * Helper for connectors that intentionally do not support source ticket creation yet. Such
   * connectors also do not build a source-ticket URL ({@code supportsSourceTicketUrl == false}) nor
   * support a filtered browse ({@code supportsTicketQuery == false}).
   */
  public static TicketSourceCapabilities noCreation(
      boolean supportsCommentOnTicket,
      boolean supportsPolling,
      boolean supportsTicketStateUpdates) {
    return new TicketSourceCapabilities(
        supportsCommentOnTicket, supportsPolling, supportsTicketStateUpdates, false, false, false);
  }

  /**
   * The Linear capability set - Linear supports comment-posting, polling, state ids, source
   * sub-ticket creation, and source-ticket URL building. Story 3i-2 - Linear does NOT yet implement
   * the filtered browse ({@code supportsTicketQuery == false}).
   */
  public static TicketSourceCapabilities linearDefaults() {
    return new TicketSourceCapabilities(true, true, true, true, true, false);
  }

  /**
   * The JIRA capability set (story 3i-1 AC2) - JIRA supports comment-posting (ADF), polling (JQL),
   * source-status ids (opaque {@code fields.status.id}), source sub-task creation, and the {@code
   * /browse/} source-ticket URL. Story 3i-2 adds the JQL-backed filtered browse ({@code
   * supportsTicketQuery == true}) - the one flag that today distinguishes JIRA from {@link
   * #linearDefaults()}. Kept a distinct named factory so the capability contract test asserts the
   * JIRA connector's flags explicitly.
   */
  public static TicketSourceCapabilities jiraDefaults() {
    return new TicketSourceCapabilities(true, true, true, true, true, true);
  }
}
