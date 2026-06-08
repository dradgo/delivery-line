package org.dradgo.application.integration.linear;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Domain-shaped projection of a Linear ticket. Adapters translate Linear's GraphQL response shape
 * to this record — no Linear-specific types (GraphQL DTOs, {@code Issue}, {@code Connection<…>})
 * are allowed to surface here. Verified by the {@code LINEAR_TYPES_MUST_NOT_LEAK_THROUGH_PORT}
 * ArchUnit rule.
 *
 * <p>{@code labels} is stored as an immutable {@link Map} of label-name → label-value (Linear
 * labels are flat tags; we keep a map shape so future label metadata can attach without breaking
 * the wire form). {@code authorIdentity} carries the Linear-user identity string (e.g., {@code
 * user@example.com} or the Linear user public id) — never a GraphQL user DTO.
 *
 * <p>Story 3a.5 appends two <strong>nullable</strong> issue workflow-state fields at the END of the
 * component list (after {@code labels}) to minimize the construction fan-out: {@code statusId} is
 * the Linear issue workflow-state UUID (the auto-ingest gating key — stable across Linear-side
 * renames) and {@code status} is its display name (e.g. {@code "Ready for Planning"}, logs only).
 * Both are {@code null} when the source GraphQL response omits {@code state}; a {@code null} {@code
 * statusId} is treated as ineligible for auto-ingest (never NPE on the accessors).
 */
public record LinearTicket(
    String ticketRef,
    String title,
    String summary,
    String authorIdentity,
    Instant createdAt,
    Instant updatedAt,
    Map<String, String> labels,
    String status,
    String statusId) {

  public LinearTicket {
    if (ticketRef == null || ticketRef.isBlank()) {
      throw new IllegalArgumentException("ticketRef must be non-blank");
    }
    Objects.requireNonNull(title, "title");
    Objects.requireNonNull(summary, "summary");
    Objects.requireNonNull(authorIdentity, "authorIdentity");
    Objects.requireNonNull(createdAt, "createdAt");
    Objects.requireNonNull(updatedAt, "updatedAt");
    labels =
        labels == null
            ? Collections.emptyMap()
            : Collections.unmodifiableMap(new LinkedHashMap<>(labels));
    // status (name) + statusId (id) are intentionally nullable — left as-is.
  }
}
