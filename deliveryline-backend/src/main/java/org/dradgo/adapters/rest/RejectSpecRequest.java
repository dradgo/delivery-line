package org.dradgo.adapters.rest;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.dradgo.domain.registry.ActorType;

public record RejectSpecRequest(
	@NotBlank @Size(max = 128) String artifactId,
	@NotNull @Positive Integer artifactVersion,
	@NotNull @Positive Integer contextVersion,
	@NotBlank @Size(max = 128) String actorIdentity,
	@NotNull ActorType actorType,
	@Size(max = 128) String correlationId,
	@NotBlank @Size(max = 512) String reasonText
) {
}
