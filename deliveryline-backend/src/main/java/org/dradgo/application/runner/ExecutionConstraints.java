package org.dradgo.application.runner;

import java.time.Duration;
import java.util.Objects;

public record ExecutionConstraints(Duration timeout, boolean allowRawOutput) {

  public ExecutionConstraints {
    Objects.requireNonNull(timeout, "timeout");
    if (timeout.isZero() || timeout.isNegative()) {
      throw new IllegalArgumentException("timeout must be positive");
    }
  }

  public int timeoutSeconds() {
    return Math.toIntExact(timeout.getSeconds());
  }
}
