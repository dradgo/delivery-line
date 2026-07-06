package org.dradgo.application.project;

import java.util.Optional;
import org.dradgo.application.runner.ExecutionSubStage;
import org.dradgo.domain.registry.ProjectRunnerStep;
import org.dradgo.domain.registry.RunnerStage;

/**
 * Story 3e-4 (AC2) — the {@code (RunnerStage, ExecutionSubStage) → ProjectRunnerStep} derivation
 * the per-step runner mapping resolves against. This lives in the application layer (NOT on the
 * {@code ProjectRunnerStep} enum) because {@link ExecutionSubStage} is an {@code
 * application.runner} type the {@code domain.registry} package may not import (the
 * layered-architecture rule).
 *
 * <ul>
 *   <li>{@code INVESTIGATION} → {@link ProjectRunnerStep#SPEC}
 *   <li>{@code EXECUTION} + {@code IMPLEMENTATION_PLAN} → {@link
 *       ProjectRunnerStep#IMPLEMENTATION_PLAN}
 *   <li>{@code EXECUTION} + {@code PR_OUTPUT} → {@link ProjectRunnerStep#PR_OUTPUT}
 *   <li>{@code EXECUTION} with no sub-stage (the legacy generic composition / recovery retry) →
 *       none
 *   <li>{@code REVIEW} (and any other stage) → none — the reviewer kind is governed by {@code
 *       reviewer_model_kind} (3d-1/3d-2), never this map.
 * </ul>
 *
 * <p>A {@code none} result means the per-step layer is skipped and resolution falls through to the
 * single per-project {@code runnerKind} override, then the global per-stage default.
 */
public final class ProjectRunnerSteps {

  private ProjectRunnerSteps() {}

  public static Optional<ProjectRunnerStep> of(RunnerStage stage, ExecutionSubStage subStage) {
    if (stage == null) {
      return Optional.empty();
    }
    return switch (stage) {
      case INVESTIGATION -> Optional.of(ProjectRunnerStep.SPEC);
      case EXECUTION -> {
        if (subStage == null) {
          yield Optional.empty();
        }
        yield switch (subStage) {
          case IMPLEMENTATION_PLAN -> Optional.of(ProjectRunnerStep.IMPLEMENTATION_PLAN);
          case PR_OUTPUT -> Optional.of(ProjectRunnerStep.PR_OUTPUT);
        };
      }
      case REVIEW -> Optional.empty();
      // Story 3h-1 (AC1) — BUILD runs backend-side (BuildCommandPort), not via a per-step runner
      // kind, so there is no per-step override to resolve; fall through to none.
      case BUILD -> Optional.empty();
      // Story 3h-2 (AC1) — LINT runs backend-side (BuildCommandPort), not via a per-step runner
      // kind, so there is no per-step override to resolve; fall through to none.
      case LINT -> Optional.empty();
    };
  }
}
