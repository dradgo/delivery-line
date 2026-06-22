package org.dradgo.domain.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.dradgo.domain.DomainException;
import org.junit.jupiter.api.Test;

/**
 * Story 3d-2 (Task 1, AC1/AC2) — the new code-only {@link RunnerStage#REVIEW} value round-trips
 * through the registry parser exactly like the pre-existing stages. RunnerStage is intentionally
 * NOT in RegistryContractTest/FlywaySchemaContractTest ({@code runner_executions.stage} is an
 * un-CHECKed text column), so this focused parse test is the registry-level coverage for REVIEW.
 */
class RunnerStageReviewParsingTest {

  @Test
  void reviewExposesItsCanonicalWireValue() {
    assertEquals("review", RunnerStage.REVIEW.value());
  }

  @Test
  void reviewRoundTripsThroughFromValue() {
    assertEquals(RunnerStage.REVIEW, RunnerStage.fromValue("review"));
  }

  @Test
  void existingStagesStillParse() {
    assertEquals(RunnerStage.INVESTIGATION, RunnerStage.fromValue("investigation"));
    assertEquals(RunnerStage.EXECUTION, RunnerStage.fromValue("execution"));
  }

  @Test
  void unknownStageStillFailsFast() {
    DomainException unknown =
        assertThrows(DomainException.class, () -> RunnerStage.fromValue("__bogus__"));
    assertEquals(DomainErrorCode.UNKNOWN_REGISTRY_VALUE, unknown.errorCode());
  }
}
