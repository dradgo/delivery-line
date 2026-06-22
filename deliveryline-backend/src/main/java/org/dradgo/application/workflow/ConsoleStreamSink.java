package org.dradgo.application.workflow;

/**
 * Story 3d-6 (FR68, ADR 0025) — application-side sink the {@link DiagnosticConsoleService} forwards
 * a read-only console session's lifecycle through. The REST controller implements this over an
 * {@code SseEmitter}, keeping the Spring streaming type OUT of the application layer (mirrors
 * {@link LogStreamSink}).
 *
 * <p>Lives under {@code application.workflow} (the controller-facing service surface) rather than
 * {@code application.runner} so the REST controller can depend on it without tripping the {@code
 * rest_controllers_stay_thin_and_avoid_spi_or_persistence_or_runner} ArchUnit rule (which forbids a
 * {@code adapters.rest} → {@code application.runner} edge — the exact reason story 3d-5 placed
 * {@link LogStreamSink} / {@link StepLogStreamService} here too).
 *
 * <p>Chunks reaching {@link #onChunk} are ALREADY redacted (best-effort, {@code
 * RedactionPolicyService}). Raw chunks never reach this sink (Trap T1/T2). The console is
 * read-only: there is NO input method — no chunk ever flows back toward the container.
 */
public interface ConsoleStreamSink {

  /**
   * A single redacted console chunk. {@code stream} is {@code "stdout"}/{@code "stderr"}; {@code
   * seq} is a monotonically-increasing per-session ordinal so the client can detect gaps / reorder.
   */
  void onChunk(String stream, String redactedChunk, long seq);

  /** Lifecycle: the console resolved to a phase ({@code phase} = {@code "live"}). */
  void onStatus(String phase, String runnerExecutionId);

  /**
   * Terminal: the console session ended normally ({@code reason} e.g. {@code "container-exited"}).
   */
  void onEnd(String reason);

  /**
   * Terminal: the console could not be served ({@code reason} e.g. {@code "console-not-live"} / a
   * denial / unexpected failure).
   */
  void onError(String reason);
}
