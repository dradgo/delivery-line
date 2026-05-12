package org.dradgo.adapters.runner;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.dradgo.domain.registry.FailureCategory;
import org.dradgo.domain.registry.RunnerStage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * In-memory registry of {@link MockRunnerScenario} entries. Production-classpath scenarios live
 * under {@code src/main/resources/runner-scenarios/}; tests may register additional scenarios via
 * {@link #register(MockRunnerScenario)} (and {@link #clearTestScenarios()} for cleanup).
 *
 * <p>Profile-activated by {@code runners.mock} so the bean only loads when the mock runner stack
 * is wired.
 */
@Component
@Profile("!runners.docker")
public class MockRunnerScenarioRegistry {

	private static final Logger log = LoggerFactory.getLogger(MockRunnerScenarioRegistry.class);

	private static final String CLASSPATH_PREFIX = "runner-scenarios/";

	private final Map<String, MockRunnerScenario> scenarios = new LinkedHashMap<>();

	public MockRunnerScenarioRegistry() {
		registerDefault("happy-spec", RunnerStage.INVESTIGATION, MockRunnerScenario.Behaviour.HAPPY, "happy-spec.json", null);
		registerDefault("happy-implementation-plan", RunnerStage.EXECUTION, MockRunnerScenario.Behaviour.HAPPY, "happy-implementation-plan.json", null);
		registerDefault("happy-pr-output", RunnerStage.EXECUTION, MockRunnerScenario.Behaviour.HAPPY, "happy-pr-output.json", null);
		registerDefault("timeout", null, MockRunnerScenario.Behaviour.TIMEOUT, null, FailureCategory.RUNNER_TIMEOUT);
		registerDefault("crash", null, MockRunnerScenario.Behaviour.CRASH, null, FailureCategory.RUNNER_CRASH);
		registerDefault("contract-violation", null, MockRunnerScenario.Behaviour.CONTRACT_VIOLATION,
			"contract-violation.json", FailureCategory.RUNNER_CONTRACT_VIOLATION);
		registerDefault("non-zero-exit", null, MockRunnerScenario.Behaviour.NON_ZERO_EXIT,
			"non-zero-exit.json", FailureCategory.RUNNER_NON_ZERO_EXIT);
		registerDefault("late-result", null, MockRunnerScenario.Behaviour.LATE_RESULT, "late-result.json",
			FailureCategory.RUNNER_LATE_RESULT);
		registerDefault("duplicate-result", null, MockRunnerScenario.Behaviour.DUPLICATE_RESULT, "duplicate-result.json",
			FailureCategory.RUNNER_DUPLICATE_RESULT);
		registerDefault("malformed-output", null, MockRunnerScenario.Behaviour.MALFORMED_OUTPUT, "malformed-output.json",
			FailureCategory.RUNNER_MALFORMED_OUTPUT);
	}

	private void registerDefault(
		String name,
		RunnerStage stage,
		MockRunnerScenario.Behaviour behaviour,
		String fixtureFilename,
		FailureCategory expected
	) {
		String resource = fixtureFilename == null ? null : CLASSPATH_PREFIX + fixtureFilename;
		scenarios.put(name, new MockRunnerScenario(name, stage, behaviour, resource, expected));
	}

	public void register(MockRunnerScenario scenario) {
		Objects.requireNonNull(scenario, "scenario");
		scenarios.put(scenario.name(), scenario);
	}

	public Optional<MockRunnerScenario> find(String name) {
		return Optional.ofNullable(scenarios.get(name));
	}

	public MockRunnerScenario require(String name) {
		return find(name).orElseThrow(() ->
			new IllegalArgumentException("Unknown runner scenario: " + name + " (registered: " + scenarios.keySet() + ")"));
	}

	public Map<String, MockRunnerScenario> all() {
		return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(scenarios));
	}

	public byte[] loadFixtureBytes(MockRunnerScenario scenario) {
		if (scenario.fixtureResource() == null) {
			return new byte[0];
		}
		try (InputStream stream = Thread.currentThread().getContextClassLoader()
			.getResourceAsStream(scenario.fixtureResource())) {
			if (stream == null) {
				log.warn("loadFixtureBytes missing scenario={} resource={}", scenario.name(), scenario.fixtureResource());
				return new byte[0];
			}
			return stream.readAllBytes();
		} catch (IOException error) {
			throw new IllegalStateException("Failed to load runner scenario " + scenario.name(), error);
		}
	}

	public void clearTestScenarios() {
		scenarios.entrySet().removeIf(entry -> entry.getKey().startsWith("test-"));
	}
}
