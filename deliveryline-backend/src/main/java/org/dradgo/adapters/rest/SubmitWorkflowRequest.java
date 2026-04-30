package org.dradgo.adapters.rest;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.dradgo.domain.registry.ActorType;

public record SubmitWorkflowRequest(
	@NotBlank @Size(max = 128) String linearTicketReference,
	@NotBlank @Size(max = 128) String actorIdentity,
	@NotNull ActorType actorType,
	@Size(max = 128) String correlationId
) {
}
