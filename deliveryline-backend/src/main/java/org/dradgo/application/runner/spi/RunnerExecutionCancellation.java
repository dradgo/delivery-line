package org.dradgo.application.runner.spi;

import org.dradgo.domain.registry.RunnerExecutionStatus;

/**
 * Story 4.8 (AC4) — result of a pause cancellation after the runner row has been locked. Neutral
 * sibling of {@link RunnerTakeoverCancellation} (which stays untouched so 3.22's takeover callers
 * keep their exact signature).
 */
public record RunnerExecutionCancellation(
    String runnerExecutionId, RunnerExecutionStatus previousStatus) {}
