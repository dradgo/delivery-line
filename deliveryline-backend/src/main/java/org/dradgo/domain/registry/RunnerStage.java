package org.dradgo.domain.registry;

import java.util.Map;

public enum RunnerStage implements RegistryValue {
  INVESTIGATION("investigation"),
  EXECUTION("execution"),
  // Story 3h-1 (AC1, FR75) — the build-validation gate rides the existing runner stack as its own
  // stage. Like REVIEW this is a CODE-ONLY enum value: {@code runner_executions.stage} is an
  // un-CHECKed text column, so BUILD needs NO Flyway migration and is NOT a RegistryContractTest /
  // FlywaySchemaContractTest entry (both auto-derive the stage set from {@code values()}). The cost
  // is the same exhaustive {@code switch (stage)} fan-out — every consumer adds an explicit BUILD
  // arm (no silent {@code default}) or a build execution mis-routes. Per the ADR-0030 amendment a
  // BUILD execution runs BACKEND-SIDE (ProcessBuilder in the materialized host workspace, behind
  // {@code BuildCommandPort}), never through the Docker runner — so it emits NO artifacts-table
  // artifact and records ZERO token/provider usage. It reuses the story-3.6 raw-output capture and
  // the 3d-5 per-step step/log view (zero new persistence for the execution record).
  BUILD("build"),
  // Story 3d-2 (AC1/AC2, ADR 0026, DD-1) — the advisory reviewer rides the existing runner stack
  // as its own stage. This is a CODE-ONLY enum: {@code runner_executions.stage} is an un-CHECKed
  // text column, so REVIEW needs NO Flyway migration and is NOT a RegistryContractTest /
  // FlywaySchemaContractTest entry. The cost is the exhaustive {@code switch (stage)} fan-out —
  // every consumer adds an explicit REVIEW arm (no silent {@code default}) or a review run
  // mis-routes. A REVIEW execution reviews an existing WaitingForReview output artifact and
  // harvests its verdict into {@code step_reviews}; it emits NO artifacts-table artifact.
  REVIEW("review");

  private static final Map<String, RunnerStage> LOOKUP = RegistryParsers.index(values());

  private final String value;

  RunnerStage(String value) {
    this.value = value;
  }

  @Override
  public String value() {
    return value;
  }

  static RunnerStage fromValue(String rawValue) {
    return fromValue(rawValue, null);
  }

  public static RunnerStage fromValue(String rawValue, String field) {
    return RegistryParsers.parse("RunnerStage", rawValue, field, LOOKUP);
  }
}
