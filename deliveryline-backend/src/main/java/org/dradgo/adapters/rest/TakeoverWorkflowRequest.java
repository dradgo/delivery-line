package org.dradgo.adapters.rest;

import org.dradgo.domain.registry.ActorType;

public record TakeoverWorkflowRequest(
	String actorIdentity,
	ActorType actorType,
	String correlationId,
	String reasonText
) {
}
