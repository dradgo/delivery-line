package org.dradgo.adapters.persistence.mapper;

import org.dradgo.adapters.persistence.entity.StepReviewEntity;
import org.dradgo.application.review.StepReviewSnapshot;
import org.springframework.stereotype.Component;

/**
 * Story 3d-2 (AC2) — maps {@link StepReviewEntity} → {@link StepReviewSnapshot}. Dereferences the
 * LAZY {@code workflowRun}/{@code runnerExecution}/{@code reviewedArtifact} associations, so
 * callers must supply an entity loaded via a {@code join fetch} query (or inside a transaction) —
 * see {@code StepReviewRepository}.
 */
@Component
public class StepReviewEntityMapper {

  public StepReviewSnapshot toSnapshot(StepReviewEntity entity) {
    return new StepReviewSnapshot(
        entity.getPublicId(),
        entity.getWorkflowRun().getPublicId(),
        entity.getRunnerExecution().getPublicId(),
        entity.getReviewedArtifact().getPublicId(),
        entity.getReviewedArtifactVersion(),
        entity.getOutcome(),
        entity.getRationale(),
        entity.getReviewerModelIdentity(),
        entity.getProducerModelIdentity(),
        entity.getCreatedAt());
  }
}
