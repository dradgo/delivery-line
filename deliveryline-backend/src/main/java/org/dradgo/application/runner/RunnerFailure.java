package org.dradgo.application.runner;

import java.util.Objects;
import org.dradgo.domain.registry.FailureCategory;

public record RunnerFailure(
    String runnerExecutionId, FailureCategory failureCategory, String reason) {

  public RunnerFailure {
    if (runnerExecutionId == null || runnerExecutionId.isBlank()) {
      throw new IllegalArgumentException("runnerExecutionId must not be blank");
    }
    Objects.requireNonNull(failureCategory, "failureCategory");
  }
}
