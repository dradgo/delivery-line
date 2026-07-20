package org.dradgo.application.audit.spi;

import java.util.List;

/**
 * Story 4.3 (AC1/AC8) — one page of audit event rows plus the FULL-set count. Returned by {@link
 * AuditEventReadPort#listByRun} / {@link AuditEventReadPort#listByTicket}.
 *
 * <p>{@code rows} is at most {@code query.limit()} rows (the service passes {@code pageSize + 1} so
 * a full extra row signals a next page); {@code totalCount} is the count over the WHOLE filtered
 * set independent of {@code limit}/{@code cursor}, so it is stable across pages.
 *
 * @param rows the fetched rows in {@code (created_at DESC, id DESC)} keyset order
 * @param totalCount the total number of rows matching the filter (limit/cursor independent)
 */
public record AuditEventPageSnapshot(List<AuditEventRowSnapshot> rows, long totalCount) {

  public AuditEventPageSnapshot {
    rows = rows == null ? List.of() : List.copyOf(rows);
  }

  /** An empty page — nothing matched. */
  public static AuditEventPageSnapshot empty() {
    return new AuditEventPageSnapshot(List.of(), 0L);
  }
}
