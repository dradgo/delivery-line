package org.dradgo.application.workflow;

import org.dradgo.domain.registry.WorkflowState;

public sealed interface DomainResult permits SubmitWorkflowResult, WorkflowStateChangeResult {

	String workflowRunId();

	WorkflowState currentState();

	String correlationId();
}
