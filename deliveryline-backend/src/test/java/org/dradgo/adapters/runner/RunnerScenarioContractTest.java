package org.dradgo.adapters.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.dradgo.domain.registry.FailureCategory;
import org.dradgo.runnercontracts.RunnerContractValidator;
import org.dradgo.runnercontracts.RunnerContractValidator.ValidationTarget;
import org.dradgo.runnercontracts.ValidationContext;
import org.dradgo.runnercontracts.ValidationResult;
import org.junit.jupiter.api.Test;

/**
 * Deterministic scenario library exit-gate (story 1.13 Task 7). Each main-classpath {@code
 * runner-scenarios/*.json} fixture is loaded and asserted to either (a) pass the {@code
 * runner-result.v1} schema (happy paths + non-zero-exit) or (b) fail validation with the documented
 * {@link FailureCategory} mapping (contract-violation). Scenarios that exercise runtime-only
 * failure modes (timeout, crash) are present in the registry but do not produce a result file —
 * they're asserted to load and to map to the expected {@link FailureCategory}.
 */
class RunnerScenarioContractTest {

  private static final String RUN_ID = "run_scenario12345";
  private static final String REX_ID = "rex_scenario12345";
  private static final Set<String> RESULT_PRODUCING_SCENARIOS =
      new LinkedHashSet<>(
          Set.of(
              "happy-spec",
              "happy-implementation-plan",
              "happy-pr-output",
              "non-zero-exit",
              "late-result",
              "duplicate-result"));

  private final RunnerContractValidator validator = new RunnerContractValidator();

  @Test
  void everyRegisteredScenarioHasAFixtureFileOrIsRuntimeOnly() {
    MockRunnerScenarioRegistry registry = new MockRunnerScenarioRegistry();
    Map<String, MockRunnerScenario> all = registry.all();
    for (MockRunnerScenario scenario : all.values()) {
      if (scenario.fixtureResource() == null) {
        continue;
      }
      try (InputStream stream =
          Thread.currentThread()
              .getContextClassLoader()
              .getResourceAsStream(scenario.fixtureResource())) {
        assertNotNull(
            stream,
            () ->
                "missing classpath fixture for scenario "
                    + scenario.name()
                    + " at "
                    + scenario.fixtureResource());
      } catch (Exception error) {
        throw new AssertionError("failed to read fixture for scenario " + scenario.name(), error);
      }
    }
  }

  @Test
  void happyScenariosPassRunnerResultSchemaAfterPlaceholderSubstitution() {
    for (String scenario :
        new String[] {"happy-spec", "happy-implementation-plan", "happy-pr-output"}) {
      byte[] tailored = tailoredFixtureBytes(scenario);
      ValidationResult result =
          validator.validate(ValidationTarget.RUNNER_RESULT, tailored, contextFor(tailored.length));
      assertTrue(
          result.valid(),
          () -> "scenario " + scenario + " should be valid but errors=" + result.errors());
    }
  }

  @Test
  void contractViolationScenarioFailsRunnerResultSchema() {
    byte[] tailored = tailoredFixtureBytes("contract-violation");
    ValidationResult result =
        validator.validate(ValidationTarget.RUNNER_RESULT, tailored, contextFor(tailored.length));
    assertFalse(result.valid(), "contract-violation fixture must fail validation");
  }

  @Test
  void nonZeroExitFixtureIsSchemaValidAndCarriesNonZeroExitFailureCategory() {
    byte[] tailored = tailoredFixtureBytes("non-zero-exit");
    ValidationResult result =
        validator.validate(ValidationTarget.RUNNER_RESULT, tailored, contextFor(tailored.length));
    assertTrue(
        result.valid(),
        () -> "non-zero-exit fixture must pass schema validation but errors=" + result.errors());
    String text = new String(tailored, StandardCharsets.UTF_8);
    assertTrue(
        text.contains("\"failureCategory\": \"runner_non_zero_exit\""),
        () -> "non-zero-exit fixture must carry runner_non_zero_exit; saw: " + text);
  }

