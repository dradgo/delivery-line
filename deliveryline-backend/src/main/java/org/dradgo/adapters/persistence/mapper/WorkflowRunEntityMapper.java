package org.dradgo.adapters.persistence.mapper;

import org.dradgo.adapters.persistence.entity.WorkflowRunEntity;
import org.dradgo.application.workflow.spi.WorkflowRunSnapshot;
import org.dradgo.domain.registry.WorkflowState;
import org.springframework.stereotype.Component;

@Component
public class WorkflowRunEntityMapper {

  public WorkflowRunEntity toNewEntity(String publicId, WorkflowState initialState) {
    return WorkflowRunEntity.create(publicId, initialState);
  }

  public WorkflowRunSnapshot toSnapshot(WorkflowRunEntity entity) {
    return new WorkflowRunSnapshot(
        entity.getPublicId(),
        entity.getCurrentState(),
        entity.getArchivedAt(),
        entity.getVersion(),
        entity.getSpecRejectionLoopCount(),
        entity.isEscalationMarkerSet());
  }
}
