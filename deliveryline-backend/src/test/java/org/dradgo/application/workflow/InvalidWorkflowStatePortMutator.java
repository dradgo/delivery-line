package org.dradgo.application.workflow;

import org.dradgo.application.workflow.spi.WorkflowRunStatePort;
import org.dradgo.domain.registry.WorkflowState;

public final class InvalidWorkflowStatePortMutator {

	private final WorkflowRunStatePort workflowRunStatePort;

	public InvalidWorkflowStatePortMutator(WorkflowRunStatePort workflowRunStatePort) {
		this.workflowRunStatePort = workflowRunStatePort;
	}

	void mutate() {
		workflowRunStatePort.updateCurrentState("run_test1234", WorkflowState.EXECUTING, 1L);
	}
}
