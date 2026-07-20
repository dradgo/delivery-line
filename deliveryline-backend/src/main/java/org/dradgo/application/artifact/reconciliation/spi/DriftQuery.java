package org.dradgo.application.artifact.reconciliation.spi;

import java.time.Duration;

/**
 * Story 4.15 (AC5) — the SPI-level filter for {@code ArtifactDriftReadPort.listUnresolved}. Every
 * filter field is nullable = "no filter on this axis"; all rows returned are always {@code
 * resolved_at IS NULL AND archived_at IS NULL}. {@code driftCategory} is a {@code DriftCategory}
 * wire value; {@code timeSince} bounds {@code detected_at >= now() - timeSince}; {@code
 * workflowRunId} narrows to a run; {@code ticketReference} narrows to a run whose typed {@code
 * linear} integration link carries that external ref. {@code limit} is a mandatory positive page
 * cap (story 4.15 review D2 — detection-only accumulates unresolved rows until story 4.16 resolves
 * them, so the read MUST be bounded); the application-facing {@code
 * ArtifactReconciliationService.DriftFilter} resolves a nullable operator limit to this concrete
 * cap.
 */
public record DriftQuery(
    String driftCategory,
    Duration timeSince,
    String workflowRunId,
    String ticketReference,
    int limit) {}
