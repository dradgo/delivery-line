package org.dradgo.application.runner;

import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Per-run DinD Testcontainers sidecar tunables, bound from {@code
 * deliveryline.runner.testcontainers.*}. Kept as a SEPARATE @ConfigurationProperties (not a
 * component on the RunnerProperties.Docker record) to avoid that record's constructor fan-out.
 */
@ConfigurationProperties(prefix = "deliveryline.runner.testcontainers")
public record TestcontainersProperties(
    @DefaultValue("docker:27-dind") String dindImage,
    @DefaultValue("2147483648") long memoryBytes,
    @DefaultValue("60s") Duration readinessTimeout) {

  public TestcontainersProperties {
    if (dindImage == null || dindImage.isBlank()) {
      throw new IllegalArgumentException(
          "deliveryline.runner.testcontainers.dind-image must be set");
    }
    if (memoryBytes <= 0L) {
      throw new IllegalArgumentException(
          "deliveryline.runner.testcontainers.memory-bytes must be positive: " + memoryBytes);
    }
    Objects.requireNonNull(readinessTimeout, "readinessTimeout");
    if (readinessTimeout.isZero() || readinessTimeout.isNegative()) {
      throw new IllegalArgumentException(
          "deliveryline.runner.testcontainers.readiness-timeout must be positive");
    }
  }

  public static TestcontainersProperties defaults() {
    return new TestcontainersProperties(
        "docker:27-dind", 2L * 1024 * 1024 * 1024, Duration.ofSeconds(60));
  }
}
