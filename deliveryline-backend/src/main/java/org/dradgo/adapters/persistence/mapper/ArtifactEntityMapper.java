package org.dradgo.adapters.persistence.mapper;

import org.dradgo.adapters.persistence.entity.ArtifactEntity;
import org.dradgo.application.artifact.ArtifactRecordSnapshot;
import org.springframework.stereotype.Component;

@Component
public class ArtifactEntityMapper {

  public ArtifactRecordSnapshot toSnapshot(ArtifactEntity entity) {
    return new ArtifactRecordSnapshot(
        entity.getPublicId(),
        entity.getWorkflowRun().getPublicId(),
        entity.getArtifactType(),
        entity.getVersion(),
        entity.getParentArtifact() == null ? null : entity.getParentArtifact().getPublicId(),
        entity.getClassification(),
        entity.getStorageRef(),
        entity.getChecksumAlgorithm(),
        entity.getChecksumValue(),
        entity.getFailureCategory(),
        entity.getFailureReason(),
        entity.getStatus(),
        entity.getArchivedAt(),
        entity.isLineageRecovery(),
        entity.getCreatedAt());
  }
}
