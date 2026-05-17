package org.dradgo.application.artifact.spi;

import java.util.Optional;

public interface ArtifactPayloadStore {

  String write(
      String workflowRunId,
      String artifactId,
      int version,
      String payloadRef,
      byte[] payloadContent);

  boolean isReadable(String storageRef);

  /**
   * Returns the payload bytes for the given storageRef, or {@link Optional#empty()} when the
   * reference does not point to a readable, contained file. Implementations MUST apply the same
   * containment/symlink/path-traversal guards as {@link #isReadable(String)}.
   */
  Optional<byte[]> readBytes(String storageRef);
}
