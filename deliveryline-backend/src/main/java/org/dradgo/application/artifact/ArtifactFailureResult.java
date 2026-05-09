package org.dradgo.application.artifact;

public record ArtifactFailureResult(
	ArtifactRecordSnapshot artifact,
	ArtifactOperationSnapshot operation
) {
}
