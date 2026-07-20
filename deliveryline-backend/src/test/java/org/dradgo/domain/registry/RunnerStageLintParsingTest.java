package org.dradgo.domain.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.dradgo.domain.DomainException;
import org.junit.jupiter.api.Test;

/**
 * Story 3h-2 (Task 1, AC1, FR76) — the new code-only {@link RunnerStage#LINT} value round-trips
 * through the registry parser exactly like the pre-existing stages. RunnerStage is intentionally
 * NOT in RegistryContractTest/FlywaySchemaContractTest ({@code runner_executions.stage} is an
 * un-CHECKed text column; both contract tests auto-derive the stage set from {@code values()}), so
 * this focused parse test is the registry-level coverage for LINT — mirroring {@link
 * RunnerStageBuildParsingTest}.
 */
class RunnerStageLintParsingTest {

  @Test
  void lintExposesItsCanonicalWireValue() {
    assertEquals("lint", RunnerStage.LINT.value());
  }

  @Test
  void lintRoundTripsThroughFromValue() {
    assertEquals(RunnerStage.LINT, RunnerStage.fromValue("lint"));
  }

  @Test
  void existingStagesStillParse() {
    assertEquals(RunnerStage.INVESTIGATION, RunnerStage.fromValue("investigation"));
    assertEquals(RunnerStage.EXECUTION, RunnerStage.fromValue("execution"));
    assertEquals(RunnerStage.BUILD, RunnerStage.fromValue("build"));
    assertEquals(RunnerStage.REVIEW, RunnerStage.fromValue("review"));
  }

  @Test
  void unknownStageStillFailsFast() {
    DomainException unknown =
        assertThrows(DomainException.class, () -> RunnerStage.fromValue("__bogus__"));
    assertEquals(DomainErrorCode.UNKNOWN_REGISTRY_VALUE, unknown.errorCode());
  }
}
