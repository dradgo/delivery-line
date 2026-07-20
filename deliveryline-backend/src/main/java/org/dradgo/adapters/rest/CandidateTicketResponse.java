package org.dradgo.adapters.rest;

import io.swagger.v3.oas.annotations.media.Schema;
import org.dradgo.domain.integration.ticketsource.TicketSummary;

/**
 * Story 3i-2 (AC3) — one candidate ticket in the filtered intake browse.
 *
 * <p>Named {@code CandidateTicketResponse} rather than {@code TicketSummaryResponse} to keep it
 * unambiguous against the two same-simple-name records it sits between: the port-boundary {@code
 * domain.integration.ticketsource.TicketSummary} it maps from, and the unrelated context-bundle
 * {@code application.runner.TicketSummary}.
 *
 * <p>{@code ticketRef} and {@code title} are REQUIRED on the wire — {@link TicketSummary} enforces
 * both as non-null, so declaring them required lets the generated client type them as {@code
 * string} rather than {@code string | undefined}. Leaving them optional would push a guarantee the
 * backend already makes onto every consumer as defensive {@code ?? ''} handling.
 *
 * <p>{@code summary} is nullable — a source ticket with no description is legal.
 */
@Schema(
    name = "CandidateTicket",
    description = "A candidate ticket returned by a filtered intake browse.")
public record CandidateTicketResponse(
    @Schema(
            description = "Source ticket reference.",
            example = "PROJ-123",
            requiredMode = Schema.RequiredMode.REQUIRED)
        String ticketRef,
    @Schema(
            description = "Source ticket headline.",
            example = "Fix the billing rounding error",
            requiredMode = Schema.RequiredMode.REQUIRED)
        String title,
    @Schema(
            description = "Source ticket description; null when the ticket has no body.",
            nullable = true,
            example = "Totals are rounded half-down instead of half-even.")
        String summary) {

  public static CandidateTicketResponse from(TicketSummary ticket) {
    return new CandidateTicketResponse(
        ticket.ticketRef().value(), ticket.title(), ticket.summary());
  }
}
