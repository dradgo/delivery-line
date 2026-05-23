package org.dradgo.adapters.persistence.mapper;

import org.dradgo.adapters.persistence.entity.ApprovalEntity;
import org.dradgo.application.approval.ApprovalSnapshot;
import org.springframework.stereotype.Component;

/**
 * Maps {@link ApprovalEntity} → {@link ApprovalSnapshot} for application-layer reads (story 2.8).
 * Read-only at this story's scope; writers will eventually require an inverse mapping when {@code
 * ApprovalService} (story 2.9) lands.
 */
@Component
public class ApprovalEntityMapper {

  public ApprovalSnapshot toSnapshot(ApprovalEntity entity) {
    return new ApprovalSnapshot(
        entity.getPublicId(),
        entity.getWorkflowRun().getPublicId(),
        entity.getArtifact().getPublicId(),
        entity.getArtifactVersion(),
        entity.getContextBundleVersion(),
        entity.getActorIdentity(),
        entity.getActorType(),
        entity.getReviewerRole(),
        entity.getDecision(),
        entity.getReason(),
        entity.getRejectionTaxonomy(),
        entity.getDecidedAt());
  }
}
