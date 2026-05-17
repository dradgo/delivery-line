package org.dradgo.application.artifact;

public record ArtifactVersionRequest(
    String lineageMemberArtifactId,
    String payloadRef,
    ActorContext actor,
    String operationType,
    String operationPublicId,
    String idempotencyKey,
    String runnerExecutionId) {

  public ArtifactVersionRequest {
    if (lineageMemberArtifactId == null || lineageMemberArtifactId.isBlank()) {
      throw new IllegalArgumentException("lineageMemberArtifactId must not be blank");
    }
    if (actor == null) {
      throw new IllegalArgumentException("actor must not be null");
    }
  }
}
