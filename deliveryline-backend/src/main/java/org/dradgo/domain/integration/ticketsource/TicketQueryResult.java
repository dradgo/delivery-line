package org.dradgo.domain.integration.ticketsource;

import java.util.List;
import java.util.Objects;

/**
 * Vendor-neutral result of a candidate-ticket browse (story 3i-2 / FR81): the page of tickets the
 * operator can see, plus the source's count of every ticket that matched the filter.
 *
 * <p><strong>Why {@code total} exists.</strong> A browse is a bounded page — {@code
 * TicketQuery.limit()} caps what the source returns. Without {@code total}, a browse matching 400
 * tickets and one matching exactly 50 render identically, so the operator cannot tell a complete
 * backlog from a truncated one and has no signal to narrow the filter. Silently hiding matches
 * undercuts the point of a filtered intake surface.
 *
 * <p>{@code total} counts <em>matches at the source</em>, not rows in {@link #tickets()}. It can
 * therefore exceed {@code tickets().size()} for two independent reasons: the page was capped by
 * {@code limit}, or an individual issue could not be mapped and was skipped (a JIRA permission
 * scheme can hide a required field from the browsing account). {@link #truncated()} covers both —
 * in either case the operator is not seeing every match, which is exactly what they need to know.
 *
 * <p>A source that cannot report a total should pass {@code tickets.size()}, yielding {@code
 * truncated() == false}.
 */
public record TicketQueryResult(List<TicketSummary> tickets, int total) {

  public TicketQueryResult {
    tickets = List.copyOf(Objects.requireNonNull(tickets, "tickets"));
    if (total < 0) {
      throw new IllegalArgumentException("TicketQueryResult total must not be negative: " + total);
    }
  }

  /** An empty page with nothing behind it. */
  public static TicketQueryResult empty() {
    return new TicketQueryResult(List.of(), 0);
  }

  /** A complete page: every match is present, so there is nothing beyond it. */
  public static TicketQueryResult complete(List<TicketSummary> tickets) {
    return new TicketQueryResult(tickets, tickets.size());
  }

  /**
   * True when the operator is not seeing every ticket that matched — capped page, or skipped rows.
   */
  public boolean truncated() {
    return total > tickets.size();
  }
}
