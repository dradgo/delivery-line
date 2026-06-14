package org.dradgo.application.runner.queue.spi;

/**
 * Story 3.17b (AC2) — application-owned boundary for the low-latency queue wake-up signal. {@link
 * org.dradgo.application.runner.queue.RunnerExecutionQueue#enqueue} fires {@link #notifyQueued()}
 * from a post-commit synchronization (so the {@code queued} row is durable before the wake), and
 * the persistence adapter implementation issues {@code NOTIFY runner_queue_updated}. A dedicated
 * listener connection (also in the adapter layer) {@code LISTEN}s for it and signals idle workers.
 *
 * <p>The port keeps {@code NOTIFY}/JDBC out of the application layer (Trap T11). It is best-effort:
 * {@code LISTEN/NOTIFY} is a latency optimization atop the AC1 backoff poll (Decision D8), so an
 * implementation that fails to deliver must not break enqueue — workers still drain via the poll.
 */
public interface RunnerQueueNotificationPort {

  /** Issue the {@code runner_queue_updated} notification. Must not throw on a transient failure. */
  void notifyQueued();
}
