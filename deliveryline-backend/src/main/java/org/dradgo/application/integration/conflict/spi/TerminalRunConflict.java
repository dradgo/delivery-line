package org.dradgo.application.integration.conflict.spi;

/**
 * Story 4.30 (Reconciliation 4) — a lean projection of one UNRESOLVED {@code integration_conflicts}
 * row whose owning {@code workflow_runs} row is in a TERMINAL state ({@code Completed}/{@code
 * TakenOver}/{@code Reconciled}). Read by {@code
 * IntegrationConflictTerminalRunReconciliationSweepService} to self-heal conflicts that were
 * stranded on a terminalized run — either created before this story shipped or in the
 * sub-millisecond TOCTOU window the detector's terminal-run guard cannot cover.
 *
 * <p>Carries only the three fields the sweep needs: {@code conflictId} to route the resolve, {@code
 * workflowRunId} to take the per-run reconcile advisory lock, and {@code currentState} for the
 * per-item WARN audit line (ids/states only — no snapshots, no external payloads).
 */
public record TerminalRunConflict(String conflictId, String workflowRunId, String currentState) {}
