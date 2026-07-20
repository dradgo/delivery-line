package org.dradgo.application.project;

import static org.assertj.core.api.Assertions.assertThat;

import org.dradgo.application.runner.ExecutionSubStage;
import org.dradgo.domain.registry.ProjectRunnerStep;
import org.dradgo.domain.registry.RunnerStage;
import org.junit.jupiter.api.Test;

/**
 * Story 3e-4 (AC2) — the {@code (RunnerStage, ExecutionSubStage) → ProjectRunnerStep} derivation,
 * covering every stage/sub-stage combination including the no-step cases (REVIEW, sub-stage-less
 * EXECUTION, null stage) that must skip the per-step layer entirely.
 */
class ProjectRunnerStepsTest {

  @Test
  void investigationMapsToSpec() {
    assertThat(ProjectRunnerSteps.of(RunnerStage.INVESTIGATION, null))
        .contains(ProjectRunnerStep.SPEC);
    // A spurious sub-stage on INVESTIGATION is ignored — the stage alone decides spec.
    assertThat(ProjectRunnerSteps.of(RunnerStage.INVESTIGATION, ExecutionSubStage.PR_OUTPUT))
        .contains(ProjectRunnerStep.SPEC);
  }

  @Test
  void executionSubStagesMapToPlanAndPrOutput() {
    assertThat(ProjectRunnerSteps.of(RunnerStage.EXECUTION, ExecutionSubStage.IMPLEMENTATION_PLAN))
        .contains(ProjectRunnerStep.IMPLEMENTATION_PLAN);
    assertThat(ProjectRunnerSteps.of(RunnerStage.EXECUTION, ExecutionSubStage.PR_OUTPUT))
        .contains(ProjectRunnerStep.PR_OUTPUT);
  }

  @Test
  void executionWithoutSubStageHasNoStep() {
    // The legacy generic composition / recovery retry path carries no sub-stage → no per-step
    // layer.
    assertThat(ProjectRunnerSteps.of(RunnerStage.EXECUTION, null)).isEmpty();
  }

  @Test
  void reviewAndNullStageHaveNoStep() {
    // REVIEW is governed by reviewer_model_kind, not this map.
    assertThat(ProjectRunnerSteps.of(RunnerStage.REVIEW, null)).isEmpty();
    assertThat(ProjectRunnerSteps.of(RunnerStage.REVIEW, ExecutionSubStage.PR_OUTPUT)).isEmpty();
    assertThat(ProjectRunnerSteps.of(null, null)).isEmpty();
  }

  @Test
  void buildStageHasNoStep() {
    // Story 3h-1 — BUILD runs backend-side (BuildCommandPort), never via a per-step runner kind,
    // so it has no per-step override to resolve (a spurious sub-stage is ignored).
    assertThat(ProjectRunnerSteps.of(RunnerStage.BUILD, null)).isEmpty();
    assertThat(ProjectRunnerSteps.of(RunnerStage.BUILD, ExecutionSubStage.PR_OUTPUT)).isEmpty();
  }

  @Test
  void lintStageHasNoStep() {
    // Story 3h-2 — LINT runs backend-side (BuildCommandPort), never via a per-step runner kind,
    // so it has no per-step override to resolve (a spurious sub-stage is ignored).
    assertThat(ProjectRunnerSteps.of(RunnerStage.LINT, null)).isEmpty();
    assertThat(ProjectRunnerSteps.of(RunnerStage.LINT, ExecutionSubStage.PR_OUTPUT)).isEmpty();
  }
}
