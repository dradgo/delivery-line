package org.dradgo.domain.integration.ticketsource;

import java.util.Objects;

/**
 * Vendor-neutral projection of a <em>candidate</em> ticket returned by a browse query (story 3i-2 /
 * FR81) — the thin read shape the intake surface lists, not the full {@link Ticket}.
 *
 * <p><strong>Not to be confused with {@code org.dradgo.application.runner.TicketSummary}</strong>,
 * which is the context-bundle projection handed to a runner: that one carries a bare {@code String}
 * ref and rejects a blank title/summary. This record is the port-boundary type — it wraps the
 * neutral {@link TicketRef} and tolerates a missing description, because a source ticket with no
 * body is perfectly legal and must not crash the browse mapping.
 *
 * <p>{@code title} is the source's headline (JIRA {@code fields.summary}); {@code summary} is the
 * source's body/description (JIRA's ADF-extracted {@code fields.description}) and is <strong>
 * nullable</strong>. Neither is ever logged in full — see the redaction posture in {@code
 * docs/integrations/ticket-source-extension-contract.md}.
 */
public record TicketSummary(TicketRef ticketRef, String title, String summary) {

  public TicketSummary {
    Objects.requireNonNull(ticketRef, "ticketRef");
    Objects.requireNonNull(title, "title");
    // summary is intentionally nullable — a ticket with an empty description is legal.
  }
}
