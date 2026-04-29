package org.dradgo.adapters.rest;

import org.dradgo.domain.registry.ActorType;

public record RetryWorkflowRequest(
	String actorIdentity,
	ActorType actorType,
	String correlationId,
	String reasonText
) {
}
