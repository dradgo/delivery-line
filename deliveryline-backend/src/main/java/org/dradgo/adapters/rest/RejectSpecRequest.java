package org.dradgo.adapters.rest;

import org.dradgo.domain.registry.ActorType;

public record RejectSpecRequest(
	String artifactId,
	Integer artifactVersion,
	Integer contextVersion,
	String actorIdentity,
	ActorType actorType,
	String correlationId,
	String reasonText
) {
}
