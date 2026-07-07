package org.dradgo.application.integration.conflict.spi;

/**
 * Story 4.17 (AC7) — one grouped {@code (conflict_category, integration_type)} count of currently
 * UNRESOLVED, non-archived conflicts, read by {@code IntegrationConflictMetricsBinder} to publish
 * the {@code deliveryline_integration_conflict_unresolved_count{category,integration}} gauge.
 * {@code integrationType} is the joined {@code integration_links.integration_type} (nullable if the
 * link row is gone, though the RESTRICT FK makes that unlikely) — the binder tags it {@code
 * "unknown"} in that case.
 */
public record UnresolvedConflictCount(
    String conflictCategory, String integrationType, long count) {}
