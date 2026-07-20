package org.dradgo.application.recovery;

/**
 * A single recovery action recommended for a failed workflow run, ranked by operator safety (story
 * 4.4 AC2).
 *
 * <p>Produced by {@link RecommendationService#recommend} as a pure function of the run's current
 * state, failure category, and integration drift — it carries NO persistence identity and is never
 * written to a table (contrast {@link org.dradgo.application.recovery.spi.RecoveryActionSnapshot}).
 * {@link org.dradgo.application.workflow.WorkflowInspectionService#getFailureDiagnostics} maps it
 * into a nested {@code RecommendedActionView} for the CLI/REST adapters (adapters never import
 * {@code application.recovery}).
 *
 * @param actionType the recovery verb — one of the {@code recovery_actions} CHECK vocabulary {@code
 *     retry|rerun|resume|takeover|pause|reconcile}. Only {@code retry} has a wired one-click
 *     invocation today (story 4.4 Reconciliation 10); the rest are ranked advisory guidance.
 * @param safetyLevel {@code safe} | {@code caution} | {@code risky} — the operator-facing safety
 *     classification driving the CLI colour coding and the FE ranking
 * @param reason human-readable explanation of why the action is (or is not) advisable
 * @param precondition human-readable precondition the operator should confirm before invoking (e.g.
 *     {@code "workspace intact"}), never null
 */
public record RecommendedAction(
    String actionType, String safetyLevel, String reason, String precondition) {}
