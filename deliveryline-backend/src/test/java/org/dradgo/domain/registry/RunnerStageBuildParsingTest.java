package org.dradgo.domain.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.dradgo.domain.DomainException;
import org.junit.jupiter.api.Test;

/**
 * Story 3h-1 (Task 1, AC1, FR75) — the new code-only {@link RunnerStage#BUILD} value round-trips
 * through the registry parser exactly like the pre-existing stages. RunnerStage is intentionally
 * NOT in RegistryContractTest/FlywaySchemaContractTest ({@code runner_executions.stage} is an
 * un-CHECKed text column; both contract tests auto-derive the stage set from {@code values()}), so
 * this focused parse test is the registry-level coverage for BUILD — mirroring {@link
 * RunnerStageReviewParsingTest}.
 */
class RunnerStageBuildParsingTest {

  @Test
  void buildExposesItsCanonicalWireValue() {
    assertEquals("build", RunnerStage.BUILD.value());
  }

  @Test
  void buildRoundTripsThroughFromValue() {
    assertEquals(RunnerStage.BUILD, RunnerStage.fromValue("build"));
  }

  @Test
  void existingStagesStillParse() {
    assertEquals(RunnerStage.INVESTIGATION, RunnerStage.fromValue("investigation"));
    assertEquals(RunnerStage.EXECUTION, RunnerStage.fromValue("execution"));
    assertEquals(RunnerStage.REVIEW, RunnerStage.fromValue("review"));
  }

  @Test
  void unknownStageStillFailsFast() {
    DomainException unknown =
        assertThrows(DomainException.class, () -> RunnerStage.fromValue("__bogus__"));
    assertEquals(DomainErrorCode.UNKNOWN_REGISTRY_VALUE, unknown.errorCode());
  }
}
