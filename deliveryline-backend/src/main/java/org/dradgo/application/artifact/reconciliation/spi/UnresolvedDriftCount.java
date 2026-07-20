package org.dradgo.application.artifact.reconciliation.spi;

import org.dradgo.domain.registry.DriftCategory;

/**
 * Story 4.15 (AC7) — one grouped {@code drift_category} count of currently unresolved drift, the
 * gauge snapshot source. {@code driftCategory} is parsed at the adapter's row mapper via {@code
 * PersistedRegistryValues.artifactDriftCategory}.
 */
public record UnresolvedDriftCount(DriftCategory driftCategory, long count) {}
