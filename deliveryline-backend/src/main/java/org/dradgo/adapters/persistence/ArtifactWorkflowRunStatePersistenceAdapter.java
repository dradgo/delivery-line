package org.dradgo.adapters.persistence;

import java.util.Optional;
import org.dradgo.adapters.persistence.repository.WorkflowRunRepository;
import org.dradgo.application.artifact.spi.ArtifactWorkflowRunStatePort;
import org.dradgo.domain.registry.WorkflowState;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class ArtifactWorkflowRunStatePersistenceAdapter implements ArtifactWorkflowRunStatePort {

	private final WorkflowRunRepository workflowRunRepository;

	public ArtifactWorkflowRunStatePersistenceAdapter(WorkflowRunRepository workflowRunRepository) {
		this.workflowRunRepository = workflowRunRepository;
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<WorkflowState> currentState(String workflowRunId) {
		return workflowRunRepository.findByPublicId(workflowRunId).map(run -> run.getCurrentState());
	}
}
