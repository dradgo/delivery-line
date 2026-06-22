package org.dradgo.application.runner;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.RunnerExecutionStatus;

/**
 * Pure-Java state-machine guard for {@code runner_executions.status}. Used by the persistence
 * adapter to reject illegal transitions before they hit the DB, mirroring the artifact
 * state-machine guard pattern (1.12 patches C4 / R4).
 *
 * <p>Allowed transitions:
 *
 * <pre>
 *   pending  → running | completed | failed | timed_out | orphaned | cancelled_for_takeover
 *   queued   → running | cancelled_for_takeover
 *   running  → completed | failed | timed_out | orphaned | cancelled_for_takeover
 *   timed_out|orphaned → failed (late-result reclassification)
 *   completed|failed|cancelled_for_takeover → (no further transitions)
 * </pre>
 *
 * <p>Story 3.22 (AC5 / Trap T4): {@code queued} was previously ABSENT from the {@code ALLOWED} map
 * (dequeue flips queued→running via native {@code FOR UPDATE SKIP LOCKED} SQL that bypasses this
 * guard). Developer-takeover flips queued rows through the guarded {@code markCancelledForTakeover}
 * adapter path, so {@code queued} now has an explicit rule.
 *
 * <p>Reuses {@link DomainErrorCode#ILLEGAL_TRANSITION} — the registry already covers this shape (no
 * new code is needed; the rejection details disambiguate the runner vs workflow caller).
 */
public final class RunnerExecutionStateMachine {

  private static final Map<RunnerExecutionStatus, Set<RunnerExecutionStatus>> ALLOWED;
  private static final Set<RunnerExecutionStatus> TERMINAL =
      EnumSet.of(
          RunnerExecutionStatus.COMPLETED,
          RunnerExecutionStatus.FAILED,
          RunnerExecutionStatus.TIMED_OUT,
          RunnerExecutionStatus.ORPHANED,
          RunnerExecutionStatus.CANCELLED_FOR_TAKEOVER);

  static {
    Map<RunnerExecutionStatus, Set<RunnerExecutionStatus>> rules =
        new EnumMap<>(RunnerExecutionStatus.class);
    rules.put(
        RunnerExecutionStatus.PENDING,
        EnumSet.of(
            RunnerExecutionStatus.RUNNING,
            RunnerExecutionStatus.COMPLETED,
            RunnerExecutionStatus.FAILED,
            RunnerExecutionStatus.TIMED_OUT,
            RunnerExecutionStatus.ORPHANED,
            RunnerExecutionStatus.CANCELLED_FOR_TAKEOVER));
    // Story 3.22 (Trap T4): queued was absent — add the rule so the guarded
    // markCancelledForTakeover
    // path is legal. The synchronous dequeue (native FOR UPDATE SKIP LOCKED) still flips
    // queued→running outside this guard; this rule covers the takeover (and future guarded) edges.
    rules.put(
        RunnerExecutionStatus.QUEUED,
        EnumSet.of(RunnerExecutionStatus.RUNNING, RunnerExecutionStatus.CANCELLED_FOR_TAKEOVER));
    rules.put(
        RunnerExecutionStatus.RUNNING,
        EnumSet.of(
            RunnerExecutionStatus.COMPLETED,
            RunnerExecutionStatus.FAILED,
            RunnerExecutionStatus.TIMED_OUT,
            RunnerExecutionStatus.ORPHANED,
            RunnerExecutionStatus.CANCELLED_FOR_TAKEOVER));
    rules.put(
        RunnerExecutionStatus.AWAITING_MANUAL,
        EnumSet.of(RunnerExecutionStatus.CANCELLED_FOR_TAKEOVER));
    rules.put(RunnerExecutionStatus.COMPLETED, EnumSet.noneOf(RunnerExecutionStatus.class));
    rules.put(RunnerExecutionStatus.FAILED, EnumSet.noneOf(RunnerExecutionStatus.class));
    rules.put(RunnerExecutionStatus.TIMED_OUT, EnumSet.of(RunnerExecutionStatus.FAILED));
    rules.put(RunnerExecutionStatus.ORPHANED, EnumSet.of(RunnerExecutionStatus.FAILED));
    rules.put(
        RunnerExecutionStatus.CANCELLED_FOR_TAKEOVER, EnumSet.noneOf(RunnerExecutionStatus.class));
    ALLOWED = Map.copyOf(rules);
  }

  private RunnerExecutionStateMachine() {}

  public static boolean isTerminal(RunnerExecutionStatus status) {
    Objects.requireNonNull(status, "status");
    return TERMINAL.contains(status);
  }

  public static void assertCanTransition(
      String runnerExecutionId, RunnerExecutionStatus from, RunnerExecutionStatus to) {
    Objects.requireNonNull(runnerExecutionId, "runnerExecutionId");
    Objects.requireNonNull(from, "from");
    Objects.requireNonNull(to, "to");
    Set<RunnerExecutionStatus> targets = ALLOWED.getOrDefault(from, Set.of());
    if (!targets.contains(to)) {
      Map<String, Object> details = new LinkedHashMap<>();
      details.put("runnerExecutionId", runnerExecutionId);
      details.put("fromStatus", from.value());
      details.put("toStatus", to.value());
      details.put("reason", from == to ? "no_op_transition" : "transition_not_allowed");
      throw new DomainException(
          DomainErrorCode.ILLEGAL_TRANSITION,
          "Illegal runner-execution transition "
              + from.value()
              + " → "
              + to.value()
              + " for "
              + runnerExecutionId,
          details);
    }
  }
}
