package org.dradgo.adapters.persistence.mapper;

import java.util.LinkedHashMap;
import org.dradgo.adapters.persistence.entity.WorkflowEventEntity;
import org.dradgo.adapters.persistence.entity.WorkflowRunEntity;
import org.dradgo.application.workflow.spi.WorkflowEventRecord;
import org.springframework.stereotype.Component;

@Component
public class WorkflowEventEntityMapper {

	public WorkflowEventEntity toEntity(WorkflowEventRecord eventRecord, WorkflowRunEntity workflowRun) {
		WorkflowEventEntity entity = new WorkflowEventEntity();
		entity.setPublicId(eventRecord.publicId());
		entity.setWorkflowRun(workflowRun);
		entity.setEventType(eventRecord.eventType());
		entity.setPriorState(eventRecord.priorState());
		entity.setResultingState(eventRecord.resultingState());
		entity.setActorIdentity(eventRecord.actorIdentity());
		entity.setActorType(eventRecord.actorType());
		entity.setReason(eventRecord.reason());
		entity.setFailureCategory(eventRecord.failureCategory());
		entity.setInterventionMarker(eventRecord.interventionMarker());
		entity.setCreatedAt(eventRecord.createdAt());
		entity.setDetails(eventRecord.details() == null
			? new LinkedHashMap<>()
			: new LinkedHashMap<>(eventRecord.details()));
		return entity;
	}

	public WorkflowEventRecord toRecord(WorkflowEventEntity entity) {
		return new WorkflowEventRecord(
			entity.getPublicId(),
			entity.getWorkflowRun().getPublicId(),
			entity.getEventType(),
			entity.getPriorState(),
			entity.getResultingState(),
			entity.getActorIdentity(),
			entity.getActorType(),
			entity.getReason(),
			entity.getFailureCategory(),
			entity.isInterventionMarker(),
			entity.getCreatedAt(),
			entity.getDetails() == null
				? new LinkedHashMap<>()
				: new LinkedHashMap<>(entity.getDetails()));
	}
}
