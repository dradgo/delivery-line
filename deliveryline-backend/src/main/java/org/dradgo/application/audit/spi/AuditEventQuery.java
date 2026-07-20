package org.dradgo.application.audit.spi;

import java.time.OffsetDateTime;
import java.util.Set;

/**
 * Story 4.3 (AC1/AC2/AC3/AC8) — the resolved predicate parameters backing {@link
 * AuditEventReadPort}. Built by {@code AuditQueryService} from the raw CLI/REST filter AFTER
 * validation (event-type tokens resolved to registry wire strings, {@code --since}/{@code --until}
 * time-range checked, {@code --cursor} decoded to a {@code (createdAt, id)} keyset). The service
 * fetches {@code limit + 1} rows so it can detect a next page and encode {@code nextCursor}; the
 * port returns them verbatim plus the FULL-set {@code totalCount} (count is cursor/limit
 * independent).
 *
 * <p>{@code scopeRef} is the run public id ({@code run_...}) for {@code listByRun} or the ticket
 * external ref (e.g. {@code LIN-123}) for {@code listByTicket}. Nullable filter fields ({@code
 * actorIdentity}, {@code sinceInclusive}, {@code untilInclusive}, {@code cursorCreatedAt}, {@code
 * cursorId}) are plain nullable references (repo convention — NOT {@code
 * Optional}/{@code @Nullable}). {@code eventTypes} empty disables the event-type filter.
 *
 * @param scopeRef run public id (by-run) or ticket external ref (by-ticket)
 * @param eventTypes resolved event-type wire strings to match; empty disables the filter
 * @param actorIdentity exact actor-identity match, or {@code null} to disable
 * @param sinceInclusive lower time bound (inclusive), or {@code null}
 * @param untilInclusive upper time bound (inclusive), or {@code null}
 * @param cursorCreatedAt the decoded keyset cursor's {@code created_at}, or {@code null} on page 1
 * @param cursorId the decoded keyset cursor's {@code id} tiebreaker, or {@code null} on page 1
 * @param limit maximum rows to return (the service passes {@code pageSize + 1} to detect a next
 *     page)
 */
public record AuditEventQuery(
    String scopeRef,
    Set<String> eventTypes,
    String actorIdentity,
    OffsetDateTime sinceInclusive,
    OffsetDateTime untilInclusive,
    OffsetDateTime cursorCreatedAt,
    Long cursorId,
    int limit) {

  public AuditEventQuery {
    eventTypes = eventTypes == null ? Set.of() : Set.copyOf(eventTypes);
  }

  /**
   * True when an event-type filter is active (drives the {@code event_type in (...)} predicate).
   */
  public boolean hasEventTypeFilter() {
    return !eventTypes.isEmpty();
  }

  /** True when a keyset cursor is active (paging into a later page). */
  public boolean hasCursor() {
    return cursorId != null;
  }
}
