package org.dradgo.application.workflow.spi;

import java.time.OffsetDateTime;
import org.dradgo.domain.registry.FailureTaxonomyValue;

/**
 * Story 4.9 — a point-in-time snapshot of the {@code workflow_runs} failure-classification triple
 * ({@code failure_classification}, {@code failure_classified_at}, {@code failure_classified_by}).
 * All three columns are set or null together (the V44 {@code
 * ck_workflow_runs_failure_classification_complete} invariant), so an absent classification is
 * represented as {@code Optional.empty()} at the port surface, never a partially-null record.
 */
public record FailureClassificationRecord(
    FailureTaxonomyValue taxonomyValue, OffsetDateTime classifiedAt, String classifiedBy) {}
