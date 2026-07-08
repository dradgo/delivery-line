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
 * </ul>
 */
public record TicketSourceCapabilities(
    boolean supportsCommentOnTicket,
    boolean supportsPolling,
    boolean supportsTicketStateUpdates,
    boolean supportsTicketCreation,
    boolean supportsSourceTicketUrl) {

  /**
   * Helper for connectors that intentionally do not support source ticket creation yet. Such
   * connectors also do not build a source-ticket URL ({@code supportsSourceTicketUrl == false}).
   */
  public static TicketSourceCapabilities noCreation(
      boolean supportsCommentOnTicket,
      boolean supportsPolling,
      boolean supportsTicketStateUpdates) {
    return new TicketSourceCapabilities(
        supportsCommentOnTicket, supportsPolling, supportsTicketStateUpdates, false, false);
  }

  /**
   * The Linear capability set - Linear supports comment-posting, polling, state ids, source
   * sub-ticket creation, and source-ticket URL building.
   */
  public static TicketSourceCapabilities linearDefaults() {
    return new TicketSourceCapabilities(true, true, true, true, true);
  }

  /**
   * The JIRA capability set (story 3i-1 AC2) - JIRA supports comment-posting (ADF), polling (JQL),
   * source-status ids (opaque {@code fields.status.id}), source sub-task creation, and the {@code
   * /browse/} source-ticket URL. Same full set as {@link #linearDefaults()}; kept a distinct named
   * factory so the capability contract test asserts the JIRA connector's flags explicitly.
   */
  public static TicketSourceCapabilities jiraDefaults() {
    return new TicketSourceCapabilities(true, true, true, true, true);
  }
}
