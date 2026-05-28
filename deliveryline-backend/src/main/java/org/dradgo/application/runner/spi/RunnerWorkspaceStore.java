package org.dradgo.application.runner.spi;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Application-owned port for the runner-workspace directory at {@code
 * {deliveryline.home}/runner-work/{runnerExecutionId}/}. Distinct from {@link RunnerScratchStore}
 * on purpose (story 3.1 trap T1): scratch holds the broker-owned bundle + leaf result file the mock
 * and broker correlate on; the workspace holds the adapter-owned {@code input/}, {@code output/},
 * {@code logs/} subdirectory tree that becomes the runner container's bind-mount surface.
 *
 * <p>Implemented by {@code adapters.files.LocalRunnerWorkspaceStore}. Every method enforces the
 * same containment + symlink-escape guards as {@link RunnerScratchStore}: rex-ids go through {@link
 * org.dradgo.domain.id.PublicIdPrefixes#require(String, org.dradgo.domain.id.PublicIdPrefixes)},
 * resolved paths must remain under {@code deliveryline.home}, and read paths use {@link
 * java.nio.file.LinkOption#NOFOLLOW_LINKS}.
 */
public interface RunnerWorkspaceStore {

  /**
   * Create (or open) the workspace directory tree for the given runner execution and return a typed
   * handle to the four absolute, canonical paths. Idempotent: re-{@code prepare}-ing the same id
   * returns the existing layout without mutating its contents.
   *
   * <p>Permissions: POSIX {@code 0700} on the root and each subdirectory; an equivalent owner-only
   * ACL on Windows where POSIX views are unavailable.
   */
  WorkspaceLayout prepare(String runnerExecutionId);

  /**
   * Atomically write the redacted context-bundle bytes (already validated by the broker) to {@code
   * input/context-bundle.v1.json}. Same temp-file + rename pattern as {@code
   * LocalRunnerScratchStore.writeContextBundle} so a partial write can never expose a truncated
   * bundle to the runner container. Returns the absolute path of the written file.
   *
   * <p>Story 3.1 trap T4: the adapter (the only caller) does NOT re-validate the bundle here — the
   * broker already validated; re-validating would mask state drift between scratch and workspace.
   * Story 3.1 OQ-4: filename is {@code context-bundle.v1.json} (aligns with scratch leaf + schema
   * $id versioning).
   */
  Path writeInputBundle(String runnerExecutionId, byte[] bundleBytes);

  /**
   * Read {@code output/runner-result.v1.json} for the runner execution if the file exists. Returns
   * {@link Optional#empty()} for any of: workspace not prepared, file missing, file is a symlink
   * whose real path escapes {@code deliveryline.home}, IO failure. Never throws on a missing or
   * unreadable file — the caller (broker via the adapter) interprets emptiness as "no result file
   * written" and classifies accordingly (AC3).
   */
  Optional<byte[]> tryReadResult(String runnerExecutionId);

  /**
   * Resolve the absolute host-side path of the workspace's {@code output/} subdirectory, used by
   * story 3.6 to mount the logs container-side. Returns {@link Optional#empty()} if {@code prepare}
   * was never called for this rex-id.
   */
  Optional<Path> resolveOutputRoot(String runnerExecutionId);
}
