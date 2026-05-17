package org.dradgo.adapters.persistence.mapper;

import org.dradgo.adapters.persistence.entity.ArtifactOperationEntity;
import org.dradgo.application.artifact.ArtifactOperationSnapshot;
import org.springframework.stereotype.Component;

@Component
public class ArtifactOperationEntityMapper {

  public ArtifactOperationSnapshot toSnapshot(ArtifactOperationEntity entity) {
    return new ArtifactOperationSnapshot(
        entity.getPublicId(),
        entity.getWorkflowRun().getPublicId(),
        entity.getArtifact().getPublicId(),
        entity.getOperationType(),
        entity.getStatus(),
        entity.getIdempotencyKey(),
        entity.getFailureCategory(),
        entity.getReason(),
        entity.getCreatedAt());
  }
}
