package org.dradgo.domain.registry;

import java.util.Map;

/**
 * Story 3e-4 (AC2) — the per-project runner-mapping step value set. A project may bind a {@link
 * RunnerKind} to each of these workflow steps independently (the {@code project_runner_kinds} child
 * table; the {@code Project.stepRunnerKinds} map), resolving more specifically than the single
 * per-project {@code runnerKind} override (3d-3) or the global per-stage default.
 *
 * <p>The wire values intentionally match the {@code ArtifactType} producer-output names ({@code
 * spec}/{@code implementationPlan}/{@code prOutput}) — the step IS the artifact the runner produces
 * — but this is a distinct registry: the {@code (RunnerStage, ExecutionSubStage) →
 * ProjectRunnerStep} derivation lives in the application layer ({@code ProjectRunnerSteps}) because
 * {@code ExecutionSubStage} is an application type the domain may not import. REVIEW has NO step
 * here — the reviewer kind is governed by {@code reviewer_model_kind} (3d-1/3d-2), not this map.
 */
public enum ProjectRunnerStep implements RegistryValue {
  SPEC("spec"),
  IMPLEMENTATION_PLAN("implementationPlan"),
  PR_OUTPUT("prOutput");

  private static final Map<String, ProjectRunnerStep> LOOKUP = RegistryParsers.index(values());

  private final String value;

  ProjectRunnerStep(String value) {
    this.value = value;
  }

  @Override
  public String value() {
    return value;
  }

  static ProjectRunnerStep fromValue(String rawValue) {
    return fromValue(rawValue, null);
  }

  public static ProjectRunnerStep fromValue(String rawValue, String field) {
    return RegistryParsers.parse("ProjectRunnerStep", rawValue, field, LOOKUP);
  }
}