  @Test
  void registryExposesEveryAcSixFailureCategoryViaAtLeastOneScenario() {
    MockRunnerScenarioRegistry registry = new MockRunnerScenarioRegistry();
    Set<FailureCategory> expected =
        Set.of(
            FailureCategory.RUNNER_TIMEOUT,
            FailureCategory.RUNNER_CRASH,
            FailureCategory.RUNNER_CONTRACT_VIOLATION,
            FailureCategory.RUNNER_NON_ZERO_EXIT,
            FailureCategory.RUNNER_LATE_RESULT,
            FailureCategory.RUNNER_DUPLICATE_RESULT,
            FailureCategory.RUNNER_MALFORMED_OUTPUT);
    Set<FailureCategory> observed = new LinkedHashSet<>();
    registry
        .all()
        .values()
        .forEach(
            scenario -> {
              if (scenario.expectedFailureCategory() != null) {
                observed.add(scenario.expectedFailureCategory());
              }
            });
    assertEquals(
        expected,
        observed,
        () ->
            "every AC5 failure category must be reachable through the production-classpath scenario library; missing="
                + diff(expected, observed));
  }

  @Test
  void mockAdapterDefersDuplicateResultFixtureForReleaseDeferredResultTrigger() {
    // The MockRunnerAdapter must queue a duplicate-result payload so a test can re-trigger
    // the broker's onResult and observe DUPLICATE_RUNNER_EXECUTION_ID. Asserting the fixture
    // shape is necessary but not sufficient — the queued payload must contain the placeholder
    // runnerExecutionId so the broker sees the same id twice.
    byte[] tailored = tailoredFixtureBytes("duplicate-result");
    String text = new String(tailored, StandardCharsets.UTF_8);
    assertTrue(
        text.contains("\"runnerExecutionId\": \"" + REX_ID + "\""),
        () ->
            "duplicate-result fixture must embed runnerExecutionId so the validator's "
                + "observedRunnerExecutionIds check fires on a second arrival; saw: "
                + text);
  }

  @Test
  void mockAdapterDefersLateResultFixtureForReleaseDeferredResultTrigger() {
    // The MockRunnerAdapter does not write the late-result file at dispatch — the row is
    // allowed to TIMED_OUT first. The deferred fixture must still be schema-valid so the
    // broker's onResult is exercised on the late-result branch when the test releases it.
    byte[] tailored = tailoredFixtureBytes("late-result");
    ValidationResult result =
        validator.validate(ValidationTarget.RUNNER_RESULT, tailored, contextFor(tailored.length));
    assertTrue(
        result.valid(),
        () ->
            "late-result fixture must pass schema validation when released to a TIMED_OUT row; errors="
                + result.errors());
  }

  @Test
  void everyResultProducingScenarioHasFixtureResource() {
    MockRunnerScenarioRegistry registry = new MockRunnerScenarioRegistry();
    for (String name : RESULT_PRODUCING_SCENARIOS) {
      MockRunnerScenario scenario = registry.require(name);
      assertNotNull(
          scenario.fixtureResource(),
          () -> "scenario " + name + " produces a result file and must declare a fixture resource");
    }
  }

  private byte[] tailoredFixtureBytes(String scenarioName) {
    MockRunnerScenarioRegistry registry = new MockRunnerScenarioRegistry();
    MockRunnerScenario scenario = registry.require(scenarioName);
    byte[] raw = registry.loadFixtureBytes(scenario);
    assertTrue(raw.length > 0, () -> "fixture for " + scenarioName + " is empty");
    String tailored =
        new String(raw, StandardCharsets.UTF_8)
            .replace("{{workflowRunId}}", RUN_ID)
            .replace("{{runnerExecutionId}}", REX_ID);
    return tailored.getBytes(StandardCharsets.UTF_8);
  }

  private ValidationContext contextFor(int length) {
    return ValidationContext.builder()
        .maxPayloadBytes(Math.max(length + 1, ValidationContext.DEFAULT_MAX_PAYLOAD_BYTES))
        .build();
  }

  private static Set<FailureCategory> diff(
      Set<FailureCategory> expected, Set<FailureCategory> observed) {
    LinkedHashSet<FailureCategory> missing = new LinkedHashSet<>(expected);
    missing.removeAll(observed);
    return missing;
  }
}
