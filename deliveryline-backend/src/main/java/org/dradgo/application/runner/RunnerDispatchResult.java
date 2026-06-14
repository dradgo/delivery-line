package org.dradgo.application.runner;

import java.util.Objects;

public sealed interface RunnerDispatchResult {

  RunnerExecutionHandle handle();

  default boolean isReplay() {
    return this instanceof Replayed;
  }

  /**
   * Story 3.17b — {@code true} when the outcome is a newly ENQUEUED execution (the queue activation
   * replaced the synchronous dispatch; the real adapter dispatch happens later on a worker thread).
   * Lets callers + logging report "queued" rather than "dispatched" without instanceof gymnastics
   * (Trap T1 — the silent breaker).
   */
  default boolean isQueued() {
    return this instanceof Queued;
  }

  /**
   * @param handle the new runner-execution handle
   * @param ack the adapter dispatch acknowledgement
   * @param runnerDispatchedEventPublicId story 3.2a AC10 — the {@code runner.dispatched} event id
   *     ({@code evt_…}) emitted on the docker path, or {@code null} on the mock path (which emits
   *     {@code runner.started} inside the dispatch transaction instead). Lets a retry surface the
   *     audit anchor for the new dispatch.
   */
  record Dispatched(
      RunnerExecutionHandle handle, RunnerDispatchAck ack, String runnerDispatchedEventPublicId)
      implements RunnerDispatchResult {

    public Dispatched {
      Objects.requireNonNull(handle, "handle");
      Objects.requireNonNull(ack, "ack");
    }

    /** Convenience for callers (and tests) that do not surface the dispatched-event anchor. */
    public Dispatched(RunnerExecutionHandle handle, RunnerDispatchAck ack) {
      this(handle, ack, null);
    }
  }

  record Replayed(RunnerExecutionHandle handle) implements RunnerDispatchResult {

    public Replayed {
      Objects.requireNonNull(handle, "handle");
    }
  }

  /**
   * Story 3.17b (AC3 / Trap T1) — the execution was placed on the {@link
   * org.dradgo.application.runner.queue.RunnerExecutionQueue}; the bounded worker pool will run the
   * actual dispatch (bundle compose + adapter dispatch) later. The {@code handle} carries the
   * minted {@code rex_…} id in status {@code queued} (no {@code ack} exists yet — that is produced
   * on the worker thread). {@code queuedEventPublicId} is the {@code runner.queued} audit anchor.
   */
  record Queued(RunnerExecutionHandle handle, String queuedEventPublicId)
      implements RunnerDispatchResult {

    public Queued {
      Objects.requireNonNull(handle, "handle");
    }
  }
}
