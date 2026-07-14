package org.dradgo.application.integration.conflict.spi;

import java.time.OffsetDateTime;

/**
 * Story 4.18 (AC2) — the resolved, keyset-paginated query handed from {@code
 * IntegrationConflictService.listConflicts} to the {@link IntegrationConflictReadPort}. All
 * validation/normalization (filter tokens, limit clamp, cursor decode) has already happened in the
 * service; the adapter builds pure SQL from these fields.
 *
 * @param conflictCategory {@code IntegrationConflictCategory} wire value, or {@code null}
 * @param integrationType {@code linear} / {@code github_pr}, or {@code null}
 * @param ticketReference external-ref exact match, or {@code null}
 * @param workflowRunId run public id, or {@code null}
 * @param sinceSeconds lower bound as {@code detected_at >= now() - sinceSeconds}, or {@code null}
 * @param resolved three-valued: {@code null} = both, {@code false} = unresolved only, {@code true}
 *     = resolved only
 * @param cursorDetectedAt the keyset anchor's {@code detected_at} (from a prior page), or {@code
 *     null} for the first page
 * @param cursorConflictId the keyset anchor's conflict public id (tiebreak), or {@code null}
 * @param limit the fetch size (the service passes {@code pageSize + 1} to detect a next page)
 */
public record ConflictListQuery(
    String conflictCategory,
    String integrationType,
    String ticketReference,
    String workflowRunId,
    Double sinceSeconds,
    Boolean resolved,
    OffsetDateTime cursorDetectedAt,
    String cursorConflictId,
    int limit) {}
