package org.dradgo.application.runner.spi;

import java.util.Optional;
import org.dradgo.application.runner.RedactedRunnerLog;
import org.dradgo.application.runner.RunnerLogReference;

/**
 * Story 3.6 AC1/AC5 — application-owned port for the durable, redacted runner-log store rooted at
 * {@code {deliveryline.home}/runner-logs/{runnerExecutionId}/}.
 *
 * <p>This root is deliberately SEPARATE from {@code runner-work/{rex}/} (Trap T7): the workspace
 * store holds the raw, container-written logs reaped by story-3.2 cleanup at {@code
 * workspace-retention-hours} (24h default); this store holds the {@link
 * org.dradgo.application.security.RedactionPolicyService}-redacted streams that persist
 * independently for the full 60-day window (NFR31, AC5). Keeping the roots separate guarantees
 * workspace cleanup can never delete the durable redacted log.
 *
 * <p>Implemented by {@code adapters.files.LocalRunnerLogStore}. Every method enforces the same
 * containment + {@code rex_}-prefix guards as {@link RunnerWorkspaceStore}: ids go through {@link
 * org.dradgo.domain.id.PublicIdPrefixes#require(String, org.dradgo.domain.id.PublicIdPrefixes)},
 * resolved paths must remain under the configured runner-logs root.
 *
 * <p><b>Bytes are already redacted.</b> Callers (the capture service) MUST redact each stream via
 * {@code RedactionPolicyService} before invoking {@link #write}; the store performs NO credential
 * detection (Trap T1/T2 — credential detection stays in {@code application.security}).
 */
public interface RunnerLogStore {

  /**
   * Atomically write the already-redacted {@code runner.stdout} + {@code runner.stderr} bytes to
   * {@code runner-logs/{rex}/} and return a reference whose {@link
   * RunnerLogReference#referencePath} is the absolute store directory and {@link
   * RunnerLogReference#byteSize} is the sum of the two written files. The returned {@code
   * classification}/{@code redactionCount} are placeholders ({@code LOCAL_ONLY}/{@code 0}) — the
   * capture service owns those derived values.
   */
  RunnerLogReference write(String runnerExecutionId, byte[] redactedStdout, byte[] redactedStderr);

  /**
   * Return a reference to a previously-written redacted log directory if it exists. Carries the
   * directory path + on-disk byte size only; returns {@link Optional#empty()} when nothing was
   * captured for the id (honest "not captured" for inspection's unavailable result).
   */
  Optional<RunnerLogReference> find(String runnerExecutionId);

  /**
   * Story 3d-5 (FR65, AC1) — read the already-redacted stdout + stderr TEXT for a previously-written
   * runner-log directory, capped + lossy-UTF-8 decoded, for the finished-mode Step Execution Log
   * Viewer replay. Returns {@link Optional#empty()} when nothing was captured for the id (honest
   * "not captured"). The bytes are the authoritative story-3.6 redacted output — callers MUST NOT
   * re-redact (Trap T4). This reads the REDACTED store only, never the raw workspace store, so the
   * {@code RAW_RUNNER_OUTPUT_READS_STAY_IN_RUNNER_ADAPTER} boundary is untouched.
   */
  Optional<RedactedRunnerLog> readRedacted(String runnerExecutionId);
}
