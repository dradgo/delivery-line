package org.dradgo.application.runner.spi;

import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * Story 3.2 AC2 sub-bullet (c): byte-count + last-modified observation of {@code
 * logs/runner.stdout} surfaced by {@link RunnerWorkspaceStore#observeLogGrowth(String)}. The
 * adapter holds the previous observation per runner-execution-id and only emits {@code
 * HeartbeatTouched} when both {@code byteCount} has increased AND {@code lastModifiedAt} has moved
 * forward.
 */
public record LogGrowthObservation(long byteCount, OffsetDateTime lastModifiedAt) {

  public LogGrowthObservation {
    Objects.requireNonNull(lastModifiedAt, "lastModifiedAt");
    if (byteCount < 0L) {
      throw new IllegalArgumentException("byteCount must not be negative");
    }
  }
}
