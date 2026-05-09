package org.dradgo.application.artifact;

public record ArtifactAvailabilityResult(
	ArtifactRecordSnapshot artifact,
	ArtifactOperationSnapshot operation
) {
}
