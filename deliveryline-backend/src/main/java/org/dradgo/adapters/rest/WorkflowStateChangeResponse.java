package org.dradgo.adapters.rest;

import org.dradgo.application.workflow.WorkflowStateChangeResult;

public record WorkflowStateChangeResponse(
	String workflowRunId,
	String currentState,
	String correlationId
) {

	public static WorkflowStateChangeResponse from(WorkflowStateChangeResult result) {
		return new WorkflowStateChangeResponse(
			result.workflowRunId(),
			result.currentState().value(),
			result.correlationId());
	}
}
