package org.dradgo.adapters.runner;

import java.util.Objects;
import org.dradgo.domain.registry.FailureCategory;
import org.dradgo.domain.registry.RunnerStage;

/**
 * Scripted behaviour for {@code MockRunnerAdapter}. Each scenario binds a deterministic behaviour
 * kind, the canonical fixture filename under {@code src/main/resources/runner-scenarios/}, and
 * (where applicable) the expected {@link FailureCategory} the broker should emit.
 *
 * <p>Loading + behaviour are decoupled: the scenario shape is a pure value object so unit tests can
 * supply ad-hoc scenarios without touching the classpath.
 */
public record MockRunnerScenario(
    String name,
    RunnerStage stage,
    Behaviour behaviour,
    String fixtureResource,
    FailureCategory expectedFailureCategory) {

  public MockRunnerScenario {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(behaviour, "behaviour");
    // stage may be null when scenario applies to all stages (e.g. malformed-output).
  }

  public enum Behaviour {
    HAPPY,
    TIMEOUT,
    CRASH,
    CONTRACT_VIOLATION,
    NON_ZERO_EXIT,
    LATE_RESULT,
    DUPLICATE_RESULT,
    MALFORMED_OUTPUT
  }
}
