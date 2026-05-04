package org.dradgo.adapters.persistence.entity;

import org.dradgo.domain.registry.WorkflowState;

public final class InvalidWorkflowStateMutator {

	void mutate(WorkflowRunEntity entity) {
		entity.setCurrentState(WorkflowState.EXECUTING);
	}
}
