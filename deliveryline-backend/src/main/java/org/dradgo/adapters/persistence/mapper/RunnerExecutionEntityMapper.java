package org.dradgo.adapters.persistence.mapper;

import org.dradgo.adapters.persistence.entity.RunnerExecutionEntity;
import org.dradgo.application.runner.spi.RunnerExecutionSnapshot;
import org.springframework.stereotype.Component;

@Component
public class RunnerExecutionEntityMapper {

  public RunnerExecutionSnapshot toSnapshot(RunnerExecutionEntity entity) {
    return new RunnerExecutionSnapshot(
        entity.getPublicId(),
        entity.getWorkflowRun().getPublicId(),
        entity.getStage(),
        entity.getStatus(),
        entity.getContextBundleVersion(),
        entity.getLastActivityAt(),
        entity.getTimeoutAt(),
        entity.getFailureCategory(),
        entity.getCompletedAt(),
        entity.getCreatedAt(),
        entity.getArchivedAt());
  }
}
