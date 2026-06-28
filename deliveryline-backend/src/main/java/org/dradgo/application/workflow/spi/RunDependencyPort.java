package org.dradgo.application.workflow.spi;

import java.util.List;
import org.dradgo.application.workflow.BlockedDependencyView;
import org.dradgo.application.workflow.RunDependencyGraphView;
import org.dradgo.domain.registry.WorkflowState;

/**
 * Persistence-facing port for the run-dependency DAG (story 3f-3, FR71). Implementations only store
 * and query edges in the {@code run_dependencies} join table; all declaration rules, cycle policy,
 * and state transitions live in {@code RunDependencyService} / {@code RunDependencyReleaseService}.
 */
public interface RunDependencyPort {

  /**
   * Idempotently record that {@code runId} depends on each id in {@code dependsOnRunIds}. An edge
   * that already exists (same {@code (run_id, depends_on_run_id)} primary key) is left untouched —
   * implementations use {@code on conflict do nothing} so a declaration replay does not throw.
   */
  void addDependencies(String runId, List<String> dependsOnRunIds);

  /** All prerequisites of {@code runId} (the runs it depends on), each with its current state. */
  List<BlockedDependencyView> findPrerequisites(String runId);

  /** All dependents of {@code runId} (the runs that depend on it), each with its current state. */
  List<BlockedDependencyView> findDependents(String runId);

  /**
   * The subset of {@code runId}'s prerequisites whose current state is not {@code Completed} — the
   * runs still blocking it. Empty when every prerequisite has completed (or there are none).
   */
  List<BlockedDependencyView> findBlockedOn(String runId);

  /**
   * {@code true} when every prerequisite of {@code runId} is {@code Completed}. Vacuously {@code
   * true} when {@code runId} has zero prerequisites.
   */
  boolean allPrerequisitesCompleted(String runId);

  /**
   * {@code true} when adding the edge {@code runId -> dependsOnRunId} would introduce a cycle in
   * the DAG — i.e. {@code dependsOnRunId} already (transitively) depends on {@code runId}.
   * Implemented with a recursive reachability query so the full graph is never loaded into memory.
   */
  boolean wouldCreateCycle(String runId, String dependsOnRunId);

  /**
   * Acquire a transaction-scoped advisory lock serializing all run-dependency graph mutations
   * (declaration) against the release resolver (story 3f-3 review D1/D2). Without it, the
   * check-then-insert cycle guard is a TOCTOU race (two opposite-direction declarations can each
   * pass the probe and persist a real cycle) and a prerequisite completing in the window between a
   * declaration's state read and its edge commit can strand the dependent. Callers must already be
   * inside a transaction; the lock releases automatically on commit/rollback.
   */
  void lockDependencyGraph();

  /**
   * Assemble the read-model view for {@code runId}: its prerequisites, dependents, the unfinished
   * subset blocking it, and whether it is blocked. A single assembly site shared by the workflow
   * detail read-model and the {@code GET .../dependencies} endpoint.
   */
  default RunDependencyGraphView graphView(String runId) {
    List<BlockedDependencyView> prerequisites = findPrerequisites(runId);
    List<BlockedDependencyView> dependents = findDependents(runId);
    // Derive the blocking subset from the prerequisites already fetched rather than issuing a third
    // round trip (review 3f-3 P5): blockedOn is exactly the prerequisites that are not yet
    // Completed. Two queries per detail read instead of three; identical result.
    List<BlockedDependencyView> blockedOn =
        prerequisites.stream().filter(p -> p.state() != WorkflowState.COMPLETED).toList();
    return new RunDependencyGraphView(prerequisites, dependents, blockedOn, !blockedOn.isEmpty());
  }
}
