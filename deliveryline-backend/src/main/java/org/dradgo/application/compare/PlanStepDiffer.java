package org.dradgo.application.compare;

import java.util.List;

/**
 * Story 4.19 (AC4, Reconciliation 6) — computes implementation-plan step deltas over the two
 * artifacts' ordered step arrays. Pure interface (no Spring / persistence deps) so it unit-tests
 * independently (AC9). The input lists are the redacted step texts parsed from each payload's
 * {@code steps} array; the output blocks are further redacted defense-in-depth by {@link
 * RevisionDeltaService} before serialization (AC6).
 */
public interface PlanStepDiffer {

  /**
   * LCS-aligns the two step lists and emits {@code added} / {@code removed} / {@code reordered} /
   * {@code modified} blocks. {@code stepId} and the order fields are the 0-based array indices
   * (there is no persisted step id — Reconciliation 6).
   *
   * @param priorSteps revision A steps in order; {@code null} treated as empty.
   * @param currentSteps revision B steps in order; {@code null} treated as empty.
   * @return the ordered change blocks; empty when the two lists are identical. Never {@code null}.
   */
  List<PlanStepChangeBlock> diff(List<String> priorSteps, List<String> currentSteps);
}
