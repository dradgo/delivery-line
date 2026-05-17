package org.dradgo.adapters.persistence;

import java.util.Map;
import java.util.Optional;
import org.dradgo.adapters.persistence.mapper.WorkflowRunEntityMapper;
import org.dradgo.adapters.persistence.repository.WorkflowRunRepository;
import org.dradgo.application.workflow.spi.WorkflowRunCreatePort;
import org.dradgo.application.workflow.spi.WorkflowRunReadPort;
import org.dradgo.application.workflow.spi.WorkflowRunSnapshot;
import org.dradgo.application.workflow.spi.WorkflowRunStatePort;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.WorkflowState;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;

@Component
public class WorkflowRunPersistenceAdapter
    implements WorkflowRunReadPort, WorkflowRunCreatePort, WorkflowRunStatePort {

  private final WorkflowRunRepository workflowRunRepository;
  private final WorkflowRunEntityMapper workflowRunEntityMapper;

  public WorkflowRunPersistenceAdapter(
      WorkflowRunRepository workflowRunRepository,
      WorkflowRunEntityMapper workflowRunEntityMapper) {
    this.workflowRunRepository = workflowRunRepository;
    this.workflowRunEntityMapper = workflowRunEntityMapper;
  }

  @Override
  public Optional<WorkflowRunSnapshot> findByPublicId(String publicId) {
    return workflowRunRepository.findByPublicId(publicId).map(workflowRunEntityMapper::toSnapshot);
  }

  @Override
  public WorkflowRunSnapshot create(String publicId, WorkflowState initialState) {
    return workflowRunEntityMapper.toSnapshot(
        workflowRunRepository.saveAndFlush(
            workflowRunEntityMapper.toNewEntity(publicId, initialState)));
  }

  @Override
  public void updateCurrentState(String publicId, WorkflowState targetState, Long expectedVersion) {
    if (expectedVersion == null) {
      throw new OptimisticLockingFailureException(
          "Workflow run state update is missing an optimistic-lock version for " + publicId);
    }
    int updated =
        workflowRunRepository.updateCurrentState(publicId, targetState.value(), expectedVersion);
    if (updated != 1) {
      if (!workflowRunRepository.existsByPublicId(publicId)) {
        throw new DomainException(
            DomainErrorCode.RUN_NOT_FOUND,
            "Workflow run not found: " + publicId,
            Map.of("runId", publicId));
      }
      throw new OptimisticLockingFailureException(
          "Workflow run state update lost optimistic lock for " + publicId);
    }
  }
}
