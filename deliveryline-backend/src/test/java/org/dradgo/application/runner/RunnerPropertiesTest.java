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
    // Story 3.6 AC4 — log capture stays local-only by default (no shareable elevation).
    assertEquals(false, defaults.allowShareableLogs());
  }

  @Test
  void allowShareableLogsBindsTheConfiguredValue() {
    // Story 3.6 AC4 — the flag is the operator opt-in gate for shareable-redacted log elevation.
    RunnerProperties enabled =
        new RunnerProperties(
            2.0d,
            java.util.Map.of(),
            10_000L,
            50,
            60_000L,
            5_000L,
            RunnerProperties.Recovery.defaults(),
            RunnerProperties.Mock.defaults(),
            RunnerProperties.Scheduling.defaults(),
            RunnerProperties.Docker.defaults(),
            RunnerProperties.defaultSecretEnvNames(),
            true,
            RunnerProperties.SpecStage.defaults());
    assertEquals(true, enabled.allowShareableLogs());
  }

  @Test
  void specStageDefaultsToCodexWithAutoDispatchOn() {
    // Story 3a-1 (AC10) — non-Spring construction defaults: codex image + auto-dispatch ON.
    RunnerProperties.SpecStage specStage = RunnerProperties.defaults().specStage();
    assertEquals(RunnerKind.CODEX, specStage.kind());
    assertEquals(true, specStage.autoDispatch());
  }

  @Test
  void kindForStageHonorsSpecStageForInvestigationAndDockerDefaultOtherwise() {
    // Story 3a-1 (AC10) — INVESTIGATION resolves to spec-stage.kind; other stages keep the docker
    // default kind.
    RunnerProperties claudeSpec =
        new RunnerProperties(
            2.0d,
            java.util.Map.of(),
            10_000L,
            50,
            60_000L,
            5_000L,
            RunnerProperties.Recovery.defaults(),
            RunnerProperties.Mock.defaults(),
            RunnerProperties.Scheduling.defaults(),
            RunnerProperties.Docker.defaults(),
            RunnerProperties.defaultSecretEnvNames(),
            false,
            new RunnerProperties.SpecStage(RunnerKind.CLAUDE, true));
    assertEquals(
        RunnerKind.CLAUDE,
        claudeSpec.kindForStage(org.dradgo.domain.registry.RunnerStage.INVESTIGATION));
    assertEquals(
        RunnerKind.CODEX,
        claudeSpec.kindForStage(org.dradgo.domain.registry.RunnerStage.EXECUTION));
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
                RunnerProperties.Docker.defaults(),
                RunnerProperties.defaultSecretEnvNames(),
                false,
                RunnerProperties.SpecStage.defaults()));
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
                RunnerProperties.Docker.defaults(),
                RunnerProperties.defaultSecretEnvNames(),
                false,
                RunnerProperties.SpecStage.defaults()));
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

  @Test
  void danglingContainerMinAgeRejectsValueAboveOneDay() {
    // Story 3.2a code-review (2026-05-29): the upper bound guards now.minusSeconds(...) overflow.
    docker(3_600_000L, 86_400L); // exactly one day — boundary allowed, no throw
    assertThrows(IllegalArgumentException.class, () -> docker(3_600_000L, 86_401L));
  }

  @Test
  void redactImageTagStripsEmbeddedCredentialsAndLeavesPlainRefsUntouched() {
    // Story 3.2a code-review (2026-05-29): directly pin the credential-redaction contract so a
    // credential-bearing image tag can never leak into the runner.dispatched audit event / logs.
    assertEquals(
        "***@host/image:tag", RunnerProperties.Docker.redactImageTag("user:secret@host/image:tag"));
    // Un-credentialed reference is untouched.
    assertEquals(
        "deliveryline/codex-runner:latest",
        RunnerProperties.Docker.redactImageTag("deliveryline/codex-runner:latest"));
    // Registry with a port + digest (no credentials) must NOT be over-redacted.
    assertEquals(
        "registry.example:5000/image@sha256:abc",
        RunnerProperties.Docker.redactImageTag("registry.example:5000/image@sha256:abc"));
    // Null / blank pass through unchanged.
    assertEquals(null, RunnerProperties.Docker.redactImageTag(null));
    assertEquals("  ", RunnerProperties.Docker.redactImageTag("  "));
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
