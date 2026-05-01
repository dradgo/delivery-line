package org.dradgo.application.workflow.commands;

import org.dradgo.domain.registry.ActorType;

public sealed interface WorkflowCommand
	permits SubmitWorkflowCommand, ApproveSpecCommand, RejectSpecCommand, RetryWorkflowCommand, TakeoverWorkflowCommand {

	String actorIdentity();

	ActorType actorType();

	String idempotencyKey();

	String correlationId();

	default String commandType() {
		return getClass().getSimpleName();
	}
}
