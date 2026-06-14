package org.dradgo.adapters.persistence.mapper;

import org.dradgo.adapters.persistence.entity.RunnerExecutionEntity;
import org.dradgo.application.runner.spi.RunnerExecutionSnapshot;
import org.dradgo.domain.registry.DataClassification;
import org.springframework.stereotype.Component;

@Component
public class RunnerExecutionEntityMapper {

  public RunnerExecutionSnapshot toSnapshot(RunnerExecutionEntity entity) {
    String rawClassification = entity.getRawOutputClassification();
    DataClassification classification =
        rawClassification == null
            ? null
            : DataClassification.fromValue(
                rawClassification, "runner_executions.raw_output_classification");
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
        entity.getArchivedAt(),
        entity.getHeartbeatStaleEmittedAt(),
        entity.getRawOutputReference(),
        classification,
        entity.getRawOutputByteSize(),
        entity.getRedactionCount(),
        entity.getDispatchedAt(),
        entity.getWorkerId(),
        entity.getQueuePriority(),
        entity.getQueueAttemptCount(),
        entity.getCorrelationId());
  }
}
