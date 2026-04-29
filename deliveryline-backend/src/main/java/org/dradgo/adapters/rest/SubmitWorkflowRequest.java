package org.dradgo.adapters.rest;

import org.dradgo.domain.registry.ActorType;

public record SubmitWorkflowRequest(
	String linearTicketReference,
	String actorIdentity,
	ActorType actorType,
	String correlationId
) {
}
