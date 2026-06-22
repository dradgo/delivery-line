package org.dradgo.application.runner.spi;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Application-owned port for the runner-scratch directory under {@code deliveryline.home}. The
 * local filesystem implementation lives in {@code adapters.files} so neither the application layer
 * nor sibling adapters depend on filesystem types directly.
 */
public interface RunnerScratchStore {

  Path contextBundlePath(String runnerExecutionId);

  Path runnerResultPath(String runnerExecutionId);

  Path writeContextBundle(String runnerExecutionId, byte[] redactedBytes);

  /** Best-effort removal used to compensate a failed manual park or cancel it on takeover. */
  void deleteContextBundle(String runnerExecutionId);

  Path writeRunnerResult(String runnerExecutionId, byte[] payloadBytes);

  Optional<byte[]> tryReadRunnerResult(String runnerExecutionId);

  /**
   * Read the redacted bundle bytes previously written by {@link #writeContextBundle} for the given
   * runner execution. Returns {@link Optional#empty()} when the scratch file has been evicted /
   * pruned (scratch is not durable storage). Story 2.8 AC7: used by {@code
   * WorkflowInspectionService.getContextBundleForArtifact} as the primary FR55 read path.
   */
  Optional<byte[]> tryReadContextBundle(String runnerExecutionId);

  /**
   * Read the bytes of an artifact file referenced by a runner result's {@code contentReference}
   * field. The reference is resolved as a relative multi-segment path under {@code
   * {deliveryline.home}/runner-scratch/{runnerExecutionId}/}, with the same containment guard as
   * {@link #tryReadRunnerResult}: absolute paths, parent-segment escapes, and symlink swaps that
   * would resolve outside {@code deliveryline.home} are rejected and surface as an empty {@link
   * Optional}.
   */
  Optional<byte[]> tryReadArtifactContent(String runnerExecutionId, String relativeReference);

  /**
   * Write the bytes of an artifact file referenced by a runner result's {@code contentReference}
   * field. Used by deterministic test/mock runners; real Docker runners write directly.
   */
  Path writeArtifactContent(
      String runnerExecutionId, String relativeReference, byte[] payloadBytes);
}
