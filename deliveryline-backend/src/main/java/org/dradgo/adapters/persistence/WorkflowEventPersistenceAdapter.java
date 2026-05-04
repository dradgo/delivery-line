package org.dradgo.adapters.persistence;

import java.util.Map;
import org.dradgo.adapters.persistence.mapper.WorkflowEventEntityMapper;
import org.dradgo.adapters.persistence.repository.WorkflowEventRepository;
import org.dradgo.adapters.persistence.repository.WorkflowRunRepository;
import org.dradgo.application.workflow.spi.WorkflowEventRecord;
import org.dradgo.application.workflow.spi.WorkflowEventWritePort;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.DomainErrorCode;
import org.springframework.stereotype.Component;

@Component
public class WorkflowEventPersistenceAdapter implements WorkflowEventWritePort {

	private final WorkflowEventRepository workflowEventRepository;
	private final WorkflowRunRepository workflowRunRepository;
	private final WorkflowEventEntityMapper workflowEventEntityMapper;

	public WorkflowEventPersistenceAdapter(
		WorkflowEventRepository workflowEventRepository,
		WorkflowRunRepository workflowRunRepository,
		WorkflowEventEntityMapper workflowEventEntityMapper
	) {
		this.workflowEventRepository = workflowEventRepository;
		this.workflowRunRepository = workflowRunRepository;
		this.workflowEventEntityMapper = workflowEventEntityMapper;
	}

	@Override
	public void append(WorkflowEventRecord eventRecord) {
		var workflowRun = workflowRunRepository.findByPublicId(eventRecord.workflowRunPublicId())
			.orElseThrow(() -> new DomainException(
				DomainErrorCode.RUN_NOT_FOUND,
				"Workflow run not found while appending event: " + eventRecord.workflowRunPublicId(),
				Map.of("runId", eventRecord.workflowRunPublicId())));
		workflowEventRepository.saveAndFlush(
			workflowEventEntityMapper.toEntity(eventRecord, workflowRun));
	}
}
