package org.dradgo.adapters.integration.linear;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.dradgo.application.integration.linear.LinearTicket;

/**
 * Wire-form of a Linear mock fixture JSON file (kept project-internal — not shared with the runner
 * contracts module). Conforms to {@code schemas/linear-ticket-mock.v1.schema.json}.
 *
 * <p>Adapter-only translation type: not exposed through the {@link LinearTicket port} surface.
 */
final class LinearTicketFixtureDocument {

  private final String ticketRef;
  private final String title;
  private final String summary;
  private final String authorIdentity;
  private final String createdAt;
  private final String updatedAt;
  private final Map<String, String> labels;

  @JsonCreator
  LinearTicketFixtureDocument(
      @JsonProperty("ticketRef") String ticketRef,
      @JsonProperty("title") String title,
      @JsonProperty("summary") String summary,
      @JsonProperty("authorIdentity") String authorIdentity,
      @JsonProperty("createdAt") String createdAt,
      @JsonProperty("updatedAt") String updatedAt,
      @JsonProperty("labels") Map<String, String> labels) {
    this.ticketRef = ticketRef;
    this.title = title;
    this.summary = summary;
    this.authorIdentity = authorIdentity;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
    this.labels = labels == null ? Map.of() : new LinkedHashMap<>(labels);
  }

  LinearTicket toLinearTicket() {
    return new LinearTicket(
        ticketRef,
        title,
        summary,
        authorIdentity,
        parseInstant("createdAt", createdAt),
        parseInstant("updatedAt", updatedAt),
        labels);
  }

  Instant updatedAt() {
    return parseInstant("updatedAt", updatedAt);
  }

  private static Instant parseInstant(String field, String raw) {
    if (raw == null) {
      throw new IllegalStateException("Linear mock fixture missing " + field);
    }
    try {
      return Instant.parse(raw);
    } catch (DateTimeParseException error) {
      throw new IllegalStateException(
          "Linear mock fixture has invalid ISO-8601 in " + field + ": " + raw, error);
    }
  }
}
