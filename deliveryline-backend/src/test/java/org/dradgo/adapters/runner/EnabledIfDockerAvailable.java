package org.dradgo.adapters.runner;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.testcontainers.DockerClientFactory;

/**
 * Story 3.1 OQ-5 — gates Docker-backed ITs on a live Docker engine via Testcontainers' {@code
 * DockerClientFactory.instance().isDockerAvailable()} probe. Contributors without Docker (or
 * machines where Docker Desktop is paused) skip the tests instead of failing. Probe result is
 * cached for the JVM lifetime so multiple {@code @EnabledIfDockerAvailable} tests do not each
 * re-probe.
 *
 * <p>Used in tandem with {@code @Tag("docker-runner-it")}; CI selects the docker-runner-it tier on
 * Linux runners only.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@ExtendWith(EnabledIfDockerAvailable.Condition.class)
public @interface EnabledIfDockerAvailable {

  class Condition implements ExecutionCondition {

    private static volatile Boolean cached;

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
      if (isDockerAvailable()) {
        return ConditionEvaluationResult.enabled("Docker engine available");
      }
      return ConditionEvaluationResult.disabled(
          "Docker engine not available (DockerClientFactory.isDockerAvailable() returned false)");
    }

    private static boolean isDockerAvailable() {
      Boolean snapshot = cached;
      if (snapshot != null) {
        return snapshot;
      }
      synchronized (Condition.class) {
        if (cached == null) {
          try {
            cached = DockerClientFactory.instance().isDockerAvailable();
          } catch (RuntimeException error) {
            cached = false;
          }
        }
        return cached;
      }
    }
  }
}
