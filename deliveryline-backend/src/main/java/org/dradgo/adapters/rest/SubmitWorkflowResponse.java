package org.dradgo.adapters.rest;

import org.dradgo.application.workflow.SubmitWorkflowResult;

public record SubmitWorkflowResponse(
	String workflowRunId,
	String currentState,
	String correlationId
) {

	public static SubmitWorkflowResponse from(SubmitWorkflowResult result) {
		return new SubmitWorkflowResponse(
			result.workflowRunId(),
			result.currentState().value(),
			result.correlationId());
	}
}
