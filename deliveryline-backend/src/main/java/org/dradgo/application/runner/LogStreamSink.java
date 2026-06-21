package org.dradgo.application.runner;

/**
 * Story 3d-5 (FR65) — application-side sink the {@link StepLogStreamService} forwards a runner
 * execution's log lifecycle through. The REST controller implements this over an {@code
 * SseEmitter}, keeping the Spring streaming type OUT of the application layer (the FIRST streaming
 * surface; we keep {@code SseEmitter} strictly in {@code adapters.rest}).
 *
 * <p>Lines reaching {@link #onLine} are ALREADY redacted — live lines best-effort ({@code
 * RedactionPolicyService}), finished lines authoritatively (story 3.6 persisted scan). Raw lines
 * never reach this sink (Trap T1/T2).
 */
public interface LogStreamSink {

  /**
   * A single redacted log line. {@code stream} is {@code "stdout"}/{@code "stderr"}; {@code seq} is
   * a monotonically-increasing per-stream-session ordinal so the client can detect gaps / reorder.
   */
  void onLine(String stream, String redactedLine, long seq);

  /**
   * Lifecycle: the stream resolved to a mode ({@code phase} = {@code "live"}/{@code "finished"}).
   */
  void onStatus(String phase, String runnerExecutionId);

  /**
   * Terminal: the stream ended normally ({@code reason} e.g. {@code "finished-replay-complete"}).
   */
  void onEnd(String reason);

  /**
   * Terminal: the stream could not be served ({@code reason} e.g. a denial / unexpected failure).
   */
  void onError(String reason);
}
