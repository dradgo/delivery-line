package org.dradgo.application.artifact;

public record RecordArtifactOperationResult(
    ArtifactRecordSnapshot artifact,
    ArtifactOperationSnapshot operation,
    String storageRef,
    ArtifactFailureResult failure) {

  public RecordArtifactOperationResult(
      ArtifactRecordSnapshot artifact, ArtifactOperationSnapshot operation) {
    this(artifact, operation, null, null);
  }

  /**
   * Fresh-write success carrying the canonical {@code storageRef} the payload store wrote the bytes
   * to. Callers that need to promote the just-ingested artifact to {@code available} (the
   * spec-stage ingest path) pass this verbatim to {@link ArtifactOperationService#markAvailable}.
   * It is {@code null} on idempotent replays and on operations with no payload content.
   */
  public RecordArtifactOperationResult(
      ArtifactRecordSnapshot artifact, ArtifactOperationSnapshot operation, String storageRef) {
    this(artifact, operation, storageRef, null);
  }

  public RecordArtifactOperationResult(
      ArtifactRecordSnapshot artifact,
      ArtifactOperationSnapshot operation,
      ArtifactFailureResult failure) {
    this(artifact, operation, null, failure);
  }

  public boolean isFailure() {
    return failure != null;
  }
}
