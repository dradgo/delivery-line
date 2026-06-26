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
 * </ul>
 */
public record TicketSourceCapabilities(
    boolean supportsCommentOnTicket,
    boolean supportsPolling,
    boolean supportsTicketStateUpdates,
    boolean supportsTicketCreation) {

  /** Helper for connectors that intentionally do not support source ticket creation yet. */
  public static TicketSourceCapabilities noCreation(
      boolean supportsCommentOnTicket,
      boolean supportsPolling,
      boolean supportsTicketStateUpdates) {
    return new TicketSourceCapabilities(
        supportsCommentOnTicket, supportsPolling, supportsTicketStateUpdates, false);
  }

  /**
   * The Linear capability set - Linear supports comment-posting, polling, state ids, and source
   * sub-ticket creation.
   */
  public static TicketSourceCapabilities linearDefaults() {
    return new TicketSourceCapabilities(true, true, true, true);
  }
}
