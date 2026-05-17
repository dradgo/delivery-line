package org.dradgo.application.artifact;

public record RecordArtifactOperationResult(
    ArtifactRecordSnapshot artifact,
    ArtifactOperationSnapshot operation,
    ArtifactFailureResult failure) {

  public RecordArtifactOperationResult(
      ArtifactRecordSnapshot artifact, ArtifactOperationSnapshot operation) {
    this(artifact, operation, null);
  }

  public boolean isFailure() {
    return failure != null;
  }
}
