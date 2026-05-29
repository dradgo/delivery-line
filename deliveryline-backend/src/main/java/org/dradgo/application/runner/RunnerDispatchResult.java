package org.dradgo.application.runner;

import java.util.Objects;

public sealed interface RunnerDispatchResult {

  RunnerExecutionHandle handle();

  default boolean isReplay() {
    return this instanceof Replayed;
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
}
