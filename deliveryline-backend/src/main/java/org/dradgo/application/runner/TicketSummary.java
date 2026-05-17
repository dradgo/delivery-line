package org.dradgo.application.runner;

public record TicketSummary(String ticketRef, String title, String summary) {

  public TicketSummary {
    if (ticketRef == null || ticketRef.isBlank()) {
      throw new IllegalArgumentException("ticketRef must not be blank");
    }
    if (title == null || title.isBlank()) {
      throw new IllegalArgumentException("title must not be blank");
    }
    if (summary == null || summary.isBlank()) {
      throw new IllegalArgumentException("summary must not be blank");
    }
  }
}
