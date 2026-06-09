package org.dradgo.application.runner;

/**
 * Story 3.10 (Decision D1) — the logical execution sub-stage carried into {@link
 * ContextBundleService#create}. This is a <strong>project-owned</strong> discriminator, NOT a
 * {@code org.dradgo.domain.registry.RunnerStage} value: the wire enum stays {@code {INVESTIGATION,
 * EXECUTION}} and both implementation sub-stages map to {@code RunnerStage.EXECUTION}. The
 * implementation-plan-vs-pr-output distinction is therefore semantic and lives in the composition
 * method, exactly as {@code createForSpecInvestigation} carries the spec semantics while reusing
 * {@code RunnerStage.INVESTIGATION}.
 *
 * <p>A {@code null} sub-stage selects the legacy generic composition ({@code create(...)}'s
 * latest-available union) so the {@code RecoveryService} retry path and all pre-3.10 tests stay
 * byte-identical.
 */
public enum ExecutionSubStage {
  /** Plan runner: approved spec + PM decisions + incorporated clarifications; no approved plan. */
  IMPLEMENTATION_PLAN,
  /** PR-output runner: additionally carries the approved plan + plan-rejection feedback. */
  PR_OUTPUT
}
