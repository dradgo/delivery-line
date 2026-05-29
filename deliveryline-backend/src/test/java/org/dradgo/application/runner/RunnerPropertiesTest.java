package org.dradgo.application.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.time.Duration;
import java.util.EnumMap;
import org.dradgo.domain.registry.RunnerKind;
import org.junit.jupiter.api.Test;

/**
 * Story 3.2a AC12 — directed coverage of {@link RunnerProperties} binding fields and positive-value
 * guards, including the new {@code Docker.danglingContainerMinAgeSeconds} field added by this
 * story.
 */
class RunnerPropertiesTest {

  @Test
  void defaultsExposeTheStory32SchedulingAndDockerCleanupFields() {
    RunnerProperties defaults = RunnerProperties.defaults();
    assertEquals(60_000L, defaults.staleScanIntervalMs());
    assertEquals(5_000L, defaults.pollIntervalMs());
    assertEquals(10_000L, defaults.timeoutScanIntervalMs());
    assertEquals(3_600_000L, defaults.docker().workspaceCleanupIntervalMs());
    // Story 3.2a AC4 — the new dangling-container min-age grace window.
    assertEquals(120L, defaults.docker().danglingContainerMinAgeSeconds());
  }

  @Test
  void staleScanIntervalMustBePositive() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RunnerProperties(
                2.0d,
                java.util.Map.of(),
                10_000L,
                50,
                0L, // staleScanIntervalMs
                5_000L,
                RunnerProperties.Recovery.defaults(),
                RunnerProperties.Mock.defaults(),
                RunnerProperties.Scheduling.defaults(),
                RunnerProperties.Docker.defaults()));
  }

  @Test
  void pollIntervalMustBePositive() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RunnerProperties(
                2.0d,
                java.util.Map.of(),
                10_000L,
                50,
                60_000L,
                0L, // pollIntervalMs
                RunnerProperties.Recovery.defaults(),
                RunnerProperties.Mock.defaults(),
                RunnerProperties.Scheduling.defaults(),
                RunnerProperties.Docker.defaults()));
  }

  @Test
  void workspaceCleanupIntervalMustBePositive() {
    assertThrows(IllegalArgumentException.class, () -> docker(0L, 120L));
  }

  @Test
  void danglingContainerMinAgeMayBeZeroButNotNegative() {
    // Zero disables the guard (allowed); negative is rejected.
    docker(3_600_000L, 0L); // no throw
    assertThrows(IllegalArgumentException.class, () -> docker(3_600_000L, -1L));
  }

  private static RunnerProperties.Docker docker(
      long workspaceCleanupIntervalMs, long danglingContainerMinAgeSeconds) {
    EnumMap<RunnerKind, String> tags = new EnumMap<>(RunnerKind.class);
    tags.put(RunnerKind.CODEX, "deliveryline/codex-runner:latest");
    tags.put(RunnerKind.CLAUDE, "deliveryline/claude-runner:latest");
    return new RunnerProperties.Docker(
        RunnerKind.CODEX,
        tags,
        Path.of("runner-work"),
        24L,
        workspaceCleanupIntervalMs,
        Duration.ofSeconds(30L),
        Duration.ofSeconds(30L),
        danglingContainerMinAgeSeconds);
  }
}
