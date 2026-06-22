package org.dradgo.application.runner.spi;

/**
 * Story 3d-6 (FR68, ADR 0025) — application-owned port for attaching a READ-ONLY diagnostic console
 * to a LIVE runner execution's container pty/stdio. The SECOND streaming surface (after story
 * 3d-5's log follow); a near-symmetric twin of {@link RunnerLogStreamPort} with three deltas: it
 * attaches the live container stdio ({@code attachContainerCmd}) rather than following the demuxed
 * log stream; it is <b>LIVE-ONLY</b> (no finished-mode fallback — there is no console into an
 * absent container, DD-3); and the session is governed-history-recorded by the orchestration
 * service.
 *
 * <p>The implementation lives in {@code adapters.runner.docker} so docker-java stays confined to
 * the gateway (ArchUnit {@code ADAPTERS_RUNNER_DOCKER_TYPES_STAY_BEHIND_GATEWAY} + {@code
 * application-cannot-import-adapters}); a no-op fallback ({@code !runners.docker}) returns a
 * not-live subscription so the application layer rejects with {@code console-not-live} rather than
 * attaching.
 *
 * <p><b>Read-only guarantee (DD-1 / Trap T6).</b> The attach is opened WITHOUT stdin — no input
 * channel is ever wired into the container. Raw chunks flow through {@link RawConsoleSink} and must
 * be redacted (best-effort) by the application orchestration BEFORE leaving the method-local lambda
 * — no field or column ever holds a raw chunk (Trap T1). The authoritative redaction guarantee
 * remains the story-3.6 persisted post-hoc scan, which the console never touches (Trap T2).
 */
public interface RunnerConsoleStreamPort {

  /**
   * Attach a read-only console to the live container for {@code runnerExecutionId}. Each decoded
   * chunk is delivered to {@code onChunk}; {@code onEnd} fires once when the container exits / the
   * attach completes. Returns a {@link ConsoleSubscription} whose {@link
   * ConsoleSubscription#isLive()} is {@code false} when no live container exists for the rex (the
   * caller then rejects with {@code console-not-live} — there is NO finished-mode fallback, DD-3).
   *
   * <p>Implementations MUST be best-effort: an attach failure degrades to {@code onEnd} / a
   * not-live subscription and NEVER throws into the (SSE) caller thread.
   */
  ConsoleSubscription attachConsole(
      String runnerExecutionId, RawConsoleSink onChunk, Runnable onEnd);

  /**
   * Sink for a single decoded raw console chunk. {@code stream} is {@code "stdout"} or {@code
   * "stderr"}.
   */
  @FunctionalInterface
  interface RawConsoleSink {
    void accept(String stream, String rawChunk);
  }

  /**
   * Handle for an in-flight console attach. {@link #close()} stops the attach and releases the
   * docker callback (no leaked attach threads — Trap T3). {@link #isLive()} reports whether a live
   * container was actually attached.
   */
  interface ConsoleSubscription extends AutoCloseable {

    /** {@code true} when a live container was found and is being attached. */
    boolean isLive();

    /** Stop the attach and release any underlying resources. Idempotent; never throws. */
    @Override
    void close();

    /**
     * A subscription for the no-live-container case — the caller rejects with {@code
     * console-not-live} (LIVE-ONLY, DD-3).
     */
    static ConsoleSubscription notLive() {
      return new ConsoleSubscription() {
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
