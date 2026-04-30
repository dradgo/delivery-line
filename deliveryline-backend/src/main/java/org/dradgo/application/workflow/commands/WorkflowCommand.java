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

	// Trims a possibly-blank optional value to either a non-blank string or null. Called from
	// each command record's compact constructor so callers see one canonical correlationId
	// regardless of whether transports passed null, "", or a padded value.
	static String normalizeOptional(String value) {
		if (value == null) {
			return null;
		}
		String trimmed = value.trim();
		return trimmed.isEmpty() ? null : trimmed;
	}
}
