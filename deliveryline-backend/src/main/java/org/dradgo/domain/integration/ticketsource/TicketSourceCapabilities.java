package org.dradgo.domain.integration.ticketsource;

/**
 * Vendor-neutral capability declaration for a {@code TicketSourceAdapter} (story 3.32 AC3). Not
 * every ticket source supports every operation — e.g. a source may not expose comment-posting via
 * its API. Consuming services check the relevant flag before invoking an optional operation and
 * gracefully degrade when it is unsupported (the completion-sync path skips with a structured WARN
 * when {@link #supportsCommentOnTicket()} is {@code false}).
 *
 * <ul>
 *   <li>{@code supportsCommentOnTicket} — the source can write a governed run comment back to a
 *       ticket ({@code postGovernedRunComment}).
 *   <li>{@code supportsPolling} — the source can be polled for tickets updated since an instant
 *       ({@code pollNewTickets}).
 *   <li>{@code supportsTicketStateUpdates} — the source exposes a ticket workflow-state the adapter
 *       can surface (drives status-gated behavior such as auto-ingest).
 * </ul>
 */
public record TicketSourceCapabilities(
    boolean supportsCommentOnTicket, boolean supportsPolling, boolean supportsTicketStateUpdates) {

  /**
   * The Linear capability set — Linear supports comment-posting, polling, and surfaces issue
   * workflow-state ids, so all three are {@code true} today.
   */
  public static TicketSourceCapabilities linearDefaults() {
    return new TicketSourceCapabilities(true, true, true);
  }
}
