package org.dradgo.application.runner.spi;

/**
 * Story 3d-5 (FR65, ADR 0025) — application-owned port for FOLLOWING a runner execution's live
 * container logs. The FIRST streaming surface in the backend.
 *
 * <p>The implementation lives in {@code adapters.runner.docker} so docker-java stays confined to
 * the gateway (ArchUnit {@code ADAPTERS_RUNNER_DOCKER_TYPES_STAY_BEHIND_GATEWAY} + {@code
 * application-cannot-import-adapters}); a no-op fallback ({@code !runners.docker}) returns a
 * not-live subscription so the application layer transparently falls back to FINISHED mode (replay
 * of the story-3.6 persisted, already-redacted log).
 *
 * <p><b>Scope guard.</b> This port ONLY follows the live Docker stream — it persists nothing (ADR
 * 0025 D4: no new store/table). Raw lines flow through {@link RawLogLineSink} and must be redacted
 * (best-effort) by the application orchestration BEFORE leaving the method-local lambda — no field
 * or column ever holds a raw line (Trap T1). The authoritative redaction guarantee remains the
 * story-3.6 persisted post-hoc scan (Trap T2).
 */
public interface RunnerLogStreamPort {

  /**
   * Begin following the live container logs for {@code runnerExecutionId}. Each decoded line is
   * delivered to {@code onLine}; {@code onEnd} fires once when the container exits / the stream
   * completes. Returns a {@link LiveLogSubscription} whose {@link LiveLogSubscription#isLive()} is
   * {@code false} when no live container exists for the rex (the caller then serves finished mode).
   *
   * <p>Implementations MUST be best-effort: a follow failure degrades to {@code onEnd} / a not-live
   * subscription and NEVER throws into the (SSE) caller thread.
   */
  LiveLogSubscription followLiveLogs(
      String runnerExecutionId, RawLogLineSink onLine, Runnable onEnd);

  /**
   * Sink for a single decoded raw log line. {@code stream} is {@code "stdout"} or {@code "stderr"}.
   */
  @FunctionalInterface
  interface RawLogLineSink {
    void accept(String stream, String rawLine);
  }

  /**
   * Handle for an in-flight live follow. {@link #close()} stops following and releases the docker
   * callback (no leaked follow threads — Trap T3). {@link #isLive()} reports whether a live
   * container was actually attached.
   */
  interface LiveLogSubscription extends AutoCloseable {

    /** {@code true} when a live container was found and is being followed. */
    boolean isLive();

    /** Stop following and release any underlying resources. Idempotent; never throws. */
    @Override
    void close();

    /** A subscription for the no-live-container case — the caller falls back to finished mode. */
    static LiveLogSubscription notLive() {
      return new LiveLogSubscription() {
        @Override
        public boolean isLive() {
          return false;
        }

        @Override
        public void close() {
          // No-op: nothing was attached.
        }
      };
    }
  }
}
