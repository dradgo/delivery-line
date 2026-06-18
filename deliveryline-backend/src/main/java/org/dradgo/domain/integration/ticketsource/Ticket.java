package org.dradgo.domain.integration.ticketsource;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Vendor-neutral projection of a source ticket. Implementing adapters translate their vendor
 * response shape (Linear GraphQL, future JIRA/GitHub-Issues REST) to this record at the port
 * boundary — no vendor-specific transport types (GraphQL DTOs, REST envelopes) are allowed to
 * surface here. Verified by the {@code TICKET_SOURCE_TYPES_MUST_NOT_LEAK_THROUGH_PORT} ArchUnit
 * rule (story 3.32, generalized from story 1.14's Linear-specific rule).
 *
 * <p>{@code labels} is stored as an immutable {@link Map} of label-name → label-value (flat tags;
 * the map shape keeps room for future label metadata without breaking the form). {@code
 * authorIdentity} carries the source-user identity string (e.g. {@code user@example.com}) — never a
 * vendor user DTO.
 *
 * <p><strong>Opaque source-status fields (story 3.32 OQ-1).</strong> {@code sourceStatus} (display
 * name, logs only) and {@code sourceStatusId} (a <em>vendor-opaque status token</em>) are both
 * <strong>nullable</strong> and appended at the END of the component list to minimize construction
 * fan-out. {@code sourceStatusId} is deliberately an opaque {@code String} — NOT a typed vendor id
 * — and is <strong>not interpreted by neutral consumers</strong>: only the implementing adapter
 * produces it and only the vendor-specific polling host (e.g. {@code LinearPollingHost}'s
 * auto-ingest gating, story 3a.5) reads it. Both are {@code null} when the source omits status; a
 * {@code null} {@code sourceStatusId} is treated as ineligible for status-gated behavior (never NPE
 * on the accessors).
 */
public record Ticket(
    TicketRef ticketRef,
    String title,
    String summary,
    String authorIdentity,
    Instant createdAt,
    Instant updatedAt,
    Map<String, String> labels,
    String sourceStatus,
    String sourceStatusId) {

  public Ticket {
    Objects.requireNonNull(ticketRef, "ticketRef");
    Objects.requireNonNull(title, "title");
    Objects.requireNonNull(summary, "summary");
    Objects.requireNonNull(authorIdentity, "authorIdentity");
    Objects.requireNonNull(createdAt, "createdAt");
    Objects.requireNonNull(updatedAt, "updatedAt");
    labels =
        labels == null
            ? Collections.emptyMap()
            : Collections.unmodifiableMap(new LinkedHashMap<>(labels));
    // sourceStatus (name) + sourceStatusId (opaque token) are intentionally nullable — left as-is.
  }
}
