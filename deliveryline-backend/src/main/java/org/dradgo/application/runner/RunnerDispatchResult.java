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
   * Story 3d-3 (AC3/AC6) — {@code true} when the run was PARKED for manual execution (the resolved
   * runner kind is {@code manual}): the dispatch chokepoint composed + wrote the input bundle and
   * transitioned the run to {@code WaitingForManualExecution} instead of enqueuing a container. No
   * queue row, no worker slot, no adapter dispatch.
   */
  default boolean isParked() {
    return this instanceof Parked;
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

  /**
   * Story 3d-3 (AC3/AC6) — the step was PARKED for manual execution: the chokepoint composed +
   * wrote the input bundle ({@code RunnerScratchStore}) and transitioned the run to {@code
   * WaitingForManualExecution}. The {@code handle} carries the minted {@code rex_…} id in status
   * {@code awaiting_manual} (no container/lease — there is no adapter dispatch). {@code
   * manualEventPublicId} is the {@code manual.executionRequested} audit anchor.
   */
  record Parked(RunnerExecutionHandle handle, String manualEventPublicId)
      implements RunnerDispatchResult {

    public Parked {
      Objects.requireNonNull(handle, "handle");
      Objects.requireNonNull(manualEventPublicId, "manualEventPublicId");
    }
  }
}
