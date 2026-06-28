package org.dradgo.application.workflow;

import java.util.List;

/**
 * Assembled read-model view of a run's position in the run-dependency DAG (story 3f-3): the runs it
 * depends on ({@code prerequisites}), the runs that depend on it ({@code dependents}), the subset
 * of prerequisites that are not yet {@code Completed} ({@code blockedOn}), and a convenience
 * boolean. {@code blockedByDependencies} is {@code true} whenever {@code blockedOn} is non-empty —
 * i.e. at least one prerequisite has not finished. Lives in {@code application.workflow} (a
 * read-view, not a persistence port type) so the REST layer may legally map it.
 */
public record RunDependencyGraphView(
    List<BlockedDependencyView> prerequisites,
    List<BlockedDependencyView> dependents,
    List<BlockedDependencyView> blockedOn,
    boolean blockedByDependencies) {

  public static RunDependencyGraphView empty() {
    return new RunDependencyGraphView(List.of(), List.of(), List.of(), false);
  }
}
