package org.dradgo.adapters.rest;

import org.dradgo.domain.registry.ActorType;

public record ApproveSpecRequest(
	String artifactId,
	Integer artifactVersion,
	Integer contextVersion,
	String actorIdentity,
	ActorType actorType,
	String correlationId
) {
}
