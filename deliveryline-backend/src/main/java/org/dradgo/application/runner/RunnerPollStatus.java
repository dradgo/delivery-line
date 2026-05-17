package org.dradgo.application.runner;

import java.time.OffsetDateTime;
import java.util.Objects;
import org.dradgo.domain.registry.FailureCategory;

public sealed interface RunnerPollStatus {

  record Running() implements RunnerPollStatus {}

  record HeartbeatTouched(OffsetDateTime activityTimestamp) implements RunnerPollStatus {

    public HeartbeatTouched {
      Objects.requireNonNull(activityTimestamp, "activityTimestamp");
    }
  }

  record Completed() implements RunnerPollStatus {}

  record Failed(FailureCategory failureCategory) implements RunnerPollStatus {

    public Failed {
      Objects.requireNonNull(failureCategory, "failureCategory");
    }
  }

  record Unknown() implements RunnerPollStatus {}
}
