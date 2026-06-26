package org.dradgo.application.integration.ticketsource;

import java.util.Optional;
import org.dradgo.domain.integration.ticketsource.CreateSubticketResult;

/** Outcome for a capability-gated source sub-ticket creation attempt. */
public record TicketSourceSubticketOutcome(Status status, Optional<CreateSubticketResult> result) {

  public enum Status {
    CREATED,
    INTERNAL_ONLY_SKIPPED,
    NO_TICKET_SOURCE
  }

  public TicketSourceSubticketOutcome {
    result = result == null ? Optional.empty() : result;
  }

  public static TicketSourceSubticketOutcome created(CreateSubticketResult result) {
    return new TicketSourceSubticketOutcome(Status.CREATED, Optional.of(result));
  }

  public static TicketSourceSubticketOutcome internalOnlySkipped() {
    return new TicketSourceSubticketOutcome(Status.INTERNAL_ONLY_SKIPPED, Optional.empty());
  }

  public static TicketSourceSubticketOutcome noTicketSource() {
    return new TicketSourceSubticketOutcome(Status.NO_TICKET_SOURCE, Optional.empty());
  }
}
