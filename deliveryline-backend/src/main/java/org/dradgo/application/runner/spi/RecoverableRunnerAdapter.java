package org.dradgo.application.runner.spi;

import java.time.Duration;
import java.util.Optional;
import org.dradgo.application.runner.RunnerPollStatus;

/**
 * Story 3.2 AC4 (OQ-6): adapter sub-interface that exposes a side-effect-free recover-handle probe
 * used by {@code RunnerBroker.recoverOnStartup} after a JVM restart.
 *
 * <p>Implementations that can recover via external state (e.g., the Docker engine's container
 * inventory) return a typed {@link RunnerPollStatus} that the broker uses to either resume polling,
 * harvest a result, or fail-classify the row. Implementations with no external recovery surface
 * (e.g., the in-memory mock adapter) return {@link Optional#empty()} so the broker falls through to
 * the existing scratch-replay / orphan branches unchanged.
 *
 * <p>Keeps the broker free of {@code instanceof DockerRunnerAdapter} checks (Trap T5 / OQ-6).
 */
public interface RecoverableRunnerAdapter extends RunnerAdapter {

  /**
   * Probes external state for the given {@code runnerExecutionId}. Returns a poll status when the
   * adapter could rebuild the local handle (e.g., container id from a Docker label filter) AND
   * classify the current container state, or {@link Optional#empty()} when no external state was
   * recovered. Must NOT throw on a missing or unreachable backing service; convert to {@link
   * Optional#empty()} and let the broker fall through.
   */
  Optional<RunnerPollStatus> recoverHandle(String runnerExecutionId);

  /**
   * Story 3.2 AC1: forcefully terminate the runner identified by {@code runnerExecutionId}. The
   * docker adapter issues {@code docker stop} with the supplied grace, then escalates to {@code
   * docker kill} if the container is still running after the stop returns. Implementations that
   * have no external process to terminate (the in-memory mock) treat this as a {@code cancel}.
   *
   * <p>Must NOT throw — best-effort, idempotent. Returns {@link TerminationOutcome} so the broker
   * can audit which escalation path was taken (used by the runner.timeout event details).
   */
  TerminationOutcome terminate(String runnerExecutionId, Duration graceful);

  /**
   * Story 3.2 AC8: exposes the adapter-side container id (when present) so the broker can attach it
   * to {@code runner.dispatched} / {@code runner.timeout} event details. Implementations without an
   * external container handle return {@link Optional#empty()}.
   */
  default Optional<String> findContainerIdFor(String runnerExecutionId) {
    return Optional.empty();
  }

  /**
   * Story 3.2 AC8 (OQ-4): when {@code true}, the broker SKIPS the legacy {@code runner.started}
   * event AND emits {@code runner.dispatched} after the adapter's dispatch ack returns. The docker
   * adapter sets this to {@code true} so the audit trail switches to the dedicated docker lifecycle
   * event family. The in-memory mock keeps the default {@code false} so its existing {@code
   * runner.started} event continues to flow (mock-path backward compatibility).
   */
  default boolean emitsDispatchedAfterAck() {
    return false;
  }

  /**
   * Outcome of a {@link #terminate(String, Duration)} call. Drives the audit details for downstream
   * lifecycle events.
   */
  enum TerminationOutcome {
    /** No container/process was tracked for the id — no-op. */
    UNKNOWN,
    /** Graceful stop succeeded; the runner exited within the grace window. */
    STOPPED_GRACEFULLY,
    /** Graceful stop returned, but inspection showed the container still running — kill issued. */
    KILLED_AFTER_GRACE,
    /** Best-effort termination failed (engine error). Audit but do not throw. */
    BEST_EFFORT_FAILURE
  }
}
