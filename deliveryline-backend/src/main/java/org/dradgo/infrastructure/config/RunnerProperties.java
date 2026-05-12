package org.dradgo.infrastructure.config;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.dradgo.domain.registry.RunnerStage;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Constructor-bound configuration for the runner subsystem (story 1.13 Task 6).
 *
 * <p>Defaults match the story's specified values. Nested {@code recovery} and {@code mock}
 * groups keep stage-keyed maps typed against {@link RunnerStage} so the
 * {@code domain/registry} enum is the only legal stage form.
 */
@ConfigurationProperties("deliveryline.runner")
public record RunnerProperties(
	double staleThresholdMultiplier,
	Map<RunnerStage, Duration> stageTimeouts,
	long timeoutScanIntervalMs,
	int timeoutScanBatchSize,
	Recovery recovery,
	Mock mock,
	Scheduling scheduling
) {

	public RunnerProperties {
		if (staleThresholdMultiplier <= 0.0d) {
			throw new IllegalArgumentException(
				"deliveryline.runner.stale-threshold-multiplier must be positive: " + staleThresholdMultiplier);
		}
		if (timeoutScanIntervalMs <= 0L) {
			throw new IllegalArgumentException(
				"deliveryline.runner.timeout-scan-interval-ms must be positive: " + timeoutScanIntervalMs);
		}
		if (timeoutScanBatchSize <= 0) {
			throw new IllegalArgumentException(
				"deliveryline.runner.timeout-scan-batch-size must be positive: " + timeoutScanBatchSize);
		}
		stageTimeouts = stageTimeouts == null ? Map.of() : Map.copyOf(stageTimeouts);
		recovery = recovery == null ? Recovery.defaults() : recovery;
		mock = mock == null ? Mock.defaults() : mock;
		scheduling = scheduling == null ? Scheduling.defaults() : scheduling;
	}

	public static RunnerProperties defaults() {
		Map<RunnerStage, Duration> timeouts = new LinkedHashMap<>();
		timeouts.put(RunnerStage.INVESTIGATION, Duration.ofSeconds(600));
		timeouts.put(RunnerStage.EXECUTION, Duration.ofSeconds(600));
		return new RunnerProperties(
			2.0d,
			timeouts,
			10_000L,
			50,
			Recovery.defaults(),
			Mock.defaults(),
			Scheduling.defaults());
	}

	public Duration timeoutFor(RunnerStage stage) {
		Objects.requireNonNull(stage, "stage");
		Duration explicit = stageTimeouts.get(stage);
		return explicit != null ? explicit : Duration.ofSeconds(600);
	}

	public record Recovery(int batchSize) {

		public Recovery {
			if (batchSize <= 0) {
				throw new IllegalArgumentException(
					"deliveryline.runner.recovery.batch-size must be positive: " + batchSize);
			}
		}

		public static Recovery defaults() {
			return new Recovery(100);
		}
	}

	public record Mock(Map<RunnerStage, String> defaultScenario) {

		public Mock {
			defaultScenario = defaultScenario == null ? Map.of() : Map.copyOf(defaultScenario);
		}

		public static Mock defaults() {
			Map<RunnerStage, String> scenarios = new LinkedHashMap<>();
			scenarios.put(RunnerStage.INVESTIGATION, "happy-spec");
			scenarios.put(RunnerStage.EXECUTION, "happy-implementation-plan");
			return new Mock(scenarios);
		}

		public String scenarioFor(RunnerStage stage) {
			Objects.requireNonNull(stage, "stage");
			String scenario = defaultScenario.get(stage);
			return scenario != null ? scenario : "happy-spec";
		}
	}

	public record Scheduling(boolean enabled) {

		public static Scheduling defaults() {
			return new Scheduling(true);
		}
	}
}
