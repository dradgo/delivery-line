package org.dradgo.adapters.rest;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.dradgo.domain.integration.ticketsource.TicketQueryResult;

/**
 * Story 3i-2 — one page of a filtered intake browse, plus the source's total match count.
 *
 * <p><strong>Why this is an envelope.</strong> The story's AC3 originally specified a bare array,
 * matching the other list endpoints. Code review found that a bare array cannot distinguish a
 * complete browse from a truncated one: a filter matching 400 tickets and one matching exactly
 * {@code limit} render identically, so the operator has no signal to narrow their filter and no way
 * to know the ticket they want exists but fell off the page. Silently hiding matches defeats the
 * purpose of a filtered intake surface, so the envelope was adopted as a deliberate deviation.
 *
 * <p>{@code total} counts matches at the <em>source</em>, not rows in {@link #tickets()}: it
 * exceeds the page size when the browse was capped by {@code limit}, and also when an individual
 * ticket could not be mapped and was skipped. {@code truncated} folds both cases into the single
 * question the operator actually has — "am I seeing everything?"
 */
@Schema(
    name = "CandidateTicketPage",
    description = "A page of candidate tickets from a filtered intake browse.")
public record CandidateTicketPageResponse(
    @Schema(
            description = "The candidate tickets on this page, newest-updated first.",
            requiredMode = Schema.RequiredMode.REQUIRED)
        List<CandidateTicketResponse> tickets,
    @Schema(
            description =
                "Total tickets matching the filter at the source, which may exceed the page size.",
            example = "412",
            requiredMode = Schema.RequiredMode.REQUIRED)
        int total,
    @Schema(
            description =
                "True when the operator is not seeing every match — the page was capped by `limit`,"
                    + " or a ticket could not be mapped and was skipped. Narrow the filter.",
            example = "true",
            requiredMode = Schema.RequiredMode.REQUIRED)
        boolean truncated) {

  public static CandidateTicketPageResponse from(TicketQueryResult result) {
    return new CandidateTicketPageResponse(
        result.tickets().stream().map(CandidateTicketResponse::from).toList(),
        result.total(),
        result.truncated());
  }
}
