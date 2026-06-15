package org.dradgo.application.runner.spi;

import org.dradgo.domain.registry.RunnerExecutionStatus;

/** Result of a takeover cancellation after the runner row has been locked. */
public record RunnerTakeoverCancellation(
    String runnerExecutionId, RunnerExecutionStatus previousStatus) {}
