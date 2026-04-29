package org.dradgo.application.workflow;

import org.dradgo.domain.registry.WorkflowState;

public record SubmitWorkflowResult(
	String workflowRunId,
	WorkflowState currentState,
	String correlationId
) implements DomainResult {
}
